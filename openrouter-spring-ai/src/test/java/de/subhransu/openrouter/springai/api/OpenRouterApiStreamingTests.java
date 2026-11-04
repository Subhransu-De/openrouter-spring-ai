package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class OpenRouterApiStreamingTests {

	@Test
	void suppliedModuleIsNotSetUpAgainForStreamBinding() {
		AtomicInteger setups = new AtomicInteger();
		SimpleModule module = new SimpleModule("counted") {
			@Override
			public void setupModule(SetupContext context) {
				int generation = setups.incrementAndGet();
				new SimpleModule().addDeserializer(FunctionCall.class, new ValueDeserializer<FunctionCall>() {
					@Override
					public FunctionCall deserialize(JsonParser parser, DeserializationContext context) {
						parser.skipChildren();
						return new FunctionCall("generation-" + generation, "{}");
					}
				}).setupModule(context);
			}
		};
		ObjectMapper mapper = JsonMapper.builder().addModule(module).build();
		String body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{}}]}}]}\n\ndata: [DONE]\n\n";
		OpenRouterApi api = mappedApi(mapper, body);
		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().toolCalls().get(0).function().name())
				.isEqualTo("generation-1"))
			.verifyComplete();
		assertThat(setups).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "chat", "responses", "images" })
	void suppliedStringDeserializerReadsNormalizedTreeTokens(String endpoint) {
		SimpleModule module = new SimpleModule("numeric-strings").addDeserializer(String.class,
				new ValueDeserializer<String>() {
					@Override
					public String deserialize(JsonParser parser, DeserializationContext context) {
						return parser.getText();
					}
				});
		ObjectMapper mapper = JsonMapper.builder().addModule(module).build();
		String payload = switch (endpoint) {
			case "chat" -> "{\"id\":1.50,\"choices\":[{\"index\":0,\"delta\":{}}]}";
			case "responses" -> "{\"type\":\"response.output_text.delta\",\"delta\":1.50}";
			case "images" -> "{\"type\":\"image_generation.partial_image\",\"b64_json\":1.50}";
			default -> throw new IllegalArgumentException(endpoint);
		};
		OpenRouterApi api = mappedApi(mapper, "data: " + payload + "\n\n");
		Flux<String> values = switch (endpoint) {
			case "chat" -> api.chatCompletionStream(chatRequest()).map(chunk -> chunk.id());
			case "responses" ->
				api.responsesStream(ResponsesRequest.builder().model("test").input("hello").stream(true).build())
					.map(event -> event.delta());
			case "images" -> api
				.imagesStream(new ImagesRequest("test", "hello", null, null, null, null, null, null, null, null, null,
						true, null, null))
				.map(event -> event.b64Json());
			default -> throw new IllegalArgumentException(endpoint);
		};
		StepVerifier.create(values.take(1)).expectNext("1.5").verifyComplete();
	}

	@Test
	void suppliedMixInDeserializerKeepsTreeParserSemantics() {
		ObjectMapper mapper = JsonMapper.builder().addMixIn(FunctionCall.class, FunctionMixin.class).build();
		String body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{}}]}}]}\n\ndata: [DONE]\n\n";
		OpenRouterApi api = mappedApi(mapper, body);
		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().toolCalls().get(0).function().name())
				.isEqualTo("TreeTraversingParser"))
			.verifyComplete();
	}

	@Test
	void binderKeepsSuppliedDeserializerOnItsTreePath() {
		ObjectMapper mapper = JsonMapper.builder().addMixIn(FunctionCall.class, FunctionMixin.class).build();
		StreamEventBinder binder = new StreamEventBinder(mapper, false);
		FunctionCall function = binder.bind("{}", binder.reader(FunctionCall.class), FunctionCall.class);
		assertThat(function.name()).isEqualTo("TreeTraversingParser");
	}

	private OpenRouterApi mappedApi(ObjectMapper mapper, String body) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.objectMapper(mapper)
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body)
					.build())))
			.build();
	}

	private OpenRouterApi apiRespondingWith(HttpStatus status, String contentType, String body) {
		ExchangeFunction exchange = request -> Mono
			.just(ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, contentType).body(body).build());
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
	}

	private ChatCompletionRequest chatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(true)
			.build();
	}

	@Test
	void eofBeforeDoneIsTypedTruncation() {
		for (String body : List.of("", "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")) {
			OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, body);
			StepVerifier.create(api.chatCompletionStream(chatRequest()))
				.thenConsumeWhile(chunk -> true)
				.expectError(OpenRouterTruncatedResponseException.class)
				.verify();
		}
	}

	@Test
	void doneTerminatesWithoutWaitingForConnectionClose() {
		DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(Flux.<DataBuffer>just(buffers.wrap("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)))
				.concatWith(Flux.never()))
			.build());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		StepVerifier.create(api.chatCompletionStream(chatRequest())).expectComplete().verify(Duration.ofSeconds(5));
	}

	@Test
	void parsesChunksAndFiltersDoneMarker() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

				data: [DONE]

				""";
		OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hel"))
			.assertNext(chunk -> {
				assertThat(chunk.choices().get(0).delta().content()).isEqualTo("lo");
				assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
			})
			.verifyComplete();
	}

	@Test
	void toleratesUnknownFieldsInStreamChunks() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"content":"Hi","reasoning_details":[{"type":"reasoning.text","text":"thinking","format":"unknown","index":0}]},"brand_new_field":true}]}

				data: [DONE]

				""";
		OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.verifyComplete();
	}

	@Test
	void failsStalledStreamWhenTimeoutIsConfigured() {
		// The body emits one chunk, then stalls forever. With a short timeout the gap
		// between
		// elements exceeds it, so the stream errors instead of hanging.
		DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
		Flux<DataBuffer> stalledBody = Flux.<DataBuffer>just(bufferFactory
			.wrap("""
					data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

					"""
				.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never());
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(stalledBody)
			.build());
		// A second-scale timeout leaves a wide margin for the immediate first chunk to
		// arrive under
		// CI load, while the infinite stall still trips it. The verifier returns as soon
		// as it fires.
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.timeout(Duration.ofSeconds(1))
			.build();

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.expectError(TimeoutException.class)
			.verify(Duration.ofSeconds(30));
	}

	@Test
	void keepAliveCommentsResetTheStreamTimeout() {
		// OpenRouter emits ": OPENROUTER PROCESSING" SSE comments as keep-alives during
		// slow generations. The parser filters them out, but the timeout sits on the SSE
		// event stream, so comments spaced under the timeout must NOT trip it before the
		// real chunk arrives. Virtual time makes the 100ms-interval-vs-250ms-timeout
		// relationship deterministic instead of racing real CI wall-clock.
		StepVerifier.withVirtualTime(() -> {
			DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
			Flux<DataBuffer> body = Flux.interval(Duration.ofMillis(100))
				.take(15)
				.map(tick -> bufferFactory.wrap(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8)))
				.concatWith(Flux.just(bufferFactory
					.wrap("""
							data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

							data: [DONE]

							"""
						.getBytes(StandardCharsets.UTF_8))))
				.cast(DataBuffer.class);
			ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
				.body(body)
				.build());
			OpenRouterApi api = OpenRouterApi.builder()
				.apiKey("test-key")
				.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
				.timeout(Duration.ofMillis(250))
				.build();
			return api.chatCompletionStream(chatRequest());
		})
			.thenAwait(Duration.ofSeconds(2))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.verifyComplete();
	}

	@Test
	void emitsApiExceptionOnErrorStatus() {
		OpenRouterApi api = apiRespondingWith(HttpStatus.UNAUTHORIZED, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"bad key\"}}");

		StepVerifier.create(api.chatCompletionStream(chatRequest())).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(OpenRouterNonTransientApiException.class);
			OpenRouterHttpException apiException = (OpenRouterHttpException) error;
			assertThat(apiException.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(apiException.getResponseBody()).contains("bad key");
		}).verify();
	}

	@JsonDeserialize(using = ParserFunctionDeserializer.class)
	private abstract static class FunctionMixin {

	}

	public static final class ParserFunctionDeserializer extends ValueDeserializer<FunctionCall> {

		@Override
		public FunctionCall deserialize(JsonParser parser, DeserializationContext context) {
			String parserType = parser.getClass().getSimpleName();
			parser.skipChildren();
			return new FunctionCall(parserType, "{}");
		}

	}

}
