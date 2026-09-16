package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Model-level streaming dispatch tests. They prove {@link OpenRouterChatModel#stream}
 * routes to the correct {@link OpenRouterApi} stream method for each request mode, sets
 * {@code stream=true} on the outgoing request, and does not call the opposite method.
 * API-boundary SSE parsing and stream failures are covered in
 * {@code OpenRouterApiStreamingContractTests}; this layer owns dispatch only.
 */
class OpenRouterChatModelStreamingTests {

	private static final String CHAT_MODEL = "openai/gpt-5.4-mini";

	private ChatCompletionChunk chunk(String content, String finishReason) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, CHAT_MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", content, null, null), finishReason, finishReason)),
				null, null);
	}

	private ResponsesStreamEvent textDelta(String text) {
		return new ResponsesStreamEvent("response.output_text.delta", text, null, null, null);
	}

	@Test
	void defaultModeStreamsViaChatCompletionsAndSetsStreamTrue() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(chunk("Hel", null), chunk("lo", "stop")));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier
			.create(model
				.stream(new Prompt(List.of(new UserMessage("hi")),
						OpenRouterChatOptions.builder().model(CHAT_MODEL).build()))
				.map(response -> response.getResult().getOutput().getText()))
			.expectNext("Hel")
			.expectNext("lo")
			.verifyComplete();

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletionStream(captor.capture());
		assertThat(captor.getValue().stream()).isTrue();
		verify(api, never()).responsesStream(any());
	}

	@Test
	void responsesModeStreamsViaResponsesStreamAndSetsStreamTrue() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any())).thenReturn(Flux.just(textDelta("Hel"), textDelta("lo")));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier
			.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder()
						.model("openai/gpt-5.4")
						.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
						.build()))
				.map(response -> response.getResult().getOutput().getText()))
			.expectNext("Hel")
			.expectNext("lo")
			.verifyComplete();

		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api).responsesStream(captor.capture());
		assertThat(captor.getValue().stream()).isTrue();
		verify(api, never()).chatCompletionStream(any());
	}

	@Test
	void runtimeOptionsWithoutModeReplaceConfiguredResponsesDefaultMode() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(chunk("ok", "stop")));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model("openai/gpt-5.4")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.build())
			.build();

		// The supplied option set is complete. Its absent mode resolves independently to
		// chat completions instead of inheriting the configured Responses mode.
		model
			.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model("anthropic/claude-sonnet-4").temperature(0.2).build()))
			.blockLast(Duration.ofSeconds(5));

		verify(api).chatCompletionStream(any());
		verify(api, never()).responsesStream(any());
	}

	@Test
	void runtimeModeOverridesConfiguredDefaultMode() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(chunk("ok", "stop")));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model("openai/gpt-5.4")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.build())
			.build();

		model
			.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS).build()))
			.blockLast(Duration.ofSeconds(5));

		verify(api).chatCompletionStream(any());
		verify(api, never()).responsesStream(any());
	}

	@Test
	void chatCompletionsStreamPropagatesApiErrorsToTheCaller() {
		OpenRouterApiException failure = new OpenRouterApiException("stream failed", HttpStatus.BAD_GATEWAY, "{}");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.error(failure));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier
			.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder().model(CHAT_MODEL).build())))
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify(Duration.ofSeconds(5));
	}

	@Test
	void responsesStreamPropagatesApiErrorsToTheCaller() {
		OpenRouterApiException failure = new OpenRouterApiException("stream failed", HttpStatus.SERVICE_UNAVAILABLE,
				"{}");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any())).thenReturn(Flux.error(failure));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier
			.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
					OpenRouterChatOptions.builder()
						.model("openai/gpt-5.4")
						.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
						.build())))
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify(Duration.ofSeconds(5));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void transientResponsesFailureDoesNotResubscribe(boolean partialOutput) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		AtomicInteger subscriptions = new AtomicInteger();
		ResponsesResult failed = new ResponsesResult("resp-1", "response", null, CHAT_MODEL, "failed", List.of(), null,
				new StreamError("server_error", "synthetic failure"));
		ResponsesStreamEvent failure = new ResponsesStreamEvent("response.failed", null, null, failed, null);
		when(api.responsesStream(any())).thenReturn(Flux.defer(() -> {
			subscriptions.incrementAndGet();
			return partialOutput ? Flux.just(textDelta("partial"), failure) : Flux.just(failure);
		}));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		var verifier = StepVerifier.create(model.stream(new Prompt(List.of(new UserMessage("hi")),
				OpenRouterChatOptions.builder()
					.model(CHAT_MODEL)
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build())));
		if (partialOutput) {
			verifier
				.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("partial"));
		}
		verifier.expectError(OpenRouterTransientApiException.class).verify(Duration.ofSeconds(5));
		assertThat(subscriptions).hasValue(1);
		verify(api, never()).responses(any());
	}

	@Test
	void streamCompletesEmptyWhenProviderEmitsNoChunks() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		StepVerifier.create(model.stream(
				new Prompt(List.of(new UserMessage("hi")), OpenRouterChatOptions.builder().model(CHAT_MODEL).build())))
			.verifyComplete();
	}

}
