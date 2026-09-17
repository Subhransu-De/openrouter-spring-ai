package de.subhransu.openrouter.springai.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import org.junit.jupiter.api.Test;
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

	private record Fixture(OpenRouterImageModel model, MockRestServiceServer server) {
	}

}
