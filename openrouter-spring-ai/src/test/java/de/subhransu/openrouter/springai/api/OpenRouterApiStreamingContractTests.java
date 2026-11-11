package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import org.springframework.ai.chat.prompt.Prompt;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

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
		return capturingApi(status, contentType, body, new ObjectMapper());
	}

	private Capture capturingApi(HttpStatus status, String contentType, String body, ObjectMapper objectMapper) {
		AtomicReference<ClientRequest> captured = new AtomicReference<>();
		ExchangeFunction exchange = request -> {
			captured.set(request);
			return Mono.just(ClientResponse.create(status).header("Content-Type", contentType).body(body).build());
		};
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.objectMapper(objectMapper)
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		return new Capture(api, captured);
	}

	private static final String DONE_ONLY_SSE = "data: [DONE]\n\n";

	private static final String RESPONSES_COMPLETED_SSE = "data: {\"type\":\"response.completed\"}\n\n";

	private ChatCompletionRequest chatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(true)
			.build();
	}

	private ResponsesRequest responsesRequest() {
		return ResponsesRequest.builder()
			.model("openai/gpt-5.4")
			.input("hello")
			.maxOutputTokens(128)
			.stream(true)
			.build();
	}

	private ImagesRequest imagesRequest() {
		return new ImagesRequest("openai/gpt-image-1", "draw a circle", null, null, null, null, null, null, null, null,
				null, null, null, null);
	}

	@Test
	void chatCompletionStreamTargetsItsEndpointWithAuthorizationAndStreamTrueOnTheWire() {
		// The streaming WebClient is a separate code path from the blocking RestClient
		// interceptor: a regression stripping the Bearer token (or the stream flag) from
		// this path would be invisible to the blocking contract tests.
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, DONE_ONLY_SSE);

		capture.api().chatCompletionStream(chatRequest()).blockLast(Duration.ofSeconds(5));

		ClientRequest request = capture.request().get();
		assertThat(request.url().getPath()).endsWith("/chat/completions");
		assertThat(request.headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(serializedBody(request)).contains("\"stream\":true");
	}

	@Test
	void responsesStreamTargetsItsEndpointWithAuthorizationAndStreamTrueOnTheWire() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, RESPONSES_COMPLETED_SSE);

		capture.api().responsesStream(responsesRequest()).blockLast(Duration.ofSeconds(5));

		ClientRequest request = capture.request().get();
		assertThat(request.url().getPath()).endsWith("/responses");
		assertThat(request.headers().getAccept()).contains(MediaType.TEXT_EVENT_STREAM);
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

	static Stream<Arguments> errorStatusCases() {
		return Stream.of(Arguments.of(Named.of("chat 401", "chat"), HttpStatus.UNAUTHORIZED, "bad key",
				OpenRouterNonTransientApiException.class, OpenRouterErrorCategory.AUTHENTICATION, "/chat/completions"),
				Arguments.of(Named.of("chat 402", "chat"), HttpStatus.PAYMENT_REQUIRED, "insufficient credits",
						OpenRouterNonTransientApiException.class, OpenRouterErrorCategory.BILLING_CREDITS,
						"/chat/completions"),
				Arguments.of(Named.of("responses 503", "responses"), HttpStatus.SERVICE_UNAVAILABLE, "overloaded",
						OpenRouterTransientApiException.class, OpenRouterErrorCategory.PROVIDER_UNAVAILABLE,
						"/responses"),
				Arguments.of(Named.of("images 502", "images"), HttpStatus.BAD_GATEWAY, "provider unavailable",
						OpenRouterTransientApiException.class, OpenRouterErrorCategory.PROVIDER_UNAVAILABLE,
						"/images"));
	}

	@ParameterizedTest
	@MethodSource("errorStatusCases")
	void streamFailsWithApiExceptionPreservingStatusBodyCategoryAndEndpointOnErrorStatus(String endpoint,
			HttpStatus status, String message, Class<? extends OpenRouterHttpException> expectedType,
			OpenRouterErrorCategory expectedCategory, String expectedEndpoint) {
		Capture capture = capturingApi(status, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"" + message + "\"}}");
		Flux<?> stream = switch (endpoint) {
			case "chat" -> capture.api().chatCompletionStream(chatRequest());
			case "responses" -> capture.api().responsesStream(responsesRequest());
			case "images" -> capture.api().imagesStream(imagesRequest());
			default -> throw new IllegalArgumentException(endpoint);
		};

		StepVerifier.create(stream).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(expectedType);
			OpenRouterHttpException ex = (OpenRouterHttpException) error;
			assertThat(ex.getStatusCode()).isEqualTo(status);
			assertThat(ex.getResponseBody()).contains(message);
			assertThat(ex.getCategory()).isEqualTo(expectedCategory);
			assertThat(ex.getEndpoint()).isEqualTo(expectedEndpoint);
		}).verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"choices\":null}", "{\"choices\":[]}",
			"{\"choices\":[null],\"usage\":{\"total_tokens\":1}}" })
	void rejectsMissingChoicesAndNullChoiceEntries(String json) {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: " + json + "\n\ndata: [DONE]\n\n");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("OpenRouter chat chunk requires non-null choices or usage"))
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "[", "[bad]", "42 trailing", "true false", "\"unterminated", "{} {}", "{not valid json}",
			"{\ndata: \"choices\": [\ndata: }" })
	void malformedChunksFailAsProtocolErrorsThatKeepTheJacksonCause(String json) {
		// Invalid JSON on a data line (or spread over several data lines) is a protocol
		// failure: an OpenRouterProtocolException with a fixed message that never echoes
		// the payload, carrying the decoder's cause.
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: " + json + "\n\ndata: [DONE]\n\n");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Failed to decode OpenRouter stream chunk")
				.hasCauseInstanceOf(JacksonException.class))
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"probe\":1,\"choices\":\"bad\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}],\"probe\":\"OK\"}",
			"{\"probe\":1,\"probe\":\"OK\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}" })
	void duplicateKeysKeepTheLastValue(String json) {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: " + json + "\n\ndata: [DONE]\n\n");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest())).assertNext(chunk -> {
			assertThat(chunk.choices().get(0).delta().content()).isEqualTo("OK");
			assertThat(chunk.extensions()).containsEntry("probe", "OK");
		}).verifyComplete();
	}

	/**
	 * A number in a string field reaches the caller as its tree-binding text:
	 * {@code 1.50} normalizes to {@code 1.5}. Only rows a consumer could depend on are
	 * pinned; edge spellings such as {@code -0} or {@code 1e400} are deliberately left
	 * unspecified.
	 */
	@ParameterizedTest
	@CsvSource({ "1.50,1.5", "502,502" })
	void numbersInStringFieldsKeepTheTreeBindingText(String literal, String expected) {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, "data: {\"id\":" + literal
				+ ",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.id()).isEqualTo(expected))
			.verifyComplete();
	}

	@Test
	void bigDecimalMappersKeepTheTreeRangeCheck() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: {\"choices\":[],\"usage\":{\"cost\":1e400}}\n\ndata: [DONE]\n\n",
				JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build());

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Failed to decode OpenRouter stream chunk")
				.cause()
				.hasMessageContaining("cannot convert value 1E+400 to `double`")
				.hasMessageContaining("value not in 64-bit `double` range"))
			.verify();
	}

	@Test
	void treeNullPropertySettingAppliesToExtensions() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: {\"probe\":null,\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}\n\ndata: [DONE]\n\n",
				JsonMapper.builder().disable(JsonNodeFeature.READ_NULL_PROPERTIES).build());

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.extensions()).doesNotContainKey("probe"))
			.verifyComplete();
	}

	@Test
	void providerErrorTakesPrecedenceOverNullChoiceValidation() {
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE,
				"data: {\"choices\":[null],\"error\":{\"message\":\"synthetic error\"}}\n\n");

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.error().message()).isEqualTo("synthetic error"))
			.verifyComplete();
	}

	@Test
	void trailingUsageChunkWithEmptyChoicesFollowsTheFinishChunk() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest())).assertNext(chunk -> {
			assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi");
			assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
			assertThat(chunk.usage()).isNull();
		}).assertNext(chunk -> {
			assertThat(chunk.choices()).isEmpty();
			assertThat(chunk.usage().promptTokens()).isEqualTo(5);
			assertThat(chunk.usage().completionTokens()).isEqualTo(2);
			assertThat(chunk.usage().totalTokens()).isEqualTo(7);
		}).verifyComplete();
	}

	@ParameterizedTest(name = "{0} endpoint, {1}-byte buffers, {2} line endings")
	@CsvSource({ "chat,1,LF", "chat,7,LF", "chat,4096,LF", "responses,1,LF", "responses,7,LF", "responses,4096,LF",
			"images,1,LF", "images,7,LF", "images,4096,LF", "chat,1,CRLF", "chat,7,CRLF", "chat,4096,CRLF",
			"responses,1,CRLF", "responses,7,CRLF", "responses,4096,CRLF", "images,1,CRLF", "images,7,CRLF",
			"images,4096,CRLF" })
	void parsesMultilineDataAcrossBufferBoundaries(String endpoint, int bufferSize, String lineEnding) {
		String newline = lineEnding.equals("LF") ? "\n" : "\r\n";
		String text = "caf\u00e9 \uD83D\uDE00 \u2028 data: [DONE]";
		String json = switch (endpoint) {
			case "chat" -> "{\n\"choices\": [\n{\"index\":0,\"delta\":{\"content\":\"" + text + "\"}}\n]\n}";
			case "responses" -> "{\n\"type\":\"response.output_text.delta\",\n\"delta\":\"" + text + "\"\n}";
			case "images" -> "{\n\"type\":\"image_generation.partial_image\",\n\"b64_json\":\"" + text + "\"\n}";
			default -> throw new IllegalArgumentException(endpoint);
		};
		String sse = ": keepalive\n\ndata:\n\ndata:   \n\ndata: " + json.replace("\n", "\n: comment\ndata: ") + "\n\n"
				+ (endpoint.equals("responses") ? RESPONSES_COMPLETED_SSE : "") + "data: [DONE]\n\ndata: {invalid}\n\n";
		byte[] bytes = sse.replace("\n", newline).getBytes(StandardCharsets.UTF_8);
		Flux<DataBuffer> body = Flux.range(0, (bytes.length + bufferSize - 1) / bufferSize)
			.map(i -> new DefaultDataBufferFactory().wrap(
					java.util.Arrays.copyOfRange(bytes, i * bufferSize, Math.min(bytes.length, (i + 1) * bufferSize))));
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
			case "responses" -> api.responsesStream(responsesRequest())
				.filter(event -> event.delta() != null)
				.map(event -> event.delta());
			case "images" -> api.imagesStream(imagesRequest()).map(event -> event.b64Json());
			default -> throw new IllegalArgumentException(endpoint);
		};
		StepVerifier.create(values).expectNext(text).expectComplete().verify(Duration.ofSeconds(5));
	}

	@Test
	void splitsCoalescedJsonDocumentsInOneSsePayloadAndFiltersTheDoneMarker() {
		// Some proxies and providers coalesce several complete JSON events into a
		// single SSE data payload (multiple "data:" lines in one event). The parser's
		// newline re-split turns each line back into its own chunk instead of failing
		// on the concatenated payload. This pins the deliberate defensive behavior in
		// OpenRouterApi#decodeStream.
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"first"}}]}
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"second"}}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"third"},"finish_reason":"stop"}]}

				data: [DONE]

				""";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("first"))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("second"))
			.assertNext(chunk -> {
				assertThat(chunk.choices().get(0).delta().content()).isEqualTo("third");
				assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
			})
			.verifyComplete();
	}

	static Stream<Arguments> invalidPayloadPositions() {
		List<String> invalidPayloads = List.of("synthetic-invalid", "{invalid}", "[]", "[{}]", "null", "42", "true",
				"\"text\"", "{} trailing");
		return invalidPayloads.stream()
			.flatMap(invalid -> Stream.of("chat", "responses", "images", "chat-model", "responses-model")
				.flatMap(endpoint -> IntStream.range(0, 3)
					.boxed()
					.flatMap(position -> Stream.of("\n\n", "\n")
						.map(separator -> Arguments.of(Named.of(invalid, invalid), Named.of(endpoint, endpoint),
								Named.of(position + " valid events first", position),
								Named.of(separator.length() == 2 ? "blank-line separator" : "newline separator",
										separator))))));
	}

	@ParameterizedTest(name = "{0} at the {1} endpoint, {2}, {3}")
	@MethodSource("invalidPayloadPositions")
	void invalidPayloadFailsAtCorruptionAndCancelsUpstream(String invalid, String endpoint, int position,
			String separator) {
		String valid = endpoint.startsWith("chat") ? "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"OK\"}}]}"
				: endpoint.startsWith("responses") ? "{\"type\":\"response.output_text.delta\",\"delta\":\"OK\"}"
						: "{\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2U=\"}";
		String terminal = endpoint.startsWith("responses") ? RESPONSES_COMPLETED_SSE : DONE_ONLY_SSE;
		String sse = ("data: " + valid + separator).repeat(position) + "data: " + invalid + separator
				+ ("data: " + valid + separator).repeat(2 - position) + terminal;
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicBoolean subscribed = new AtomicBoolean();
		Flux<DataBuffer> body = Flux
			.<DataBuffer>just(new DefaultDataBufferFactory().wrap(sse.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(Flux.defer(() -> subscribed.compareAndSet(false, true) ? body : Flux.empty()))
					.build())))
			.build();
		Flux<?> stream = switch (endpoint) {
			case "chat" -> api.chatCompletionStream(chatRequest());
			case "responses" -> api.responsesStream(responsesRequest());
			case "images" -> api.imagesStream(imagesRequest());
			default ->
				OpenRouterChatModel.builder()
					.openRouterApi(api)
					.build()
					.stream(new Prompt("hello", OpenRouterChatOptions.builder()
						.model("synthetic-model")
						.requestMode(endpoint.equals("chat-model") ? OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
								: OpenRouterRequestMode.OPENAI_RESPONSES)
						.build()));
		};
		// A coalesced payload starting with invalid JSON fails as a whole.
		StepVerifier.create(stream)
			.expectNextCount(position)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class))
			.verify(Duration.ofSeconds(3));
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "response.completed", "response.incomplete", "response.failed" })
	void malformedResponsesTerminalPayloadFailsDecoding(String type) {
		String sse = "data: {\"type\":\"" + type
				+ "\",\"response\":{\"status\":\"incomplete\",\"usage\":\"malformed\"}}\n\n";
		Capture capture = capturingApi(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(capture.api().responsesStream(responsesRequest()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Failed to decode OpenRouter stream chunk"))
			.verify();
	}

	@ParameterizedTest(name = "{0} endpoint, stream timeout configured: {1}")
	@CsvSource({ "chat,false", "chat,true", "images,false", "images,true" })
	void doneCompletesAndCancelsOpenBody(String endpoint, boolean timeout) {
		assertTerminalBody(endpoint, timeout, "data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\n\ndata: [DONE]\n\n",
				1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "chat", "images" })
	void coalescedDoneStopsBeforeTrailingMalformedData(String endpoint) {
		assertTerminalBody(endpoint, false,
				"data: {\"choices\":[{\"index\":0,\"delta\":{}}]}\ndata: data: [DONE]\ndata: {invalid}\n\n", 1);
	}

	@ParameterizedTest
	@CsvSource({ "responses,response.completed", "responses,response.failed", "responses,response.incomplete",
			"responses,error", "responses,response.failed.error", "responses,response.output_text.error",
			"images,error" })
	void typedTerminalEventIsEmittedBeforeCancellation(String endpoint, String type) {
		assertTerminalBody(endpoint, false, "data: {\"type\":\"" + type + "\"}\n\ndata: {invalid}\n\n", 1);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void imageStreamPreservesMultipleImagesAndFinalUsage(boolean sse) {
		String body = sse
				? """
						data: {"type":"image_generation.completed","b64_json":"aW1hZ2Ux"}

						data: {"type":"image_generation.completed","b64_json":"aW1hZ2Uy","usage":{"total_tokens":42,"cost":0.03}}

						data: [DONE]

						"""
				: """
						{"data":[{"b64_json":"aW1hZ2Ux"},{"b64_json":"aW1hZ2Uy"}],"usage":{"total_tokens":42,"cost":0.03}}
						""";
		Capture capture = capturingApi(HttpStatus.OK,
				sse ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE, body);
		// n is an upper bound; returning fewer images must still complete at [DONE].
		ImagesRequest request = new ImagesRequest("test-image", "synthetic images", 3, null, null, null, null, null,
				null, null, null, true, null, null);
		StepVerifier.create(capture.api().imagesStream(request))
			.assertNext(event -> assertThat(event.b64Json()).isEqualTo("aW1hZ2Ux"))
			.assertNext(event -> {
				assertThat(event.b64Json()).isEqualTo("aW1hZ2Uy");
				assertThat(event.usage().totalTokens()).isEqualTo(42);
				assertThat(event.usage().cost()).isEqualTo(0.03);
			})
			.verifyComplete();
	}

	private static final String COMPLETED_IMAGE = "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2U=\"}\n\n";

	@Test
	void doneAfterACompletedImageCancelsTheOpenBodyAndIgnoresWhatFollows() {
		assertTerminalBody("images", false, COMPLETED_IMAGE + DONE_ONLY_SSE + "data: {invalid}\n\n", 1);
	}

	@Test
	void imageCompletionDoesNotHideALaterErrorEvent() {
		assertTerminalBody("images", false,
				COMPLETED_IMAGE
						+ "data: {\"type\":\"error\",\"error\":{\"message\":\"failed\",\"code\":\"server_error\"}}\n\n",
				2);
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

	static Stream<Arguments> prematureEofCases() {
		String chatChunk = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n";
		String responsesDelta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}\n\n";
		String partialImage = "data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"aW1hZ2U=\"}\n\n";
		String completedImage = "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2U=\"}\n\n";
		return Stream.of(Arguments.of(Named.of("chat chunk", "chat"), chatChunk, DONE_ONLY_SSE, 1),
				Arguments.of(Named.of("responses text delta", "responses"), responsesDelta, RESPONSES_COMPLETED_SSE, 2),
				Arguments.of(Named.of("partial image", "images"), partialImage, DONE_ONLY_SSE, 1),
				Arguments.of(Named.of("completed image", "images"), completedImage, DONE_ONLY_SSE, 1));
	}

	@ParameterizedTest
	@MethodSource("previewEvents")
	void prematureEofIsATypedTruncationWithoutReplay(String endpoint, String partial) {
		Replay replay = replay(endpoint, partial);

		StepVerifier.create(replay.stream())
			.expectNextCount(1)
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
		assertThat(replay.requests()).hasValue(1);
	}

	static Stream<Arguments> previewEvents() {
		return prematureEofCases().limit(3).map(row -> Arguments.of(row.get()[0], row.get()[1]));
	}

	@ParameterizedTest
	@MethodSource("previewEvents")
	void cancellingAfterAPreviewIsIntentionalEvenWithoutProtocolTermination(String endpoint, String partial) {
		Replay replay = replay(endpoint, partial);

		StepVerifier.create(replay.stream().take(1)).expectNextCount(1).verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "chat", "responses", "images" })
	void keepAliveOnlyAndEmptyBodiesAreTruncations(String endpoint) {
		Replay keepAlive = replay(endpoint, ": keepalive\n\n");
		Replay empty = replay(endpoint, "");

		StepVerifier.create(keepAlive.stream()).expectError(OpenRouterTruncatedResponseException.class).verify();
		StepVerifier.create(empty.stream()).expectError(OpenRouterTruncatedResponseException.class).verify();
	}

	@ParameterizedTest
	@MethodSource("prematureEofCases")
	void terminalStateIsPerSubscription(String endpoint, String partial, String terminal, int completeCount) {
		Replay replay = replay(endpoint, partial + terminal);

		StepVerifier.create(replay.stream()).expectNextCount(completeCount).verifyComplete();
		replay.body().set(partial);
		StepVerifier.create(replay.stream())
			.expectNextCount(1)
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
		replay.body().set(partial + terminal);
		StepVerifier.create(replay.stream()).expectNextCount(completeCount).verifyComplete();
		assertThat(replay.requests()).hasValue(3);
	}

	private Replay replay(String endpoint, String initialBody) {
		AtomicInteger requests = new AtomicInteger();
		AtomicReference<String> body = new AtomicReference<>(initialBody);
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
		Flux<?> stream = switch (endpoint) {
			case "chat" -> api.chatCompletionStream(chatRequest());
			case "responses" -> api.responsesStream(responsesRequest());
			case "images" -> api.imagesStream(imagesRequest());
			default -> throw new IllegalArgumentException(endpoint);
		};
		return new Replay(stream, requests, body);
	}

	@Test
	void consumerCancellationClosesAnOpenChatBodyWithoutAnError() {
		// The provider keeps the connection open after a chunk; the consumer stops early,
		// so cancellation (not [DONE] or corruption) has to release the body.
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<DataBuffer> body = Flux.<DataBuffer>just(new DefaultDataBufferFactory()
			.wrap("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n"
				.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body)
					.build())))
			.build();

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("hello"))
			.thenCancel()
			.verify(Duration.ofSeconds(3));

		assertThat(cancelled).isTrue();
	}

	private record Capture(OpenRouterApi api, AtomicReference<ClientRequest> request) {
	}

	private record Replay(Flux<?> stream, AtomicInteger requests, AtomicReference<String> body) {
	}

}
