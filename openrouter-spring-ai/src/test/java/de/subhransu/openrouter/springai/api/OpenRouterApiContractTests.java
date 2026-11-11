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

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
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
 * Wire-contract tests for the blocking {@link OpenRouterApi} surface. These pin the HTTP
 * method, endpoint path, headers, content type, and serialized JSON field names so a
 * regression in any of them fails a test rather than silently shipping. Streaming
 * contract coverage lives in {@link OpenRouterApiStreamingContractTests}.
 */
class OpenRouterApiContractTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String CHAT_COMPLETIONS = BASE_URL + "/chat/completions";

	private static final String RESPONSES = BASE_URL + "/responses";

	private static final String UPSTREAM_SAID_NO = "upstream said no";

	private static final String UPSTREAM_SAID_NO_BODY = "{\"error\":{\"code\":%d,\"message\":\"upstream said no\"}}";

	private static final String CATEGORIES_HEADER = "X-OpenRouter-Categories";

	private static final String CHAT_SUCCESS_BODY = """
			{
			  "id": "gen-1",
			  "object": "chat.completion",
			  "created": 123,
			  "model": "openai/gpt-5.4-mini",
			  "choices": [
			    {"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}
			  ],
			  "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}
			}
			""";

	private static final String RESPONSES_SUCCESS_BODY = """
			{
			  "id": "resp-1",
			  "object": "response",
			  "created_at": 123,
			  "model": "openai/gpt-5.4",
			  "status": "completed",
			  "output": [
			    {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "ok"}]}
			  ],
			  "usage": {"input_tokens": 1, "output_tokens": 1, "total_tokens": 2}
			}
			""";

	private Fixture fixture(OpenRouterApi.Builder customizer) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = customizer.baseUrl(BASE_URL).restClientBuilder(restClientBuilder).build();
		return new Fixture(api, server);
	}

	private OpenRouterApi.Builder baseBuilder() {
		return OpenRouterApi.builder().apiKey("test-key");
	}

	private static ChatCompletionRequest minimalChatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(false)
			.build();
	}

	private static ResponsesRequest minimalResponsesRequest() {
		return ResponsesRequest.builder()
			.model("openai/gpt-5.4")
			.input("hello")
			.maxOutputTokens(128)
			.stream(false)
			.build();
	}

	// ---------------------------------------------------------------------
	// Endpoint, method, headers, content type
	// ---------------------------------------------------------------------

	@Test
	void chatCompletionPostsToChatCompletionsEndpointAsJson() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		assertThat(fixture.api().chatCompletion(minimalChatRequest()).id()).isEqualTo("gen-1");
		fixture.server().verify();
	}

	@Test
	void responsesPostsToResponsesEndpointAsJson() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(RESPONSES))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
			.andExpect(header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
			.andRespond(withSuccess(RESPONSES_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		assertThat(fixture.api().responses(minimalResponsesRequest()).id()).isEqualTo("resp-1");
		fixture.server().verify();
	}

	// ---------------------------------------------------------------------
	// Attribution headers
	// ---------------------------------------------------------------------

	@Test
	void sendsConfiguredAttributionHeaders() {
		Fixture fixture = fixture(baseBuilder().httpReferer("https://app.example")
			.applicationTitle("My App")
			.applicationCategories("translation,programming"));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(header("HTTP-Referer", "https://app.example"))
			.andExpect(header("X-OpenRouter-Title", "My App"))
			.andExpect(header(CATEGORIES_HEADER, "translation,programming"))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	@ParameterizedTest
	@MethodSource("absentAttributionConfigurations")
	void omitsAttributionHeadersWhenAbsentOrBlank(UnaryOperator<OpenRouterApi.Builder> configuration) {
		Fixture fixture = fixture(configuration.apply(baseBuilder()));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(headerDoesNotExist("HTTP-Referer"))
			.andExpect(headerDoesNotExist("X-OpenRouter-Title"))
			.andExpect(headerDoesNotExist(CATEGORIES_HEADER))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	static Stream<Arguments> absentAttributionConfigurations() {
		UnaryOperator<OpenRouterApi.Builder> absent = builder -> builder;
		UnaryOperator<OpenRouterApi.Builder> blank = builder -> builder.httpReferer("   ")
			.applicationTitle("")
			.applicationCategories("  \t ");
		return Stream.of(Arguments.of(Named.of("never configured", absent)),
				Arguments.of(Named.of("blank values", blank)));
	}

	// ---------------------------------------------------------------------
	// Request omission (the full wire shape is pinned by RequestBuilderTests)
	// ---------------------------------------------------------------------

	@Test
	void minimalChatRequestSerializesOnlyItsPopulatedFields() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(jsonPath("$.temperature").doesNotExist())
			.andExpect(jsonPath("$.tools").doesNotExist())
			.andExpect(jsonPath("$.provider").doesNotExist())
			.andExpect(jsonPath("$.reasoning").doesNotExist())
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	// ---------------------------------------------------------------------
	// Synchronous error handling
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("endpointsWithProviderErrors")
	void providerErrorsPreserveStatusBodyAndClassification(Endpoint endpoint, ProviderError error) {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + endpoint.path))
			.andRespond(withStatus(HttpStatus.valueOf(error.status)).contentType(MediaType.APPLICATION_JSON)
				.body(error.body));

		assertThatThrownBy(() -> endpoint.call(fixture.api())).isInstanceOf(error.type).satisfies(thrown -> {
			OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
			assertThat(ex.getStatusCode().value()).isEqualTo(error.status);
			assertThat(ex.getResponseBody()).isEqualTo(error.body);
			assertThat(ex.getEndpoint()).isEqualTo(endpoint.path);
			assertThat(ex.getMessage()).isEqualTo(
					"OpenRouter " + endpoint.path + " request failed with status " + HttpStatus.valueOf(error.status));
			assertThat(ex.getCategory()).isEqualTo(error.category);
			assertThat(ex.getErrorDetails().message()).isEqualTo(error.detailMessage);
		});
		fixture.server().verify();
	}

	static Stream<Arguments> endpointsWithProviderErrors() {
		return Stream.of(Endpoint.values())
			.flatMap(endpoint -> Stream.of(
					providerError(400, UPSTREAM_SAID_NO_BODY.formatted(400), OpenRouterNonTransientApiException.class,
							OpenRouterErrorCategory.INVALID_REQUEST, UPSTREAM_SAID_NO),
					providerError(401, UPSTREAM_SAID_NO_BODY.formatted(401), OpenRouterNonTransientApiException.class,
							OpenRouterErrorCategory.AUTHENTICATION, UPSTREAM_SAID_NO),
					providerError(402, UPSTREAM_SAID_NO_BODY.formatted(402), OpenRouterNonTransientApiException.class,
							OpenRouterErrorCategory.BILLING_CREDITS, UPSTREAM_SAID_NO),
					providerError(429, UPSTREAM_SAID_NO_BODY.formatted(429), OpenRouterTransientApiException.class,
							OpenRouterErrorCategory.RATE_LIMIT, UPSTREAM_SAID_NO),
					providerError(502, UPSTREAM_SAID_NO_BODY.formatted(502), OpenRouterTransientApiException.class,
							OpenRouterErrorCategory.PROVIDER_UNAVAILABLE, UPSTREAM_SAID_NO),
					providerError(503, UPSTREAM_SAID_NO_BODY.formatted(503), OpenRouterTransientApiException.class,
							OpenRouterErrorCategory.PROVIDER_UNAVAILABLE, UPSTREAM_SAID_NO),
					providerError(400, "{\"error\":{\"message\":\"bad request\"}}",
							OpenRouterNonTransientApiException.class, OpenRouterErrorCategory.INVALID_REQUEST,
							"bad request"))
				.map(error -> Arguments.of(Named.of(endpoint.name(), endpoint), error)));
	}

	@ParameterizedTest
	@MethodSource("endpointsWithUnstructuredProviderErrors")
	void unstructuredProviderErrorsStillCarryStatusBodyAndCategory(Endpoint endpoint, ProviderError error) {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(BASE_URL + endpoint.path))
			.andRespond(withStatus(HttpStatus.valueOf(error.status)).body(error.body));

		assertThatThrownBy(() -> endpoint.call(fixture.api())).isInstanceOf(error.type).satisfies(thrown -> {
			OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
			assertThat(ex.getStatusCode().value()).isEqualTo(error.status);
			assertThat(ex.getResponseBody()).isEqualTo(error.body);
			assertThat(ex.getMessage()).isEqualTo(
					"OpenRouter " + endpoint.path + " request failed with status " + HttpStatus.valueOf(error.status));
			assertThat(ex.getCategory()).isEqualTo(error.category);
			assertThat(ex.getErrorDetails().message()).isNull();
		});
		fixture.server().verify();
	}

	static Stream<Arguments> endpointsWithUnstructuredProviderErrors() {
		return Stream.of(Endpoint.values())
			.flatMap(endpoint -> Stream
				.of(Named.of("plain-text 502",
						new ProviderError(502, "provider unavailable", OpenRouterTransientApiException.class,
								OpenRouterErrorCategory.PROVIDER_UNAVAILABLE, null)),
						Named.of("empty 500 body",
								new ProviderError(500, "", OpenRouterTransientApiException.class,
										OpenRouterErrorCategory.PROVIDER_UNAVAILABLE, null)))
				.map(error -> Arguments.of(Named.of(endpoint.name(), endpoint), error)));
	}

	private static Named<ProviderError> providerError(int status, String body, Class<? extends Throwable> type,
			OpenRouterErrorCategory category, String detailMessage) {
		return Named.of("HTTP " + status + " " + category,
				new ProviderError(status, body, type, category, detailMessage));
	}

	@Test
	void longErrorBodyIsExcludedFromMessageAndBoundedInDiagnosticGetter() {
		Fixture fixture = fixture(baseBuilder());
		String longBody = "x".repeat(5000);
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body(longBody));

		assertThatThrownBy(() -> fixture.api().chatCompletion(minimalChatRequest()))
			.isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
				assertThat(ex.getMessage())
					.isEqualTo("OpenRouter /chat/completions request failed with status 429 TOO_MANY_REQUESTS");
				assertThat(ex.getResponseBody()).hasSize(1003).endsWith("...");
			});
		fixture.server().verify();
	}

	private static org.springframework.test.web.client.RequestMatcher headerDoesNotExist(String name) {
		return request -> assertThat(request.getHeaders().headerNames()).as("header %s must be absent", name)
			.doesNotContain(name);
	}

	private record ProviderError(int status, String body, Class<? extends Throwable> type,
			OpenRouterErrorCategory category, String detailMessage) {
	}

	private enum Endpoint {

		CHAT_COMPLETIONS("/chat/completions") {
			@Override
			void call(OpenRouterApi api) {
				api.chatCompletion(minimalChatRequest());
			}
		},
		RESPONSES("/responses") {
			@Override
			void call(OpenRouterApi api) {
				api.responses(minimalResponsesRequest());
			}
		};

		private final String path;

		Endpoint(String path) {
			this.path = path;
		}

		abstract void call(OpenRouterApi api);

	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
