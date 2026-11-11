package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Model-level streaming behavior that does not depend on the dispatch table in
 * {@code OpenRouterChatModelRequestModeTests}: failures reach the caller unchanged, an
 * empty provider stream completes empty, and a transient stream failure is not
 * resubscribed in either request mode. API-boundary SSE parsing is covered in
 * {@code OpenRouterApiStreamingContractTests}.
 */
class OpenRouterChatModelStreamingTests {

	private static final String CHAT_MODEL = "openai/gpt-5.4-mini";

	private static ResponsesStreamEvent textDelta(String text) {
		return new ResponsesStreamEvent("response.output_text.delta", text, null, null, null);
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamPropagatesApiErrorsToTheCaller(OpenRouterRequestMode mode) {
		OpenRouterApiException failure = new OpenRouterApiException("stream failed", HttpStatus.BAD_GATEWAY, "{}");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.error(failure));
		when(api.responsesStream(any())).thenReturn(Flux.error(failure));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier
			.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).requestMode(mode).build())))
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify(Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamCompletesEmptyWhenProviderEmitsNothing(OpenRouterRequestMode mode) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.empty());
		when(api.responsesStream(any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
				OpenRouterChatOptions.builder().model(CHAT_MODEL).requestMode(mode).build())))
			.verifyComplete();
	}

	static Stream<Arguments> modesWithEmittedText() {
		return Stream.of(OpenRouterRequestMode.values())
			.flatMap(mode -> Stream.of(
					Arguments.of(Named.of(mode.name(), mode), Named.of("after partial output", List.of("partial"))),
					Arguments.of(Named.of(mode.name(), mode), Named.of("before any output", List.<String>of()))));
	}

	@ParameterizedTest(name = "{0}, failure {1}")
	@MethodSource("modesWithEmittedText")
	void transientStreamFailureReachesTheCallerWithoutResubscribing(OpenRouterRequestMode mode,
			List<String> emittedBeforeFailure) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		AtomicInteger subscriptions = new AtomicInteger();
		when(api.chatCompletionStream(any())).thenReturn(Flux.defer(() -> {
			subscriptions.incrementAndGet();
			return Flux.fromIterable(emittedBeforeFailure)
				.map(OpenRouterChatModelStreamingTests::chatChunk)
				.concatWith(Flux.error(new OpenRouterTransientApiException("synthetic failure",
						HttpStatus.SERVICE_UNAVAILABLE, "{}", null, null, "/chat/completions")));
		}));
		ResponsesResult failed = new ResponsesResult("resp-1", "response", null, CHAT_MODEL, "failed", List.of(), null,
				new StreamError("server_error", "synthetic failure"));
		when(api.responsesStream(any())).thenReturn(Flux.defer(() -> {
			subscriptions.incrementAndGet();
			return Flux.fromIterable(emittedBeforeFailure)
				.map(OpenRouterChatModelStreamingTests::textDelta)
				.concatWith(Flux.just(new ResponsesStreamEvent("response.failed", null, null, failed, null)));
		}));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		List<String> emitted = new ArrayList<>();

		Throwable failure = catchThrowable(() -> model
			.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).requestMode(mode).build()))
			.doOnNext(response -> emitted.add(response.getResult().getOutput().getText()))
			.blockLast(Duration.ofSeconds(5)));

		assertThat(failure).isInstanceOf(OpenRouterTransientApiException.class);
		assertThat(emitted).containsExactlyElementsOf(emittedBeforeFailure);
		assertThat(subscriptions).hasValue(1);
	}

	@Test
	void multipleChoicesStreamThroughTheModelWithPerChoiceTextAndFinishReason() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(choicesChunk(choice(0, "a", null), choice(1, "x", null)),
					choicesChunk(choice(0, "b", "stop"), choice(1, "y", "length"))));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		List<ChatResponse> responses = model
			.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).build()))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).getResults()).extracting(generation -> generation.getOutput().getText())
			.containsExactly("a", "x");
		assertThat(responses.get(0).getResults())
			.extracting(generation -> generation.getMetadata().<Integer>get("openrouter.choice_index"))
			.containsExactly(0, 1);
		assertThat(responses.get(1).getResults()).extracting(generation -> generation.getOutput().getText())
			.containsExactly("b", "y");
		assertThat(responses.get(1).getResults())
			.extracting(generation -> generation.getMetadata().<Integer>get("openrouter.choice_index"))
			.containsExactly(0, 1);
		assertThat(responses.get(1).getResults()).extracting(generation -> generation.getMetadata().getFinishReason())
			.containsExactly("STOP", "LENGTH");
	}

	@Test
	void chatDoneMarkerAfterATrailingUsageChunkCompletesTheStreamWithUsage() {
		String body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\n"
				+ "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}\n\n"
				+ "data: [DONE]\n\n";
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(sseApi(Flux.just(buffer(body))))
			.build();

		List<ChatResponse> responses = model
			.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).build()))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(responses).flatExtracting(ChatResponse::getResults)
			.extracting(generation -> generation.getOutput().getText())
			.containsExactly("hi");
		assertThat(responses).flatExtracting(ChatResponse::getResults)
			.extracting(generation -> generation.getMetadata().getFinishReason())
			.containsExactly("STOP");
		assertThat(responses.get(responses.size() - 1).getMetadata().getUsage().getTotalTokens()).isEqualTo(3);
	}

	@Test
	void consumerCancellationOfAChatStreamClosesTheOpenProviderBody() {
		AtomicBoolean bodyCancelled = new AtomicBoolean();
		Flux<DataBuffer> openBody = Flux
			.concat(Flux.just(buffer("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")),
					Flux.never())
			.doOnCancel(() -> bodyCancelled.set(true));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(sseApi(openBody)).build();

		StepVerifier
			.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).build())))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hi"))
			.thenCancel()
			.verify(Duration.ofSeconds(5));

		assertThat(bodyCancelled).isTrue();
	}

	@Test
	void chatStreamEndingBeforeDoneFailsAsTruncatedOnEverySubscription() {
		String body = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n";
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body)
					.build())))
			.build();
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Flux<ChatResponse> stream = model.stream(
				new Prompt(List.of(new UserMessage("hi")), OpenRouterChatOptions.builder().model(CHAT_MODEL).build()));

		StepVerifier.create(stream)
			.expectNextCount(1)
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify(Duration.ofSeconds(5));
		StepVerifier.create(stream)
			.expectNextCount(1)
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify(Duration.ofSeconds(5));
	}

	private static DataBuffer buffer(String text) {
		return new DefaultDataBufferFactory().wrap(text.getBytes(StandardCharsets.UTF_8));
	}

	private static OpenRouterApi sseApi(Flux<DataBuffer> body) {
		return OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body)
					.build())))
			.build();
	}

	private static Choice choice(int index, String text, String finishReason) {
		return new Choice(index, null, new Delta("assistant", text, null, null), finishReason, finishReason);
	}

	private static ChatCompletionChunk choicesChunk(Choice... choices) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, CHAT_MODEL, "openai", List.of(choices),
				null, null);
	}

	private static ChatCompletionChunk chatChunk(String text) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, CHAT_MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", text, null, null), null, null)), null, null);
	}

}
