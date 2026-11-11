package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Named;
import java.util.stream.Stream;
import java.util.function.Function;
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

/**
 * How the stream binding treats a caller-supplied {@code ObjectMapper}. Stream events are
 * bound from a normalized JSON tree, so a custom deserializer sees normalized number text
 * (wire {@code 1.50} arrives as {@code 1.5}). That is a documented property of this
 * library's binding rather than a wire contract; the tests pin it so a change in the
 * binding is deliberate.
 */
class OpenRouterApiStreamingTests {

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
	void suppliedMixInDeserializerReadsNormalizedNumbersFromTheStream() {
		// The mix-in deserializer reads the raw token text of "name". Stream chunks are
		// bound from a normalized JSON tree, so the wire literal 1.50 reaches it as 1.5.
		ObjectMapper mapper = JsonMapper.builder().addMixIn(FunctionCall.class, FunctionMixin.class).build();
		String body = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":1.50}}]}}]}\n\n"
				+ "data: [DONE]\n\n";
		OpenRouterApi api = mappedApi(mapper, body);
		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().toolCalls().get(0).function().name())
				.isEqualTo("1.5"))
			.verifyComplete();
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

	private static ChatCompletionRequest chatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(true)
			.build();
	}

	@Test
	void toleratesUnknownFieldsInStreamChunks() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"content":"Hi","reasoning_details":[{"type":"reasoning.text","text":"thinking","format":"unknown","index":0}]},"brand_new_field":true}]}

				data: [DONE]

				""";
		OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(api.chatCompletionStream(chatRequest())).assertNext(chunk -> {
			assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi");
			// The unknown reasoning format and the unknown choice field are kept, not
			// dropped or rejected.
			assertThat(chunk.choices().get(0).delta().reasoningDetails()).hasSize(1);
			assertThat(chunk.choices().get(0).delta().reasoningDetails().get(0).path("format").asString())
				.isEqualTo("unknown");
			assertThat(chunk.choices().get(0).extensions()).containsEntry("brand_new_field", true);
		}).verifyComplete();
	}

	@Test
	void failsStalledStreamWhenTimeoutIsConfigured() {
		// The body emits one chunk, then stalls forever. Virtual time moves past the
		// configured timeout, so the stream errors instead of hanging.
		StepVerifier.withVirtualTime(() -> {
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
			OpenRouterApi api = OpenRouterApi.builder()
				.apiKey("test-key")
				.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
				.timeout(Duration.ofSeconds(1))
				.build();
			return api.chatCompletionStream(chatRequest());
		})
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.expectNoEvent(Duration.ofMillis(900))
			.thenAwait(Duration.ofMillis(100))
			.expectError(TimeoutException.class)
			.verify();
	}

	static Stream<Arguments> keepAliveEndpoints() {
		Function<OpenRouterApi, Flux<String>> chat = api -> api.chatCompletionStream(chatRequest())
			.map(chunk -> chunk.choices().get(0).delta().content());
		Function<OpenRouterApi, Flux<String>> responses = api -> api
			.responsesStream(ResponsesRequest.builder().model("test").input("hello").stream(true).build())
			.mapNotNull(event -> event.delta());
		Function<OpenRouterApi, Flux<String>> images = api -> api
			.imagesStream(new ImagesRequest("test", "hello", null, null, null, null, null, null, null, null, null, true,
					null, null))
			.map(event -> event.b64Json());
		return Stream.of(Arguments.of(Named.of("chat", chat),
				"data: {\"id\":\"gen-1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
				Arguments.of(Named.of("responses", responses),
						"data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\ndata: {\"type\":\"response.completed\"}\n\n"),
				Arguments.of(Named.of("images", images),
						"data: {\"type\":\"image_generation.completed\",\"b64_json\":\"Hi\"}\n\ndata: [DONE]\n\n"));
	}

	@ParameterizedTest
	@MethodSource("keepAliveEndpoints")
	void keepAliveCommentsResetTheStreamTimeout(Function<OpenRouterApi, Flux<String>> stream, String finalEvents) {
		// OpenRouter emits ": OPENROUTER PROCESSING" SSE comments as keep-alives during
		// slow generations. The parser filters them out, but the timeout sits on the SSE
		// event stream, so comments spaced under the timeout must NOT trip it before the
		// real event arrives, on every SSE endpoint. Virtual time makes the
		// 100ms-interval-vs-250ms-timeout relationship deterministic instead of racing
		// real CI wall-clock.
		StepVerifier.withVirtualTime(() -> {
			DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
			Flux<DataBuffer> body = Flux.interval(Duration.ofMillis(100))
				.take(15)
				.map(tick -> bufferFactory.wrap(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8)))
				.concatWith(Flux.just(bufferFactory.wrap(finalEvents.getBytes(StandardCharsets.UTF_8))))
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
			return stream.apply(api);
		}).thenAwait(Duration.ofSeconds(2)).expectNext("Hi").verifyComplete();
	}

	@JsonDeserialize(using = ParserFunctionDeserializer.class)
	private abstract static class FunctionMixin {

	}

	public static final class ParserFunctionDeserializer extends ValueDeserializer<FunctionCall> {

		@Override
		public FunctionCall deserialize(JsonParser parser, DeserializationContext context) {
			parser.nextToken();
			parser.nextToken();
			String rawValueText = parser.getText();
			parser.skipChildren();
			parser.nextToken();
			return new FunctionCall(rawValueText, "{}");
		}

	}

}
