package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.Function;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesTool;
import de.subhransu.openrouter.springai.api.dto.Tool;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.codec.json.JacksonJsonDecoder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

class OpenRouterApiMapperTests {

	private static final String BASE_URL = "https://synthetic.invalid";

	private static final Duration WAIT = Duration.ofSeconds(5);

	private static final String SCHEMA = """
			{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
			""";

	@ParameterizedTest
	@EnumSource(Path.class)
	void suppliedMapperEncodesAndDecodesEveryEndpoint(Path path) {
		var transport = new Transport(path, false);
		ObjectMapper mapper = customizedMapper();
		String result = invoke(path, transport.api(mapper), request(path, false));
		assertThat(result).isEqualTo("decoded");
		assertThat(new ObjectMapper().readTree(transport.body.get()).path("model").stringValue()).isEqualTo("encoded");
		assertThat(transport.headers.get().getFirst("HTTP-Referer")).isEqualTo("https://app.invalid");
		assertThat(transport.headers.get().getFirst("X-OpenRouter-Title")).isEqualTo("synthetic-app");
		assertThat(transport.headers.get().getFirst("X-OpenRouter-Categories")).isEqualTo("synthetic");
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void suppliedMapperOverridesBuilderJsonConvertersAndCodecs(Path path) {
		var transport = new Transport(path, false);
		transport.customizeBuilderJson();
		String result = invoke(path, transport.api(customizedMapper()), request(path, false));
		assertThat(new ObjectMapper().readTree(transport.body.get()).path("model").stringValue()).isEqualTo("encoded");
		assertThat(result).isEqualTo("decoded");
		assertThat(transport.headers.get().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
		assertThat(transport.headers.get().getFirst("X-OpenRouter-Title")).isEqualTo("synthetic-app");
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/vnd.synthetic+json", "application/x-ndjson" })
	void suppliedMapperOverridesBuilderDecoderForImageFallback(String contentType) {
		var transport = new Transport(Path.IMAGES_JSON, false, MediaType.parseMediaType(contentType));
		transport.customizeBuilderJson();
		StepVerifier
			.create(transport.api(customizedMapper()).imagesStream((ImagesRequest) request(Path.IMAGES_JSON, false)))
			.assertNext(event -> assertThat(event.b64Json()).isEqualTo("decoded"))
			.verifyComplete();
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void defaultMapperPreservesBuilderJsonConvertersAndCodecs(Path path) {
		var transport = new Transport(path, false);
		transport.customizeBuilderJson();
		String result = invoke(path, transport.api(null), request(path, false));
		assertThat(new ObjectMapper().readTree(transport.body.get()).path("model").stringValue()).isEqualTo("builder");
		assertThat(result).isEqualTo(path == Path.IMAGES_JSON ? "builder" : "wire");
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/vnd.synthetic+json", "application/x-ndjson" })
	void suppliedMapperKeepsImageFallbackJsonMediaTypes(String contentType) {
		var defaults = new Transport(Path.IMAGES_JSON, false, MediaType.parseMediaType(contentType));
		assertThat(invoke(Path.IMAGES_JSON, defaults.api(null), request(Path.IMAGES_JSON, false))).isEqualTo("wire");
		var transport = new Transport(Path.IMAGES_JSON, false, MediaType.parseMediaType(contentType));
		assertThat(invoke(Path.IMAGES_JSON, transport.api(customizedMapper()), request(Path.IMAGES_JSON, false)))
			.isEqualTo("decoded");
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void defaultRequestBytesMatchTheHistoricalTransport(Path path) {
		Object request = request(path, false);
		var legacy = new Transport(path, false);
		if (path.streaming()) {
			legacy.web.build()
				.post()
				.uri(BASE_URL + path.uri())
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(request)
				.exchangeToMono(response -> response.releaseBody())
				.block(WAIT);
		}
		else {
			legacy.rest.requestInterceptor((outgoing, body, next) -> next.execute(outgoing, body))
				.build()
				.post()
				.uri(BASE_URL + path.uri())
				.contentType(MediaType.APPLICATION_JSON)
				.body(request)
				.exchange((outgoing, incoming) -> null);
		}
		for (ObjectMapper mapper : Arrays.asList(null, new ObjectMapper())) {
			var transport = new Transport(path, false);
			invoke(path, transport.api(mapper), request);
			assertThat(transport.body.get()).as(path + " mapper=" + (mapper != null))
				.containsExactly(legacy.body.get());
		}
	}

	@ParameterizedTest
	@EnumSource(Path.class)
	void serializationFailuresSendNoBytes(Path path) {
		for (ObjectMapper mapper : Arrays.asList(null, customizedMapper())) {
			var transport = new Transport(path, true);
			transport.customizeBuilderJson();
			assertThatThrownBy(() -> invoke(path, transport.api(mapper), request(path, true)))
				.hasStackTraceContaining("synthetic serialization failure");
			assertThat(transport.sentBytes).hasValue(0);
		}
	}

	private static ObjectMapper customizedMapper() {
		return new ObjectMapper().rebuild().addModule(customizations("encoded", "decoded")).build();
	}

	private static SimpleModule customizations(String encoded, String decoded) {
		SimpleModule module = new SimpleModule();
		module.addSerializer(String.class, new ValueSerializer<String>() {
			@Override
			public void serialize(String value, JsonGenerator generator, SerializationContext context) {
				generator.writeString("synthetic".equals(value) ? encoded : value);
			}
		});
		module.addDeserializer(String.class, new ValueDeserializer<String>() {
			@Override
			public String deserialize(JsonParser parser, DeserializationContext context) {
				String value = parser.getString();
				return "wire".equals(value) ? decoded : value;
			}
		});
		return module;
	}

	private static Object request(Path path, boolean reject) {
		ObjectMapper mapper = new ObjectMapper();
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("last", false);
		metadata.put("first", null);
		metadata.put("text", "quotes: \" and slash: \\ and Unicode: \u2603");
		if (reject) {
			metadata.put("padding", "x".repeat(64 * 1024));
			metadata.put("reject", new Rejected());
		}
		var schema = mapper.readTree(SCHEMA);
		var choice = mapper.readTree("{\"type\":\"function\",\"function\":{\"name\":\"lookup\"}}");
		var parts = List.of(ContentPart.text("synthetic"), ContentPart.image("data:image/png;base64,YQ=="),
				new ContentPart("file", null, null, null,
						new ContentPart.FileInput("synthetic.pdf", "data:application/pdf;base64,Yg=="), null, null),
				new ContentPart("input_audio", null, null, null, null, new ContentPart.AudioInput("Yw==", "wav"), null),
				new ContentPart("video_url", null, null, null, null, null,
						new ContentPart.VideoUrl("https://media.invalid/video.mp4")));
		return switch (path) {
			case CHAT,
					CHAT_STREAM ->
				ChatCompletionRequest.builder()
					.model("synthetic")
					.messages(List.of(new ChatMessage("user", parts, null, null, null)))
					.stream(path.streaming())
					.tools(List.of(new Tool("function", new Function("lookup", "synthetic", schema, true))))
					.toolChoice(choice)
					.responseFormat(Map.of("type", "json_schema", "json_schema",
							Map.of("name", "answer", "strict", true, "schema", schema)))
					.metadata(metadata)
					.extraBody(Map.of("logprobs", true, "top_logprobs", 0))
					.build();
			case RESPONSES,
					RESPONSES_STREAM ->
				ResponsesRequest.builder()
					.model("synthetic")
					.input(List.of(Map.of("role", "user", "content",
							List.of(Map.of("type", "input_text", "text", "synthetic"),
									Map.of("type", "input_image", "image_url", "data:image/png;base64,YQ=="),
									Map.of("type", "input_file", "filename", "synthetic.pdf", "file_data",
											"data:application/pdf;base64,Yg==")))))
					.stream(path.streaming())
					.tools(List.of(new ResponsesTool("function", "lookup", "synthetic", schema, true)))
					.toolChoice(mapper.readTree("{\"type\":\"function\",\"name\":\"lookup\"}"))
					.text(Map.of("format",
							Map.of("type", "json_schema", "name", "answer", "strict", true, "schema", schema)))
					.metadata(metadata)
					.build();
			case EMBEDDINGS -> new EmbeddingsRequest("synthetic",
					reject ? new Rejected() : List.of("synthetic", "\u2603"), "float", 3, "synthetic", null);
			case IMAGES, IMAGES_STREAM, IMAGES_JSON -> new ImagesRequest("synthetic", "synthetic", 1, null, "2K", "1:1",
					"high", "png", null, 80, 0, path.streaming(), List.of(parts.get(1)), metadata);
		};
	}

	private static String invoke(Path path, OpenRouterApi api, Object request) {
		return switch (path) {
			case CHAT -> api.chatCompletion((ChatCompletionRequest) request).id();
			case RESPONSES -> api.responses((ResponsesRequest) request).id();
			case EMBEDDINGS -> api.embeddings((EmbeddingsRequest) request).model();
			case IMAGES -> api.images((ImagesRequest) request).data().get(0).b64Json();
			case CHAT_STREAM -> api.chatCompletionStream((ChatCompletionRequest) request).blockLast(WAIT).id();
			case RESPONSES_STREAM -> api.responsesStream((ResponsesRequest) request)
				.filter(event -> event.delta() != null)
				.blockLast(WAIT)
				.delta();
			case IMAGES_STREAM, IMAGES_JSON -> api.imagesStream((ImagesRequest) request).blockLast(WAIT).b64Json();
		};
	}

	private static final class Transport {

		private final AtomicReference<byte[]> body = new AtomicReference<>();

		private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();

		private final AtomicInteger sentBytes = new AtomicInteger();

		private final RestClient.Builder rest;

		private final WebClient.Builder web;

		private Transport(Path path, boolean countBytes) {
			this(path, countBytes, path == Path.IMAGES_JSON ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM);
		}

		private Transport(Path path, boolean countBytes, MediaType contentType) {
			this.rest = RestClient.builder().requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
				@Override
				protected ClientHttpResponse executeInternal() {
					body.set(getBodyAsBytes());
					headers.set(HttpHeaders.copyOf(getHeaders()));
					sentBytes.addAndGet(getBodyAsBytes().length);
					return new MockClientHttpResponse(path.response().getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
				}
			});
			this.web = WebClient.builder().clientConnector((method, uri, callback) -> {
				var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
				var incoming = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(HttpStatus.OK);
				incoming.getHeaders().setContentType(contentType);
				incoming.setBody(path.response());
				if (countBytes) {
					outgoing.setWriteHandler(buffers -> buffers.doOnNext(buffer -> {
						sentBytes.addAndGet(buffer.readableByteCount());
						DataBufferUtils.release(buffer);
					}).then());
					return callback.apply(outgoing).thenReturn(incoming);
				}
				return callback.apply(outgoing).then(Mono.defer(outgoing::getBodyAsString)).doOnNext(value -> {
					body.set(value.getBytes(StandardCharsets.UTF_8));
					headers.set(HttpHeaders.copyOf(outgoing.getHeaders()));
				}).thenReturn(incoming);
			});
		}

		private void customizeBuilderJson() {
			JsonMapper mapper = JsonMapper.builder().addModule(customizations("builder", "builder")).build();
			this.rest.configureMessageConverters(
					converters -> converters.addCustomConverter(new JacksonJsonHttpMessageConverter(mapper)));
			this.web.codecs(codecs -> {
				codecs.customCodecs().register(new JacksonJsonEncoder(mapper));
				codecs.customCodecs().register(new JacksonJsonDecoder(mapper));
			});
		}

		private OpenRouterApi api(ObjectMapper mapper) {
			var builder = OpenRouterApi.builder()
				.apiKey("synthetic-key")
				.baseUrl(BASE_URL)
				.restClientBuilder(this.rest)
				.webClientBuilder(this.web)
				.httpReferer("https://app.invalid")
				.applicationTitle("synthetic-app")
				.applicationCategories("synthetic");
			return (mapper == null ? builder : builder.objectMapper(mapper)).build();
		}

	}

	enum Path {

		CHAT, RESPONSES, EMBEDDINGS, IMAGES, CHAT_STREAM, RESPONSES_STREAM, IMAGES_STREAM, IMAGES_JSON;

		private boolean streaming() {
			return ordinal() >= CHAT_STREAM.ordinal();
		}

		private String uri() {
			return switch (this) {
				case CHAT, CHAT_STREAM -> "/chat/completions";
				case RESPONSES, RESPONSES_STREAM -> "/responses";
				case EMBEDDINGS -> "/embeddings";
				case IMAGES, IMAGES_STREAM, IMAGES_JSON -> "/images";
			};
		}

		private String response() {
			return switch (this) {
				case CHAT, RESPONSES -> "{\"id\":\"wire\",\"status\":\"completed\",\"output\":[],\"choices\":[]}";
				case EMBEDDINGS -> "{\"model\":\"wire\",\"data\":[]}";
				case IMAGES, IMAGES_JSON -> "{\"data\":[{\"b64_json\":\"wire\"}]}";
				case CHAT_STREAM ->
					"data: {\"id\":\"wire\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"text\"}}]}\n\ndata: [DONE]\n\n";
				case RESPONSES_STREAM ->
					"data: {\"type\":\"response.output_text.delta\",\"delta\":\"wire\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
				case IMAGES_STREAM ->
					"data: {\"type\":\"image_generation.completed\",\"b64_json\":\"wire\"}\n\ndata: [DONE]\n\n";
			};
		}

	}

	@JsonSerialize(using = RejectedSerializer.class)
	static final class Rejected {

	}

	static final class RejectedSerializer extends ValueSerializer<Rejected> {

		@Override
		public void serialize(Rejected value, JsonGenerator generator, SerializationContext context) {
			generator.writeStartObject();
			generator.writeStringProperty("partial", "x".repeat(32768));
			throw new IllegalStateException("synthetic serialization failure");
		}

	}

}
