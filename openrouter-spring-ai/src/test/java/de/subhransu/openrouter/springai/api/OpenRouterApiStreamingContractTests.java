package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Wire-contract tests for the streaming {@link OpenRouterApi} surface. They pin the
 * endpoint each stream method targets and assert mid-stream provider failures surface as
 * {@link OpenRouterHttpException} with status and body preserved. Model-level dispatch
 * (which mode picks which method, and {@code stream=true} on the request) is covered in
 * {@code OpenRouterChatModelStreamingTests}.
 */
class OpenRouterApiStreamingContractTests {

	/**
	 * Captures the outgoing {@link ClientRequest} and replays a canned SSE body, so tests
	 * can assert which URI the stream method targeted.
	 */
	private Capture capturingApi(HttpStatus status, String contentType, String body) {
		AtomicReference<ClientRequest> captured = new AtomicReference<>();
		ExchangeFunction exchange = request -> {
			captured.set(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", contentType).body(body).build());
		};
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		return new Capture(api, captured);
	}

	private static final String DONE_ONLY_SSE = "data: [DONE]\n\n";

	private ChatCompletionRequest chatRequest() {
		return new ChatCompletionRequest("openai/gpt-5.4-mini", null,
				List.of(new ChatMessage("user", "hello", null, null, null)), null, null, null, null, null, null, null,
				null, null, null, null, null, null, true, null, null, null, null, null, null, null, null, null, null,
				null, null);
	}

	private ResponsesRequest responsesRequest() {
		return new ResponsesRequest("openai/gpt-5.4", null, "hello", null, 128, true, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, null, null);
	}

	private ImagesRequest imagesRequest() {
		return new ImagesRequest("openai/gpt-image-1", "draw a circle", null, null, null, null, null, null, null, null,
				null, null, null, null);
	}

	@Test
	void chatCompletionStreamTargetsChatCompletionsEndpoint() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().chatCompletionStream(chatRequest()).blockLast(Duration.ofSeconds(5));

		assertThat(capture.request().get().url().getPath()).endsWith("/chat/completions");
		assertThat(capture.request().get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
	}

	@Test
	void responsesStreamTargetsResponsesEndpoint() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().responsesStream(responsesRequest()).blockLast(Duration.ofSeconds(5));

		assertThat(capture.request().get().url().getPath()).endsWith("/responses");
		assertThat(capture.request().get().headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
	}

	@Test
	void chatCompletionStreamSendsAuthorizationAndStreamTrueOnTheWire() {
		// The streaming WebClient is a separate code path from the blocking RestClient
		// interceptor: a regression stripping the Bearer token (or the stream flag) from
		// this path would be invisible to the blocking contract tests.
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().chatCompletionStream(chatRequest()).blockLast(Duration.ofSeconds(5));

		ClientRequest request = capture.request().get();
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(serializedBody(request)).contains("\"stream\":true");
	}

	@Test
	void responsesStreamSendsAuthorizationAndStreamTrueOnTheWire() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().responsesStream(responsesRequest()).blockLast(Duration.ofSeconds(5));

		ClientRequest request = capture.request().get();
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(serializedBody(request)).contains("\"stream\":true");
	}

	@Test
	void streamingRequestsCarryConfiguredAttributionHeaders() {
		// Attribution on the streaming path goes through WebClient defaultHeaders, a
		// different mechanism from the blocking RestClient interceptor covered in
		// OpenRouterApiContractTests -- it needs its own wire assertion.
		AtomicReference<ClientRequest> captured = new AtomicReference<>();
		ExchangeFunction exchange = request -> {
			captured.set(request);
			return Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
				.body(DONE_ONLY_SSE)
				.build());
		};
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.httpReferer("https://app.example")
			.applicationTitle("My App")
			.applicationCategories("translation,programming")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();

		api.chatCompletionStream(chatRequest()).blockLast(Duration.ofSeconds(5));

		HttpHeaders headers = captured.get().headers();
		assertThat(headers.getFirst("HTTP-Referer")).isEqualTo("https://app.example");
		assertThat(headers.getFirst("X-OpenRouter-Title")).isEqualTo("My App");
		assertThat(headers.getFirst("X-OpenRouter-Categories")).isEqualTo("translation,programming");
	}

	@Test
	void streamingRequestsOmitAttributionHeadersWhenNotConfigured() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().chatCompletionStream(chatRequest()).blockLast(Duration.ofSeconds(5));

		HttpHeaders headers = capture.request().get().headers();
		assertThat(headers.headerNames()).doesNotContain("HTTP-Referer", "X-OpenRouter-Title",
				"X-OpenRouter-Categories");
	}

	private String serializedBody(ClientRequest request) {
		MockClientHttpRequest mockRequest = new MockClientHttpRequest(HttpMethod.POST, request.url());
		request.writeTo(mockRequest, ExchangeStrategies.withDefaults()).block(Duration.ofSeconds(5));
		return mockRequest.getBodyAsString().block(Duration.ofSeconds(5));
	}

	@Test
	void passesMidStreamErrorChunkThroughWithoutFailingTheFlux() {
		// A normal token event, then a top-level error chunk over the same HTTP 200
		// stream. The API layer does NOT fail the flux: it emits the parsed error chunk
		// and completes normally, leaving it to the mapper layer to raise the error.
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","error":{"code":"server_error","message":"provider dropped"}}

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hel"))
			// The error chunk parses into a ChatCompletionChunk with a populated error
			// object; the mapper (not the API parser) raises it. The API layer hands the
			// raw chunk through, so callers can inspect the error field.
			.assertNext(chunk -> {
				assertThat(chunk.error()).isNotNull();
				String message = chunk.error().message();
				assertThat(message).isEqualTo("provider dropped");
			})
			.verifyComplete();
	}

	@Test
	void streamFailsWithApiExceptionPreservingStatusAndBodyOnErrorStatus() {
		Capture capture = capturingApi(HttpStatus.PAYMENT_REQUIRED, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"insufficient credits\"}}");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest())).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(OpenRouterNonTransientApiException.class);
			OpenRouterHttpException ex = (OpenRouterHttpException) error;
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
			assertThat(ex.getResponseBody()).contains("insufficient credits");
			assertThat(ex.getCategory()).isEqualTo(OpenRouterErrorCategory.BILLING_CREDITS);
		}).verify();
	}

	@Test
	void responsesStreamFailsWithApiExceptionOnErrorStatus() {
		Capture capture = capturingApi(HttpStatus.SERVICE_UNAVAILABLE, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"overloaded\"}}");

		StepVerifier.create(capture.api().responsesStream(responsesRequest())).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(OpenRouterTransientApiException.class);
			OpenRouterHttpException ex = (OpenRouterHttpException) error;
			assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.getResponseBody()).contains("overloaded");
		}).verify();
	}

	@Test
	void imageStreamUsesSameTransientClassificationOnErrorStatus() {
		Capture capture = capturingApi(HttpStatus.BAD_GATEWAY, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"provider unavailable\"}}");

		StepVerifier.create(capture.api().imagesStream(imagesRequest())).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(OpenRouterTransientApiException.class);
			OpenRouterHttpException exception = (OpenRouterHttpException) error;
			assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
			assertThat(exception.getEndpoint()).isEqualTo("/images");
		}).verify();
	}

	@Test
	void emitsFinalUsageChunkWhenUsageRequested() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.assertNext(chunk -> {
				assertThat(chunk.choices()).isEmpty();
				assertThat(chunk.usage().totalTokens()).isEqualTo(7);
			})
			.verifyComplete();
	}

	@Test
	void parsesSingleLineDataEventFollowedByDoneMarker() {
		// A complete chat-completion chunk delivered on one SSE "data:" line, then the
		// terminal "data: [DONE]" marker which the parser drops. Confirms a full single
		// event parses end to end.
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Multi"}}]}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Multi"))
			.verifyComplete();
	}

	@ParameterizedTest
	@CsvSource({ "chat,1", "chat,7", "chat,4096", "responses,1", "responses,7", "responses,4096", "images,1",
			"images,7", "images,4096" })
	void parsesMultilineDataAcrossBufferBoundaries(String endpoint, int bufferSize) {
		String text = "caf\u00e9 \uD83D\uDE00 \u2028 data: [DONE]";
		String json = switch (endpoint) {
			case "chat" -> "{\n\"choices\": [\n{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}\n]\n}";
			case "responses" -> "{\n\"type\":\"response.output_text.delta\",\n\"delta\":\"" + text + "\"\n}";
			case "images" -> "{\n\"type\":\"image_generation.partial_image\",\n\"b64_json\":\"" + text + "\"\n}";
			default -> throw new IllegalArgumentException(endpoint);
		};
		for (String newline : List.of("\n", "\r\n")) {
			String sse = ": keepalive\n\ndata: " + json.replace("\n", "\n: comment\ndata: ")
					+ "\n\ndata: [DONE]\n\ndata: {invalid}\n\n";
			byte[] bytes = sse.replace("\n", newline).getBytes(StandardCharsets.UTF_8);
			Flux<DataBuffer> body = Flux.range(0, (bytes.length + bufferSize - 1) / bufferSize)
				.map(i -> new DefaultDataBufferFactory().wrap(java.util.Arrays.copyOfRange(bytes, i * bufferSize,
						Math.min(bytes.length, (i + 1) * bufferSize))));
			OpenRouterApi api = OpenRouterApi.builder()
				.apiKey("test-key")
				.webClientBuilder(WebClient.builder()
					.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
						.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
						.body(body)
						.build())))
				.build();
			Flux<String> values = switch (endpoint) {
				case "chat" ->
					api.chatCompletionStream(chatRequest()).map(chunk -> chunk.choices().get(0).delta().content());
				case "responses" -> api.responsesStream(responsesRequest()).map(event -> event.delta());
				case "images" -> api.imagesStream(imagesRequest()).map(event -> event.b64Json());
				default -> throw new IllegalArgumentException(endpoint);
			};
			StepVerifier.create(values).expectNext(text).expectComplete().verify(Duration.ofSeconds(5));
		}
	}

	@Test
	void splitsCoalescedJsonDocumentsInOneSsePayloadIntoSeparateChunks() {
		// Some proxies and providers coalesce several complete JSON events into a
		// single SSE data payload (multiple "data:" lines in one event). The parser's
		// newline re-split turns each line back into its own chunk instead of failing
		// on the concatenated payload. This pins the deliberate defensive behavior in
		// OpenRouterApi#decodeStream.
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"first"}}]}
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"second"}}]}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("first"))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("second"))
			.verifyComplete();
	}

	@Test
	void ignoresKeepAliveCommentLines() {
		String sse = """
				: OPENROUTER PROCESSING

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "data: {not valid json}\n\n", "data: {\ndata: \"choices\": [\ndata: }\n\n" })
	void malformedJsonStreamLineFailsWithIllegalStateNotApiException(String sse) {
		// A line that looks like a data line but is invalid JSON is a parsing failure,
		// not
		// a provider error -- callers must be able to tell them apart.
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
				.isNotInstanceOf(OpenRouterApiException.class))
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "response.completed", "response.incomplete", "response.failed" })
	void malformedResponsesTerminalPayloadFailsDecoding(String type) {
		String sse = "data: {\"type\":\"" + type
				+ "\",\"response\":{\"status\":\"incomplete\",\"usage\":\"malformed\"}}\n\n";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().responsesStream(responsesRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalStateException.class)
				.hasMessage("Failed to decode OpenRouter stream chunk"))
			.verify();
	}

	@Test
	void responsesStreamParsesEventsAsTypedStreamEvents() {
		String sse = """
				data: {"type":"response.output_text.delta","delta":"hello"}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().responsesStream(responsesRequest())).assertNext(event -> {
			assertThat(event.type()).isEqualTo("response.output_text.delta");
			assertThat(event.delta()).isEqualTo("hello");
		}).verifyComplete();
	}

	@ParameterizedTest
	@CsvSource({ "chat,false", "chat,true", "responses,false", "responses,true", "images,false", "images,true" })
	void doneCompletesAndCancelsOpenBody(String endpoint, boolean timeout) {
		assertTerminalBody(endpoint, timeout, "data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\ndata: [DONE]\n\n",
				1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "chat", "responses", "images" })
	void coalescedDoneStopsBeforeTrailingMalformedData(String endpoint) {
		assertTerminalBody(endpoint, false,
				"data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\ndata: data: [DONE]\ndata: {invalid}\n\n", 1);
	}

	@ParameterizedTest
	@CsvSource({ "responses,response.completed", "responses,response.failed", "responses,response.incomplete",
			"responses,error", "responses,response.failed.error", "responses,response.output_text.error",
			"images,image_generation.completed", "images,error" })
	void typedTerminalEventIsEmittedBeforeCancellation(String endpoint, String type) {
		assertTerminalBody(endpoint, false, "data: {\"type\":\"" + type + "\"}\n\ndata: {invalid}\n\n", 1);
	}

	private void assertTerminalBody(String endpoint, boolean timeout, String sse, long count) {
		AtomicBoolean cancelled = new AtomicBoolean();
		// Model an HTTP body that stays open after the protocol has finished.
		Flux<DataBuffer> body = Flux
			.<DataBuffer>just(new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		// A real response body is single-use; releaseBody must not replay the fixture.
		AtomicBoolean subscribed = new AtomicBoolean();
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(Flux.defer(() -> subscribed.compareAndSet(false, true) ? body : Flux.empty()))
			.build());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.timeout(timeout ? Duration.ofSeconds(1) : null)
			.build();
		Flux<?> stream = switch (endpoint) {
			case "chat" -> api.chatCompletionStream(chatRequest());
			case "responses" -> api.responsesStream(responsesRequest());
			case "images" -> api.imagesStream(imagesRequest());
			default -> throw new IllegalArgumentException(endpoint);
		};
		StepVerifier.create(stream).expectNextCount(count).expectComplete().verify(Duration.ofSeconds(3));
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "responses", "images" })
	void prematureEofFailsWithoutReplayAndTerminalStateIsPerSubscription(String endpoint) {
		String partial = endpoint.equals("responses")
				? "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n"
				: "data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"aW1hZ2U=\"}\n\n";
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<String> body = new AtomicReference<>(partial + DONE_ONLY_SSE);
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(request -> {
				requests.incrementAndGet();
				return Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body.get())
					.build());
			}))
			.build();
		Flux<?> stream = endpoint.equals("responses") ? api.responsesStream(responsesRequest())
				: api.imagesStream(imagesRequest());
		StepVerifier.create(stream).expectNextCount(1).verifyComplete();
		body.set(partial);
		StepVerifier.create(stream).expectNextCount(1).expectError(OpenRouterTruncatedResponseException.class).verify();
		// Cancellation after a preview is intentional, even without protocol termination.
		StepVerifier.create(stream.take(1)).expectNextCount(1).verifyComplete();
		body.set(": keepalive\n\n");
		StepVerifier.create(stream).expectError(OpenRouterTruncatedResponseException.class).verify();
		body.set("");
		StepVerifier.create(stream).expectError(OpenRouterTruncatedResponseException.class).verify();
		body.set(partial + DONE_ONLY_SSE);
		StepVerifier.create(stream).expectNextCount(1).verifyComplete();
		assertThat(requests).hasValue(6);
	}

	private record Capture(OpenRouterApi api, AtomicReference<ClientRequest> request) {
	}

}
