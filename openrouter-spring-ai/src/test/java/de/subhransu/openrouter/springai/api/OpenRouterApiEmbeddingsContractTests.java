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

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Wire-contract tests for {@code POST /embeddings}: endpoint path, method, headers and
 * serialized JSON field names are pinned so regressions fail a test instead of silently
 * shipping.
 */
class OpenRouterApiEmbeddingsContractTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String EMBEDDINGS = BASE_URL + "/embeddings";

	private static final String MODEL = "openai/text-embedding-3-small";

	private static final String SUCCESS_BODY = """
			{
			  "object": "list",
			  "data": [
			    {"object": "embedding", "index": 0, "embedding": [0.1, -0.2, 0.3]},
			    {"object": "embedding", "index": 1, "embedding": [0.4, 0.5, -0.6]}
			  ],
			  "model": "openai/text-embedding-3-small",
			  "usage": {"prompt_tokens": 7, "total_tokens": 7}
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
	void postsSnakeCaseFieldsToEmbeddingsEndpoint() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(EMBEDDINGS))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.input[0]").value("first"))
			.andExpect(jsonPath("$.input[1]").value("second"))
			.andExpect(jsonPath("$.encoding_format").value("float"))
			.andExpect(jsonPath("$.dimensions").value(256))
			.andExpect(jsonPath("$.user").value("user-1"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		EmbeddingsResponse response = fixture.api()
			.embeddings(new EmbeddingsRequest(MODEL, List.of("first", "second"), "float", 256, "user-1", null));

		assertThat(response.data()).hasSize(2);
		assertThat(response.data().get(0).embedding()).containsExactly(0.1f, -0.2f, 0.3f);
		assertThat(response.data().get(1).index()).isEqualTo(1);
		assertThat(response.model()).isEqualTo(MODEL);
		assertThat(response.usage().promptTokens()).isEqualTo(7);
		assertThat(response.usage().totalTokens()).isEqualTo(7);
		fixture.server().verify();
	}

	@Test
	void omitsOptionalFieldsWhenUnset() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(EMBEDDINGS))
			.andExpect(jsonPath("$.encoding_format").doesNotExist())
			.andExpect(jsonPath("$.dimensions").doesNotExist())
			.andExpect(jsonPath("$.user").doesNotExist())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().embeddings(new EmbeddingsRequest(MODEL, List.of("only"), null, null, null, null));

		fixture.server().verify();
	}

	@Test
	void preservesInBandErrorAndToleratesUnknownFields() {
		Fixture fixture = fixture();
		fixture.server().expect(once(), requestTo(EMBEDDINGS)).andRespond(withSuccess("""
				{"error":{"code":429,"message":"synthetic rate limit","future_error_field":true},
				 "future_field":true}
				""", MediaType.APPLICATION_JSON));

		EmbeddingsResponse response = fixture.api()
			.embeddings(new EmbeddingsRequest(MODEL, List.of("hello"), null, null, null, null));

		assertThat(response.error().code()).isEqualTo("429");
		assertThat(response.error().message()).isEqualTo("synthetic rate limit");
		assertThat(response.data()).isNull();
		fixture.server().verify();
	}

	@Test
	void surfacesErrorBodyOnFailure() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(EMBEDDINGS))
			.andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body("{\"error\":\"insufficient credits\"}"));
		OpenRouterApi api = fixture.api();
		EmbeddingsRequest request = new EmbeddingsRequest(MODEL, List.of("x"), null, null, null, null);

		assertThatThrownBy(() -> api.embeddings(request)).isInstanceOf(OpenRouterNonTransientApiException.class)
			.hasMessageContaining("embeddings request failed")
			.satisfies(exception -> assertThat(((OpenRouterHttpException) exception).getResponseBody())
				.contains("insufficient credits"));
	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
