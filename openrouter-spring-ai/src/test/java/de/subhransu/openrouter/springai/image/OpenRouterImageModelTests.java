package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.image.ImageOptions;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import tools.jackson.databind.ObjectMapper;
import org.springframework.ai.image.ImageOptionsBuilder;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterImageModelTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	private static final String SUCCESS_BODY = """
			{
			  "created": 1750000000,
			  "data": [
			    {"b64_json": "aW1hZ2Ux", "media_type": "image/png"}
			  ],
			  "usage": {"prompt_tokens": 0, "completion_tokens": 4160, "total_tokens": 4160, "cost": 0.03}
			}
			""";

	private Fixture fixture(OpenRouterImageOptions defaultOptions) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder)
			.build();
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(defaultOptions)
			.build();
		return new Fixture(model, server);
	}

	@Test
	void mapsImagesResponseIntoSpringAiTypes() {
		Fixture fixture = fixture(OpenRouterImageOptions.builder().model(MODEL).build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/images"))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.prompt").value("a red panda"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		ImageResponse response = fixture.model().call(new ImagePrompt("a red panda"));

		assertThat(response.getResults()).hasSize(1);
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
		OpenRouterImageGenerationMetadata metadata = (OpenRouterImageGenerationMetadata) response.getResult()
			.getMetadata();
		assertThat(metadata.mediaType()).isEqualTo("image/png");
		assertThat(metadata.partialImageIndex()).isNull();
		assertThat(response.getMetadata().getCreated()).isEqualTo(1750000000L);
		OpenRouterUsage usage = response.getMetadata().get("openrouter.usage");
		assertThat(usage.getCost()).isEqualTo(0.03);
		fixture.server().verify();
	}

	@Test
	void runtimeOptionsOverrideDefaultsAndPortableSizeMapsToPixels() {
		Fixture fixture = fixture(OpenRouterImageOptions.builder().model(MODEL).quality("low").build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/images"))
			.andExpect(jsonPath("$.model").value("openai/gpt-image-1"))
			.andExpect(jsonPath("$.size").value("1024x768"))
			.andExpect(jsonPath("$.quality").value("low"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.model()
			.call(new ImagePrompt("a red panda",
					ImageOptionsBuilder.builder().model("openai/gpt-image-1").width(1024).height(768).build()));

		fixture.server().verify();
	}

	// OpenRouter's /images contract expects each reference image as an image content
	// object, not a bare URL string; a bare-string array is rejected or ignored.
	@Test
	void inputReferencesAreSentAsImageUrlContentObjects() {
		Fixture fixture = fixture(OpenRouterImageOptions.builder()
			.model(MODEL)
			.inputReferences(java.util.List.of("https://example.test/reference.png"))
			.build());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/images"))
			.andExpect(jsonPath("$.input_references[0].type").value("image_url"))
			.andExpect(jsonPath("$.input_references[0].image_url.url").value("https://example.test/reference.png"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.model().call(new ImagePrompt("a red panda"));

		fixture.server().verify();
	}

	@Test
	void rejectsHalfSpecifiedDimensions() {
		Fixture fixture = fixture(OpenRouterImageOptions.builder().model(MODEL).width(1024).build());
		OpenRouterImageModel model = fixture.model();
		ImagePrompt prompt = new ImagePrompt("a red panda");

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("width");
	}

	@Test
	void streamsPartialAndCompletedImages() {
		String sse = """
				data: {"type":"image_generation.partial_image","partial_image_index":0,"b64_json":"cGFydGlhbA=="}

				data: {"type":"image_generation.completed","b64_json":"aW1hZ2Ux","media_type":"image/png"}

				data: {"type":"image_generation.completed","b64_json":"ZmluYWw=","media_type":"image/png","created":1750000000,"usage":{"completion_tokens":4160,"total_tokens":4160,"cost":0.03}}

				data: [DONE]

				""";
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(sse)
			.build());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).build())
			.build();

		StepVerifier.create(model.stream(new ImagePrompt("a red panda"))).assertNext(partial -> {
			OpenRouterImageGenerationMetadata metadata = (OpenRouterImageGenerationMetadata) partial.getResult()
				.getMetadata();
			assertThat(metadata.partialImageIndex()).isZero();
			assertThat(partial.getResult().getOutput().getB64Json()).isEqualTo("cGFydGlhbA==");
		}).assertNext(first -> {
			assertThat(first.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
		}).assertNext(completed -> {
			assertThat(completed.getResult().getOutput().getB64Json()).isEqualTo("ZmluYWw=");
			assertThat(completed.getMetadata().getCreated()).isEqualTo(1750000000L);
			assertThat(completed.getMetadata().<String>get("openrouter.event_type"))
				.isEqualTo("image_generation.completed");
			OpenRouterUsage usage = completed.getMetadata().get("openrouter.usage");
			assertThat(usage.getTotalTokens()).isEqualTo(4160);
			assertThat(usage.getCost()).isEqualTo(0.03);
		}).verifyComplete();
	}

	// Live finding from the Garage paint bay: providers without native image streaming
	// make OpenRouter ignore stream=true and answer with one complete application/json
	// generation instead of SSE. The stream surface must fall back to a single
	// completed event rather than failing to decode the JSON as an event stream.
	@Test
	void streamFallsBackToSingleCompletedEventWhenProviderDoesNotStream() {
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.body(SUCCESS_BODY)
			.build());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).build())
			.build();

		StepVerifier.create(model.stream(new ImagePrompt("a red panda"))).assertNext(completed -> {
			assertThat(completed.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
			OpenRouterImageGenerationMetadata metadata = (OpenRouterImageGenerationMetadata) completed.getResult()
				.getMetadata();
			assertThat(metadata.mediaType()).isEqualTo("image/png");
			assertThat(metadata.partialImageIndex()).isNull();
			assertThat(completed.getMetadata().getCreated()).isEqualTo(1750000000L);
			assertThat(completed.getMetadata().<String>get("openrouter.event_type"))
				.isEqualTo("image_generation.completed");
			OpenRouterUsage usage = completed.getMetadata().get("openrouter.usage");
			assertThat(usage.getCost()).isEqualTo(0.03);
		}).verifyComplete();
	}

	static Stream<Arguments> compatiblePortableOptions() {
		return Stream.of(false, true)
			.flatMap(stream -> Stream.of(false, true)
				.flatMap(
						defaults -> Stream.of(null, "b64_json").map(format -> Arguments.of(stream, defaults, format))));
	}

	@ParameterizedTest
	@MethodSource("compatiblePortableOptions")
	void portableOptionsPreserveWireFields(boolean stream, boolean asDefaults, String format) {
		ImageOptions portable = ImageOptionsBuilder.builder()
			.model(MODEL)
			.n(2)
			.width(128)
			.height(256)
			.responseFormat(format)
			.build();
		OpenRouterImageOptions defaults = OpenRouterImageOptions.builder()
			.model("test/default")
			.n(1)
			.width(512)
			.height(512)
			.quality("high")
			.outputFormat("webp")
			.providerOptions(Map.of("options", Map.of("test", Map.of("watermark", false))))
			.build();
		if (asDefaults) {
			defaults = defaults.merge(OpenRouterImageOptions.fromOptions(portable));
		}
		AtomicReference<String> body = new AtomicReference<>();
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		if (!stream) {
			server.expect(requestTo(BASE_URL + "/images"))
				.andExpect(request -> body
					.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
		}
		WebClient.Builder web = WebClient.builder().exchangeFunction(request -> {
			var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(request.method(),
					request.url());
			return request.writeTo(outgoing, ExchangeStrategies.withDefaults())
				.then(Mono.defer(outgoing::getBodyAsString))
				.doOnNext(body::set)
				.map(ignored -> ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
					.body(SUCCESS_BODY)
					.build());
		});
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(OpenRouterApi.builder()
				.apiKey("test-key")
				.baseUrl(BASE_URL)
				.restClientBuilder(rest)
				.webClientBuilder(web)
				.build())
			.defaultOptions(defaults)
			.build();
		ImagePrompt prompt = asDefaults ? new ImagePrompt("a blue cube") : new ImagePrompt("a blue cube", portable);
		if (stream) {
			StepVerifier.create(model.stream(prompt))
				.assertNext(response -> assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux"))
				.expectComplete()
				.verify(Duration.ofSeconds(5));
		}
		else {
			assertThat(model.call(prompt).getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
		}
		var json = new ObjectMapper().readTree(body.get());
		assertThat(json.path("model").asString()).isEqualTo(MODEL);
		assertThat(json.path("n").asInt()).isEqualTo(2);
		assertThat(json.path("size").asString()).isEqualTo("128x256");
		assertThat(json.path("quality").asString()).isEqualTo("high");
		assertThat(json.path("output_format").asString()).isEqualTo("webp");
		assertThat(json.at("/provider/options/test/watermark").asBoolean(true)).isFalse();
		assertThat(json.has("response_format")).isFalse();
		assertThat(json.has("style")).isFalse();
		assertThat(json.path("stream").asBoolean(false)).isEqualTo(stream);
		server.verify();
	}

	static Stream<Arguments> unsupportedPortableOptions() {
		return Stream.of(false, true)
			.flatMap(stream -> Stream.of("url", "", "B64_JSON", "unknown")
				.flatMap(value -> Stream.of(
						Arguments.of(stream, ImageOptionsBuilder.builder().responseFormat(value).build(),
								"responseFormat"),
						Arguments.of(stream, ImageOptionsBuilder.builder().style(value).build(), "style"))));
	}

	@ParameterizedTest
	@MethodSource("unsupportedPortableOptions")
	void rejectsUnsupportedPortableOptionsBeforeTransport(boolean stream, ImageOptions options, String field) {
		OpenRouterApi api = org.mockito.Mockito.mock(OpenRouterApi.class);
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).quality("high").build())
			.build();
		ImagePrompt prompt = new ImagePrompt("a blue cube", options);
		assertThatThrownBy(() -> {
			if (stream) {
				model.stream(prompt).blockLast(Duration.ofSeconds(5));
			}
			else {
				model.call(prompt);
			}
		}).isInstanceOf(IllegalArgumentException.class).hasMessageContaining(field);
		assertThatThrownBy(() -> OpenRouterImageOptions.fromOptions(options))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining(field);
		org.mockito.Mockito.verifyNoInteractions(api);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"AQID\"}\n\n",
			"data: {\"type\":\"synthetic.metadata\",\"usage\":{\"total_tokens\":3}}\n\n",
			"data: {\"type\":\"image_generation.partial_image\",\"partial_image_index\":0}\n\n" })
	void rejectsDoneWithoutCompletedImageAndRecordsObservationError(String prefix) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterImageModel model = streamingModel(new AtomicReference<>(prefix + "data: [DONE]\n\n"), registry);
		StepVerifier.create(model.stream(new ImagePrompt("synthetic image")))
			.expectNextCount(prefix.contains("partial_image") ? 1 : 0)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessageContaining("without a completed image"))
			.verify();
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
			.hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterTruncatedResponseException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"type\":null}", "{\"type\":\"\"}" })
	void rejectsMissingImageEventType(String event) {
		OpenRouterImageModel model = streamingModel(new AtomicReference<>("data: " + event + "\n\ndata: [DONE]\n\n"),
				TestObservationRegistry.create());
		StepVerifier.create(model.stream(new ImagePrompt("synthetic image")))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("requires a type"))
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"type\":\"synthetic.future.event\"}",
			"{\"type\":\"synthetic.metadata\",\"usage\":{\"total_tokens\":3}}",
			"{\"type\":\"synthetic.future.event\",\"b64_json\":\"AQID\"}" })
	void ignoresUnknownEventsBeforeRealImage(String event) {
		String body = "data: " + event + "\n\n"
				+ "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"AQID\",\"usage\":{\"total_tokens\":7}}\n\n"
				+ "data: [DONE]\n\n";
		OpenRouterImageModel model = streamingModel(new AtomicReference<>(body), TestObservationRegistry.create());
		StepVerifier.create(model.stream(new ImagePrompt("synthetic image"))).assertNext(response -> {
			assertThat(response.getResults()).hasSize(1);
			assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("AQID");
		}).verifyComplete();
	}

	@Test
	void completionStateIsIndependentForRepeatedSubscriptions() {
		AtomicReference<String> body = new AtomicReference<>(
				"data: {\"type\":\"image_generation.completed\",\"b64_json\":\"AQID\"}\n\ndata: [DONE]\n\n");
		Flux<ImageResponse> stream = streamingModel(body, TestObservationRegistry.create())
			.stream(new ImagePrompt("synthetic image"));
		StepVerifier.create(stream).expectNextCount(1).verifyComplete();
		body.set("data: [DONE]\n\n");
		StepVerifier.create(stream).expectError(OpenRouterTruncatedResponseException.class).verify();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedImageDoesNotHideLaterFailure(boolean providerError) {
		String body = "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"AQID\"}\n\n" + (providerError
				? "data: {\"type\":\"error\",\"error\":{\"code\":500,\"message\":\"synthetic failure\"}}\n\n" : "");
		StepVerifier
			.create(streamingModel(new AtomicReference<>(body), TestObservationRegistry.create())
				.stream(new ImagePrompt("synthetic image")))
			.expectNextCount(1)
			.expectError(providerError ? OpenRouterApiException.class : OpenRouterTruncatedResponseException.class)
			.verify();
	}

	private OpenRouterImageModel streamingModel(AtomicReference<String> body, TestObservationRegistry registry) {
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(body.get())
					.build())))
			.build();
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.observationRegistry(registry)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).n(3).build())
			.build();
	}

	private record Fixture(OpenRouterImageModel model, MockRestServiceServer server) {
	}

}
