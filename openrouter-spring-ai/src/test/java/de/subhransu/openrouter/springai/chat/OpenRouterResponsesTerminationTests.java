package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
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

	static Stream<Arguments> missingTerminalStreams() {
		Map<String, String> tails = new LinkedHashMap<>();
		tails.put("empty body", "");
		tails.put("keep-alive comment only", ": synthetic keepalive\n\n");
		tails.put("[DONE] without a terminal event", "data: [DONE]\n\n");
		tails.put("[DONE] then malformed data", "data: [DONE]\ndata: {invalid}\n\n");
		return tails.entrySet()
			.stream()
			.flatMap(tail -> Stream.of(
					Arguments.of(Named.of(tail.getKey() + ", no text before it", ""), tail.getValue(), 0),
					Arguments.of(Named.of(tail.getKey() + ", after a text delta", TEXT), tail.getValue(), 1)));
	}

	@ParameterizedTest
	@MethodSource("missingTerminalStreams")
	void missingTerminalFailsEmptyAndTextStreams(String prefix, String tail, int expectedItems) {
		Flux<ChatResponse> stream = model(prefix + tail).stream(new Prompt("synthetic"));

		assertTruncatedBeforeProtocolTermination(stream, expectedItems);
	}

	@ParameterizedTest
	@MethodSource("missingTerminalStreams")
	void missingTerminalFailsAgainOnASecondSubscription(String prefix, String tail, int expectedItems) {
		Flux<ChatResponse> stream = model(prefix + tail).stream(new Prompt("synthetic"));
		stream.onErrorResume(OpenRouterTruncatedResponseException.class, error -> Flux.empty()).blockLast();

		assertTruncatedBeforeProtocolTermination(stream, expectedItems);
	}

	private static void assertTruncatedBeforeProtocolTermination(Flux<ChatResponse> stream, int expectedItems) {
		StepVerifier.create(stream)
			.expectNextCount(expectedItems)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessageContaining("before protocol termination"))
			.verify();
	}

	@ParameterizedTest
	@CsvSource({ "completed,STOP", "incomplete,incomplete" })
	void terminalPreservesUsageAndFinishMetadataWithoutDone(String status, String expectedFinishReason) {
		String terminal = "data: {\"type\":\"response." + status + "\",\"response\":{"
				+ "\"id\":\"synthetic\",\"status\":\"" + status
				+ "\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\"}]}],"
				+ "\"usage\":{\"input_tokens\":2,\"output_tokens\":3,\"total_tokens\":5}}}\n\n";
		StepVerifier.create(model(TEXT + terminal + "data: {invalid}\n\n").stream(new Prompt("synthetic")))
			.assertNext(response -> {
				assertThat(response.getResult().getOutput().getText()).isEqualTo("hello");
			})
			.assertNext(response -> {
				assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(expectedFinishReason);
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
	void doneWithoutTerminalEventFailsAndClosesAnOpenBody() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<String> body = Flux.just(TEXT + DONE).concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true));

		StepVerifier.create(model(body).stream(new Prompt("synthetic")))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessageContaining("before protocol termination"))
			.verify(Duration.ofSeconds(3));

		assertThat(cancelled).isTrue();
	}

	@Test
	void consumerCancellationClosesAnOpenBody() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<String> body = Flux.just(TEXT).concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true));

		StepVerifier.create(model(body).stream(new Prompt("synthetic")))
			.expectNextCount(1)
			.thenCancel()
			.verify(Duration.ofSeconds(3));

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
