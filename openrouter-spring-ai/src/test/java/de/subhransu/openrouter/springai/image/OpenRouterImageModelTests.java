package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
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
import org.springframework.ai.image.observation.ImageModelObservationContext;
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

	private static final String DONE = "data: [DONE]\n\n";

	private static final String USAGE_KEY = "openrouter.usage";

	private static final String EVENT_TYPE_KEY = "openrouter.event_type";

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
		OpenRouterUsage usage = response.getMetadata().get(USAGE_KEY);
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

		var response = fixture.model()
			.call(new ImagePrompt("a red panda",
					ImageOptionsBuilder.builder().model("openai/gpt-image-1").width(1024).height(768).build()));

		fixture.server().verify();
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
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

		var response = fixture.model().call(new ImagePrompt("a red panda"));

		fixture.server().verify();
		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
	}

	static Stream<Arguments> halfSpecifiedDimensions() {
		return Stream.of(
				Arguments.of(Named.of("width only", 1024), null,
						"Both width and height must be set to derive the image size (got width=1024, height=null)"),
				Arguments.of(Named.of("height only", null), 768,
						"Both width and height must be set to derive the image size (got width=null, height=768)"));
	}

	@ParameterizedTest
	@MethodSource("halfSpecifiedDimensions")
	void rejectsHalfSpecifiedDimensions(Integer width, Integer height, String message) {
		OpenRouterImageOptions defaults = OpenRouterImageOptions.builder()
			.model(MODEL)
			.width(width)
			.height(height)
			.build();
		OpenRouterImageModel model = fixture(defaults).model();
		ImagePrompt prompt = new ImagePrompt("a red panda");

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
	}

	@ParameterizedTest
	@MethodSource("halfSpecifiedDimensions")
	void rejectsHalfSpecifiedDimensionsOnTheStreamingPathAndRecordsTheError(Integer width, Integer height,
			String message) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterImageModel model = streamingModel(new AtomicReference<>(DONE), registry);
		ImagePrompt prompt = new ImagePrompt("a red panda",
				OpenRouterImageOptions.builder().width(width).height(height).build());

		StepVerifier.create(model.stream(prompt))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(IllegalArgumentException.class).hasMessage(message))
			.verify();
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
			.hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(IllegalArgumentException.class);
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
		TestObservationRegistry registry = TestObservationRegistry.create();
		AtomicReference<ImageResponse> observed = new AtomicReference<>();
		registry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof ImageModelObservationContext;
			}

			@Override
			public void onStop(Observation.Context context) {
				observed.set(((ImageModelObservationContext) context).getResponse());
			}
		});
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(api)
			.observationRegistry(registry)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).build())
			.build();

		StepVerifier.create(model.stream(new ImagePrompt("a red panda"))).assertNext(partial -> {
			OpenRouterImageGenerationMetadata metadata = (OpenRouterImageGenerationMetadata) partial.getResult()
				.getMetadata();
			assertThat(metadata.partialImageIndex()).isZero();
			assertThat(partial.getResult().getOutput().getB64Json()).isEqualTo("cGFydGlhbA==");
			assertThat(partial.getMetadata().<String>get(EVENT_TYPE_KEY)).isEqualTo("image_generation.partial_image");
			assertThat(partial.getMetadata().<Object>get(USAGE_KEY)).isNull();
		}).assertNext(first -> {
			assertThat(first.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
			assertThat(first.getMetadata().<String>get(EVENT_TYPE_KEY)).isEqualTo("image_generation.completed");
			assertThat(first.getMetadata().<Object>get(USAGE_KEY)).isNull();
		}).assertNext(completed -> {
			assertThat(completed.getResult().getOutput().getB64Json()).isEqualTo("ZmluYWw=");
			assertThat(completed.getMetadata().getCreated()).isEqualTo(1750000000L);
			assertThat(completed.getMetadata().<String>get(EVENT_TYPE_KEY)).isEqualTo("image_generation.completed");
			OpenRouterUsage usage = completed.getMetadata().get(USAGE_KEY);
			assertThat(usage.getTotalTokens()).isEqualTo(4160);
			assertThat(usage.getCost()).isEqualTo(0.03);
		}).verifyComplete();
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
			.hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.doesNotHaveError();
		// The observation context ends up with the last emitted event: the final image.
		assertThat(observed.get().getResult().getOutput().getB64Json()).isEqualTo("ZmluYWw=");
		assertThat(observed.get().getMetadata().<OpenRouterUsage>get(USAGE_KEY).getTotalTokens()).isEqualTo(4160);
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
			assertThat(completed.getMetadata().<String>get(EVENT_TYPE_KEY)).isEqualTo("image_generation.completed");
			OpenRouterUsage usage = completed.getMetadata().get(USAGE_KEY);
			assertThat(usage.getCost()).isEqualTo(0.03);
		}).verifyComplete();
	}

	static Stream<Arguments> compatiblePortableOptions() {
		return Stream.of(false, true)
			.flatMap(defaults -> Stream.of(null, "b64_json").map(format -> Arguments.of(defaults, format)));
	}

	private static OpenRouterImageOptions defaultsFor(boolean portableAsDefaults, String format) {
		OpenRouterImageOptions defaults = OpenRouterImageOptions.builder()
			.model("test/default")
			.n(1)
			.width(512)
			.height(512)
			.quality("high")
			.outputFormat("webp")
			.providerOptions(Map.of("options", Map.of("test", Map.of("watermark", false))))
			.build();
		return portableAsDefaults ? defaults.merge(OpenRouterImageOptions.fromOptions(portable(format))) : defaults;
	}

	private static ImagePrompt promptFor(boolean portableAsDefaults, String format) {
		return portableAsDefaults ? new ImagePrompt("a blue cube") : new ImagePrompt("a blue cube", portable(format));
	}

	private static ImageOptions portable(String format) {
		return ImageOptionsBuilder.builder().model(MODEL).n(2).width(128).height(256).responseFormat(format).build();
	}

	private static void assertPortableWire(String body, boolean stream) {
		var json = new ObjectMapper().readTree(body);
		assertThat(json.path("model").asString()).isEqualTo(MODEL);
		assertThat(json.path("n").asInt()).isEqualTo(2);
		assertThat(json.path("size").asString()).isEqualTo("128x256");
		assertThat(json.path("quality").asString()).isEqualTo("high");
		assertThat(json.path("output_format").asString()).isEqualTo("webp");
		assertThat(json.at("/provider/options/test/watermark").asBoolean(true)).isFalse();
		assertThat(json.has("response_format")).isFalse();
		assertThat(json.has("style")).isFalse();
		assertThat(json.path("stream").asBoolean(false)).isEqualTo(stream);
	}

	@ParameterizedTest(name = "portable options as defaults = {0}, responseFormat = {1}")
	@MethodSource("compatiblePortableOptions")
	void portableOptionsPreserveWireFieldsOnBlockingCalls(boolean asDefaults, String format) {
		AtomicReference<String> body = new AtomicReference<>();
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo(BASE_URL + "/images"))
			.andExpect(request -> body
				.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).restClientBuilder(rest).build())
			.defaultOptions(defaultsFor(asDefaults, format))
			.build();

		ImageResponse response = model.call(promptFor(asDefaults, format));

		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux");
		assertPortableWire(body.get(), false);
		server.verify();
	}

	@ParameterizedTest(name = "portable options as defaults = {0}, responseFormat = {1}")
	@MethodSource("compatiblePortableOptions")
	void portableOptionsPreserveWireFieldsOnServerSentEventStreams(boolean asDefaults, String format) {
		AtomicReference<String> body = new AtomicReference<>();
		WebClient.Builder web = WebClient.builder().exchangeFunction(request -> {
			var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(request.method(),
					request.url());
			return request.writeTo(outgoing, ExchangeStrategies.withDefaults())
				.then(Mono.defer(outgoing::getBodyAsString))
				.doOnNext(body::set)
				.map(ignored -> ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body("data: {\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2Ux\"}\n\n" + DONE)
					.build());
		});
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).webClientBuilder(web).build())
			.defaultOptions(defaultsFor(asDefaults, format))
			.build();

		StepVerifier.create(model.stream(promptFor(asDefaults, format)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2Ux"))
			.verifyComplete();

		assertPortableWire(body.get(), true);
	}

	private static final String RESPONSE_FORMAT_MESSAGE = "OpenRouter image responseFormat must be null or 'b64_json'; URL output cannot be requested";

	private static final String STYLE_MESSAGE = "OpenRouter does not support portable image style; leave style unset and use providerOptions for provider-specific settings";

	static Stream<Arguments> unsupportedPortableOptions() {
		return Stream.of("url", "", "B64_JSON", "unknown")
			.flatMap(value -> Stream.of(
					argumentSet("responseFormat '" + value + "'",
							ImageOptionsBuilder.builder().responseFormat(value).build(), RESPONSE_FORMAT_MESSAGE),
					argumentSet("style '" + value + "'", ImageOptionsBuilder.builder().style(value).build(),
							STYLE_MESSAGE)));
	}

	private OpenRouterImageModel modelWithUnusedTransport(OpenRouterApi api) {
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model(MODEL).quality("high").build())
			.build();
	}

	@ParameterizedTest
	@MethodSource("unsupportedPortableOptions")
	void rejectsUnsupportedPortableOptionsOnBlockingCallsBeforeTransport(ImageOptions options, String message) {
		OpenRouterApi api = org.mockito.Mockito.mock(OpenRouterApi.class);
		OpenRouterImageModel model = modelWithUnusedTransport(api);
		ImagePrompt prompt = new ImagePrompt("a blue cube", options);

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
		org.mockito.Mockito.verifyNoInteractions(api);
	}

	@ParameterizedTest
	@MethodSource("unsupportedPortableOptions")
	void rejectsUnsupportedPortableOptionsOnStreamsBeforeTransport(ImageOptions options, String message) {
		OpenRouterApi api = org.mockito.Mockito.mock(OpenRouterApi.class);
		OpenRouterImageModel model = modelWithUnusedTransport(api);
		ImagePrompt prompt = new ImagePrompt("a blue cube", options);

		assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
		org.mockito.Mockito.verifyNoInteractions(api);
	}

	@ParameterizedTest
	@MethodSource("unsupportedPortableOptions")
	void fromOptionsRejectsUnsupportedPortableOptions(ImageOptions options, String message) {
		assertThatThrownBy(() -> OpenRouterImageOptions.fromOptions(options))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
	}

	static Stream<Arguments> streamsEndingWithoutACompletedImage() {
		return Stream.of(Arguments.of("", 0, "no events"),
				Arguments.of("data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"AQID\"}\n\n", 1,
						"only a partial image"),
				Arguments.of("data: {\"type\":\"synthetic.metadata\",\"usage\":{\"total_tokens\":3}}\n\n", 0,
						"only an unknown event"),
				Arguments.of("data: {\"type\":\"image_generation.partial_image\",\"partial_image_index\":0}\n\n", 1,
						"a partial image without content"));
	}

	@ParameterizedTest(name = "{2}")
	@MethodSource("streamsEndingWithoutACompletedImage")
	void rejectsDoneWithoutCompletedImageAndRecordsObservationError(String prefix, int emitted, String scenario) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterImageModel model = streamingModel(new AtomicReference<>(prefix + DONE), registry);
		StepVerifier.create(model.stream(new ImagePrompt("synthetic image")))
			.expectNextCount(emitted)
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
				+ DONE;
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
		body.set(DONE);
		StepVerifier.create(stream).expectError(OpenRouterTruncatedResponseException.class).verify();
	}

	static Stream<Arguments> failuresAfterACompletedImage() {
		return Stream.of(
				Arguments.of(Named.of("stream ends without [DONE]", ""), OpenRouterTruncatedResponseException.class),
				Arguments.of(Named.of("provider error event follows",
						"data: {\"type\":\"error\",\"error\":{\"code\":500,\"message\":\"synthetic failure\"}}\n\n"),
						OpenRouterApiException.class));
	}

	@ParameterizedTest
	@MethodSource("failuresAfterACompletedImage")
	void completedImageDoesNotHideLaterFailure(String trailer, Class<? extends Throwable> expected) {
		String body = "data: {\"type\":\"image_generation.completed\",\"b64_json\":\"AQID\"}\n\n" + trailer;
		StepVerifier
			.create(streamingModel(new AtomicReference<>(body), TestObservationRegistry.create())
				.stream(new ImagePrompt("synthetic image")))
			.expectNextCount(1)
			.expectError(expected)
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
