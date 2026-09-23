package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterResponsesTerminationTests {

	private static final String TEXT = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n";

	private static final String DONE = "data: [DONE]\n\n";

	@ParameterizedTest
	@ValueSource(strings = { "", ": synthetic keepalive\n\n", "data: [DONE]\n\n", "data: [DONE]\ndata: {invalid}\n\n" })
	void missingTerminalFailsEmptyAndTextStreamsOnEverySubscription(String tail) {
		for (String prefix : new String[] { "", TEXT }) {
			Flux<ChatResponse> stream = model(prefix + tail).stream(new Prompt("synthetic"));
			for (int i = 0; i < 2; i++) {
				StepVerifier.create(stream)
					.expectNextCount(prefix.isEmpty() ? 0 : 1)
					.expectErrorSatisfies(
							error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
								.hasMessageContaining("before protocol termination"))
					.verify();
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "completed", "incomplete" })
	void terminalPreservesUsageAndFinishMetadataWithoutDone(String status) {
		String terminal = "data: {\"type\":\"response." + status + "\",\"response\":{"
				+ "\"id\":\"synthetic\",\"status\":\"" + status
				+ "\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],"
				+ "\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}}\n\n";
		StepVerifier.create(model(TEXT + terminal + "data: {invalid}\n\n").stream(new Prompt("synthetic")))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hello"))
			.assertNext(response -> {
				assertThat(response.getResult().getMetadata().getFinishReason())
					.isEqualTo("completed".equals(status) ? "STOP" : status);
				assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
			})
			.verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "response.failed", "error", "response.output_text.error" })
	void terminalErrorsRetainProviderCategory(String type) {
		String error = "{\"code\":\"server_error\",\"message\":\"synthetic failure\"}";
		String fields = "response.failed".equals(type) ? "\"response\":{\"status\":\"failed\",\"error\":" + error + "}"
				: "\"error\":" + error;
		StepVerifier
			.create(model("data: {\"type\":\"" + type + "\"," + fields + "}\n\n" + DONE)
				.stream(new Prompt("synthetic")))
			.expectError(OpenRouterTransientApiException.class)
			.verify();
	}

	@Test
	void legacyDoneIsExplicitlyRejected() {
		StepVerifier.create(model("""
				data: {"type":"response.done","response":{"status":"completed","usage":{"total_tokens":5}}}

				""" + DONE).stream(new Prompt("synthetic")))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("Legacy response.done is unsupported"))
			.verify();
	}

	@Test
	void unfinishedRoundNeverExecutesAdvisorTools() {
		AtomicInteger executions = new AtomicInteger();
		var callback = FunctionToolCallback.builder("synthetic_action", (Map<String, Object> input) -> {
			executions.incrementAndGet();
			return "ok";
		}).description("Synthetic counter").inputType(Map.class).build();
		var client = ChatClient
			.builder(
					model("""
							data: {"type":"response.output_item.done","item":{"type":"function_call","id":"synthetic","call_id":"synthetic","name":"synthetic_action","status":"completed","arguments":"{}"}}

							"""
							+ DONE))
			.defaultAdvisors(ToolCallingAdvisor.builder().build())
			.build();
		StepVerifier
			.create(client.prompt()
				.user("synthetic")
				.options(OpenRouterChatOptions.builder().toolCallbacks(callback))
				.stream()
				.content())
			.thenConsumeWhile(value -> true)
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
		assertThat(executions).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void cancellationAndDoneStopAnOpenBody(boolean done) {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<String> body = Flux.just(TEXT + (done ? DONE : ""))
			.concatWith(Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		var verification = StepVerifier.create(model(body).stream(new Prompt("synthetic"))).expectNextCount(1);
		if (done) {
			verification.expectError(OpenRouterTruncatedResponseException.class)
				.verify(java.time.Duration.ofSeconds(3));
		}
		else {
			verification.thenCancel().verify(java.time.Duration.ofSeconds(3));
		}
		assertThat(cancelled).isTrue();
	}

	private OpenRouterChatModel model(String body) {
		return model(Flux.just(body));
	}

	private OpenRouterChatModel model(Flux<String> body) {
		var api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(request -> {
				AtomicBoolean subscribed = new AtomicBoolean();
				return Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(Flux.defer(() -> subscribed.compareAndSet(false, true) ? body : Flux.empty())
						.map(value -> new org.springframework.core.io.buffer.DefaultDataBufferFactory()
							.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
					.build());
			}))
			.build();
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model("synthetic")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.build())
			.build();
	}

}
