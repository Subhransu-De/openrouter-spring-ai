package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/**
 * Chat Completions stream state budget set through
 * {@link OpenRouterChatModel.Builder#streamingStateMaxBytes(long)} and
 * {@link OpenRouterChatModel.Builder#streamingStateMaxChoices(int)}. The budget is
 * documented as not affecting synchronous calls or Responses.
 */
class OpenRouterChatModelStreamingStateLimitsTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String REASONING_CHUNK = "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"synthetic\"}}]}";

	// Every active choice reserves a fixed diagnostic allowance, so a byte limit has to
	// stay above it for the metadata charges that these tests vary to matter.
	private static final long BYTE_LIMIT = 8192;

	@Test
	void byteLimitFromTheBuilderAdmitsAShortStreamAndStopsALongOne() {
		var model = chatModel(chunks(REASONING_CHUNK, 3)).streamingStateMaxBytes(BYTE_LIMIT).build();
		StepVerifier.create(model.stream(chatPrompt())).expectNextCount(3).verifyComplete();

		AtomicBoolean cancelled = new AtomicBoolean();
		var longStream = chatModel(chunks(REASONING_CHUNK, 500).doOnCancel(() -> cancelled.set(true)))
			.streamingStateMaxBytes(BYTE_LIMIT)
			.build();
		StepVerifier.create(longStream.stream(chatPrompt()))
			.thenConsumeWhile(response -> true)
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterLimitExceededException.class, limit -> {
						assertThat(limit.getLimit()).isEqualTo(Limit.CHAT_STATE_BYTES);
						assertThat(limit.getConfiguredLimit()).isEqualTo(BYTE_LIMIT);
						assertThat(limit.getObservedValue()).isGreaterThan(BYTE_LIMIT);
					}))
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void choiceLimitFromTheBuilderAdmitsAsManyActiveChoicesAsConfigured() {
		Flux<ChatCompletionChunk> twoChoices = Flux.just(chunk(
				"{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"}},{\"index\":1,\"delta\":{\"content\":\"b\"}}]}"));

		var enough = chatModel(twoChoices).streamingStateMaxChoices(2).build();
		StepVerifier.create(enough.stream(chatPrompt())).expectNextCount(1).verifyComplete();

		var tooFew = chatModel(twoChoices).streamingStateMaxChoices(1).build();
		StepVerifier.create(tooFew.stream(chatPrompt()))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterLimitExceededException.class, limit -> {
						assertThat(limit.getLimit()).isEqualTo(Limit.CHAT_STATE_CHOICES);
						assertThat(limit.getConfiguredLimit()).isEqualTo(1);
						assertThat(limit.getObservedValue()).isEqualTo(2);
					}))
			.verify();
	}

	@Test
	void theBudgetDoesNotApplyToSynchronousCallsOrResponses() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		// The same one-byte, one-choice budget stops the Chat Completions stream ...
		when(api.chatCompletionStream(any())).thenReturn(chunks(REASONING_CHUNK, 3));
		// ... but not a two-choice synchronous Chat Completions call ...
		when(api.chatCompletion(any())).thenReturn(JSON.readValue("""
				{"id":"gen-1","choices":[
				{"index":0,"message":{"role":"assistant","content":"a"},"finish_reason":"stop"},
				{"index":1,"message":{"role":"assistant","content":"b"},"finish_reason":"stop"}]}
				""", ChatCompletionResponse.class));
		// ... nor a Responses stream or call with metadata-bearing state.
		var reasoning = responsesEvent("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"synthetic\"}");
		var completed = responsesEvent("""
				{"type":"response.completed","response":{"id":"resp-1","status":"completed","output":[
				{"type":"message","content":[{"type":"output_text","text":"done"}]}]}}
				""");
		when(api.responsesStream(any())).thenReturn(Flux.just(reasoning, completed));
		when(api.responses(any())).thenReturn(completed.response());
		var model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.streamingStateMaxBytes(1)
			.streamingStateMaxChoices(1)
			.build();

		StepVerifier.create(model.stream(chatPrompt()))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterLimitExceededException.class,
							limit -> assertThat(limit.getLimit()).isEqualTo(Limit.CHAT_STATE_BYTES)))
			.verify();
		assertThat(model.call(chatPrompt()).getResults()).hasSize(2);
		assertThat(model.call(responsesPrompt()).getResult().getOutput().getText()).isEqualTo("done");
		StepVerifier.create(model.stream(responsesPrompt())).expectNextCount(2).verifyComplete();
	}

	private static OpenRouterChatModel.Builder chatModel(Flux<ChatCompletionChunk> chunks) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks);
		return OpenRouterChatModel.builder().openRouterApi(api);
	}

	private static Flux<ChatCompletionChunk> chunks(String json, int count) {
		return Flux.range(0, count).map(index -> chunk(json));
	}

	private static ChatCompletionChunk chunk(String json) {
		return JSON.readValue(json, ChatCompletionChunk.class);
	}

	private static ResponsesStreamEvent responsesEvent(String json) {
		return JSON.readValue(json, ResponsesStreamEvent.class);
	}

	private static Prompt chatPrompt() {
		return new Prompt("synthetic", OpenRouterChatOptions.builder().model("synthetic").build());
	}

	private static Prompt responsesPrompt() {
		return new Prompt("synthetic",
				OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build());
	}

}
