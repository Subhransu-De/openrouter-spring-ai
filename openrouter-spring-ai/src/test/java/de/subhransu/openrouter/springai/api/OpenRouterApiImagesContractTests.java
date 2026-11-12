package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract tests for {@code POST /images}: endpoint path, method, headers and the
 * snake_case field names of OpenRouter's unified Image API are pinned here.
 */
@Tag("multimodal")
@Tag("multimodal.image-model")
class OpenRouterApiImagesContractTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String IMAGES = BASE_URL + "/images";

	private static final String MODEL = "bytedance-seed/seedream-4.5";

	private static final String SUCCESS_BODY = """
			{
			  "created": 1750000000,
			  "data": [
			    {"b64_json": "aW1hZ2Ux", "media_type": "image/png"},
			    {"b64_json": "aW1hZ2Uy", "media_type": "image/webp"}
			  ],
			  "usage": {"prompt_tokens": 0, "completion_tokens": 4160, "total_tokens": 4160, "cost": 0.03}
			}
			""";

	private Fixture fixture() {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder)
			.build();
		return new Fixture(api, server);
	}

	@Test
	void postsSnakeCaseFieldsToImagesEndpoint() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(IMAGES))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.prompt").value("a red panda wearing sunglasses"))
			.andExpect(jsonPath("$.n").value(2))
			.andExpect(jsonPath("$.size").value("2048x2048"))
			.andExpect(jsonPath("$.resolution").value("2K"))
			.andExpect(jsonPath("$.aspect_ratio").value("16:9"))
			.andExpect(jsonPath("$.quality").value("high"))
			.andExpect(jsonPath("$.output_format").value("webp"))
			.andExpect(jsonPath("$.background").value("transparent"))
			.andExpect(jsonPath("$.output_compression").value(80))
			.andExpect(jsonPath("$.seed").value(42))
			.andExpect(jsonPath("$.input_references[0].type").value("image_url"))
			.andExpect(jsonPath("$.input_references[0].image_url.url").value("https://example.test/reference.png"))
			.andExpect(jsonPath("$.provider.options.bytedance.watermark").value(false))
			.andExpect(jsonPath("$.stream").doesNotExist())
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		ImagesResponse response = fixture.api()
			.images(new ImagesRequest(MODEL, "a red panda wearing sunglasses", 2, "2048x2048", "2K", "16:9", "high",
					"webp", "transparent", 80, 42, null,
					List.of(ContentPart.image("https://example.test/reference.png")),
					Map.of("options", Map.of("bytedance", Map.of("watermark", false)))));

		assertThat(response.created()).isEqualTo(1750000000L);
		assertThat(response.data()).hasSize(2);
		assertThat(response.data().get(0).b64Json()).isEqualTo("aW1hZ2Ux");
		assertThat(response.data().get(1).mediaType()).isEqualTo("image/webp");
		assertThat(response.usage().completionTokens()).isEqualTo(4160);
		assertThat(response.usage().cost()).isEqualTo(0.03);
		fixture.server().verify();
	}

	@Test
	void omitsOptionalFieldsWhenUnset() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(IMAGES))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.prompt").value("minimal"))
			.andExpect(jsonPath("$.n").doesNotExist())
			.andExpect(jsonPath("$.size").doesNotExist())
			.andExpect(jsonPath("$.input_references").doesNotExist())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api()
			.images(new ImagesRequest(MODEL, "minimal", null, null, null, null, null, null, null, null, null, null,
					null, null));

		fixture.server().verify();
	}

	@Test
	void surfacesErrorBodyOnFailure() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(IMAGES))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":\"unsupported aspect_ratio\"}"));
		OpenRouterApi api = fixture.api();
		ImagesRequest request = new ImagesRequest(MODEL, "x", null, null, null, null, null, null, null, null, null,
				null, null, null);

		assertThatThrownBy(() -> api.images(request)).isInstanceOf(OpenRouterNonTransientApiException.class)
			.hasMessageContaining("images request failed")
			.satisfies(exception -> assertThat(((OpenRouterHttpException) exception).getResponseBody())
				.contains("unsupported aspect_ratio"));
	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
