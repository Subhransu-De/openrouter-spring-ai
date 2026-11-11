package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
			.andExpect(jsonPath("$.provider.allow_fallbacks").value(false))
			.andExpect(jsonPath("$.provider.require_parameters").value(true))
			.andExpect(jsonPath("$.provider.data_collection").value("deny"))
			.andExpect(jsonPath("$.provider.order[0]").value("openai"))
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		EmbeddingsResponse response = fixture.api()
			.embeddings(new EmbeddingsRequest(MODEL, List.of("first", "second"), "float", 256, "user-1",
					new ProviderPreferences(false, true, "deny", List.of("openai"), null, null, null)));

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
			.andExpect(jsonPath("$.model").value(MODEL))
			.andExpect(jsonPath("$.input[0]").value("only"))
			.andExpect(jsonPath("$.encoding_format").doesNotExist())
			.andExpect(jsonPath("$.dimensions").doesNotExist())
			.andExpect(jsonPath("$.user").doesNotExist())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

		EmbeddingsResponse response = fixture.api()
			.embeddings(new EmbeddingsRequest(MODEL, List.of("only"), null, null, null, null));

		assertThat(response.data()).hasSize(2);
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

	static Stream<Arguments> failureStatuses() {
		return Stream.of(argumentSet("400", HttpStatus.BAD_REQUEST, OpenRouterNonTransientApiException.class),
				argumentSet("401", HttpStatus.UNAUTHORIZED, OpenRouterNonTransientApiException.class),
				argumentSet("402", HttpStatus.PAYMENT_REQUIRED, OpenRouterNonTransientApiException.class),
				argumentSet("404", HttpStatus.NOT_FOUND, OpenRouterNonTransientApiException.class),
				argumentSet("429", HttpStatus.TOO_MANY_REQUESTS, OpenRouterTransientApiException.class),
				argumentSet("500", HttpStatus.INTERNAL_SERVER_ERROR, OpenRouterTransientApiException.class),
				argumentSet("503", HttpStatus.SERVICE_UNAVAILABLE, OpenRouterTransientApiException.class));
	}

	@ParameterizedTest
	@MethodSource("failureStatuses")
	void surfacesErrorBodyOnFailure(HttpStatus status, Class<? extends OpenRouterHttpException> expected) {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(EMBEDDINGS))
			.andRespond(withStatus(status).body("{\"error\":\"synthetic failure\"}"));
		OpenRouterApi api = fixture.api();
		EmbeddingsRequest request = new EmbeddingsRequest(MODEL, List.of("x"), null, null, null, null);

		assertThatThrownBy(() -> api.embeddings(request)).isInstanceOf(expected)
			.hasMessageContaining("embeddings request failed")
			.satisfies(exception -> {
				OpenRouterHttpException failure = (OpenRouterHttpException) exception;
				assertThat(failure.getStatusCode().value()).isEqualTo(status.value());
				assertThat(failure.getResponseBody()).contains("synthetic failure");
			});
		fixture.server().verify();
	}

	static Stream<Arguments> emptyOrNullBodies() {
		return Stream.of(
				argumentSet("200 with an empty body", HttpStatus.OK, "", "Empty OpenRouter /embeddings response"),
				argumentSet("204 with an empty body", HttpStatus.NO_CONTENT, "",
						"Empty OpenRouter /embeddings response"),
				argumentSet("200 with a JSON null body", HttpStatus.OK, "null", "Null OpenRouter /embeddings response"),
				argumentSet("204 with a JSON null body", HttpStatus.NO_CONTENT, "null",
						"Null OpenRouter /embeddings response"));
	}

	@ParameterizedTest
	@MethodSource("emptyOrNullBodies")
	void rejectsEmptyAndNullSuccessBodiesAsProtocolErrors(HttpStatus status, String body, String message) {
		Fixture fixture = fixture();
		fixture.server().expect(once(), requestTo(EMBEDDINGS)).andRespond(withStatus(status).body(body));
		OpenRouterApi api = fixture.api();
		EmbeddingsRequest request = new EmbeddingsRequest(MODEL, List.of("x"), null, null, null, null);

		assertThatThrownBy(() -> api.embeddings(request)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(message);
		fixture.server().verify();
	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
