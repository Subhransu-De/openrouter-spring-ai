package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.api.dto.ReasoningOptions;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.UsageConfig;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

	private ChatCompletionRequest minimalChatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(false)
			.build();
	}

	private ResponsesRequest minimalResponsesRequest() {
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

	@Test
	void omitsAttributionHeadersWhenAbsent() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(headerDoesNotExist("HTTP-Referer"))
			.andExpect(headerDoesNotExist("X-OpenRouter-Title"))
			.andExpect(headerDoesNotExist(CATEGORIES_HEADER))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	@Test
	void omitsAttributionHeadersWhenBlank() {
		Fixture fixture = fixture(baseBuilder().httpReferer("   ").applicationTitle("").applicationCategories("  \t "));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(headerDoesNotExist("HTTP-Referer"))
			.andExpect(headerDoesNotExist("X-OpenRouter-Title"))
			.andExpect(headerDoesNotExist(CATEGORIES_HEADER))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	@ParameterizedTest(name = "categories \"{0}\" forwarded verbatim")
	@ValueSource(strings = { "programming", "translation,programming", "a,b,c" })
	void forwardsCategoriesVerbatim(String categories) {
		Fixture fixture = fixture(baseBuilder().applicationCategories(categories));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andExpect(header(CATEGORIES_HEADER, categories))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(minimalChatRequest());
		fixture.server().verify();
	}

	// ---------------------------------------------------------------------
	// Serialized JSON wire names (camelCase regression guards)
	// ---------------------------------------------------------------------

	@Test
	void serializesFullChatRequestWithOpenRouterWireNames() {
		ChatCompletionRequest request = ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.models(List.of("anthropic/claude-3.5-sonnet", "openai/gpt-5.4"))
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.temperature(0.7)
			.topP(0.9)
			.topK(40)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.repetitionPenalty(1.1)
			.minP(0.05)
			.topA(0.8)
			.maxTokens(256)
			.maxCompletionTokens(512)
			.stop(List.of("STOP"))
			.seed(42)
			.user("user-7")
			.stream(false)
			.responseFormat(Map.of("type", "json_object"))
			.toolChoice(Map.of("type", "auto"))
			.parallelToolCalls(true)
			.provider(new ProviderPreferences(true, false, "deny", List.of("openai"), List.of("anthropic"),
					List.of("fp16"), "throughput"))
			.reasoning(new ReasoningOptions("high", 1024, false, true))
			.serviceTier("flex")
			.metadata(Map.of("trace", "abc"))
			.route("fallback")
			.usage(new UsageConfig(true))
			.modalities(List.of("image", "text"))
			.imageConfig(Map.of("aspect_ratio", "16:9"))
			.build();
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			// Snake-case wire names, not Java camelCase
			.andExpect(jsonPath("$.top_p").value(0.9))
			.andExpect(jsonPath("$.top_k").value(40))
			.andExpect(jsonPath("$.frequency_penalty").value(0.1))
			.andExpect(jsonPath("$.presence_penalty").value(0.2))
			.andExpect(jsonPath("$.repetition_penalty").value(1.1))
			.andExpect(jsonPath("$.min_p").value(0.05))
			.andExpect(jsonPath("$.top_a").value(0.8))
			.andExpect(jsonPath("$.max_tokens").value(256))
			.andExpect(jsonPath("$.max_completion_tokens").value(512))
			.andExpect(jsonPath("$.response_format.type").value("json_object"))
			.andExpect(jsonPath("$.tool_choice.type").value("auto"))
			.andExpect(jsonPath("$.parallel_tool_calls").value(true))
			.andExpect(jsonPath("$.service_tier").value("flex"))
			.andExpect(jsonPath("$.usage.include").value(true))
			.andExpect(jsonPath("$.provider.allow_fallbacks").value(true))
			.andExpect(jsonPath("$.provider.require_parameters").value(false))
			.andExpect(jsonPath("$.provider.data_collection").value("deny"))
			.andExpect(jsonPath("$.provider.order[0]").value("openai"))
			.andExpect(jsonPath("$.provider.ignore[0]").value("anthropic"))
			.andExpect(jsonPath("$.provider.quantizations[0]").value("fp16"))
			.andExpect(jsonPath("$.provider.sort").value("throughput"))
			.andExpect(jsonPath("$.models[0]").value("anthropic/claude-3.5-sonnet"))
			.andExpect(jsonPath("$.metadata.trace").value("abc"))
			.andExpect(jsonPath("$.route").value("fallback"))
			.andExpect(jsonPath("$.seed").value(42))
			.andExpect(jsonPath("$.user").value("user-7"))
			.andExpect(jsonPath("$.modalities[0]").value("image"))
			.andExpect(jsonPath("$.modalities[1]").value("text"))
			.andExpect(jsonPath("$.image_config.aspect_ratio").value("16:9"))
			// camelCase forms must never appear on the wire
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("topP"))))
			.andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("maxTokens"))))
			.andExpect(content()
				.string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("parallelToolCalls"))))
			.andRespond(withSuccess(CHAT_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().chatCompletion(request);
		fixture.server().verify();
	}

	@Test
	void omitsNullChatFieldsFromSerializedBody() {
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

	@Test
	void serializesResponsesRequestWithWireNames() {
		ResponsesRequest request = ResponsesRequest.builder()
			.model("openai/gpt-5.4")
			.input("hello")
			.instructions("be terse")
			.maxOutputTokens(256)
			.stream(false)
			.temperature(0.3)
			.topP(0.8)
			.topK(20)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.metadata(Map.of("trace", "xyz"))
			.provider(new ProviderPreferences(true, null, null, null, null, null, null))
			.reasoning(new ReasoningOptions("medium", null, null, null))
			.route("fallback")
			.serviceTier("priority")
			.user("user-1")
			.parallelToolCalls(true)
			.toolChoice(Map.of("type", "auto"))
			.modalities(List.of("image", "text"))
			.imageConfig(Map.of("aspect_ratio", "16:9"))
			.build();
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(RESPONSES))
			.andExpect(jsonPath("$.max_output_tokens").value(256))
			.andExpect(jsonPath("$.top_p").value(0.8))
			.andExpect(jsonPath("$.top_k").value(20))
			.andExpect(jsonPath("$.frequency_penalty").value(0.1))
			.andExpect(jsonPath("$.presence_penalty").value(0.2))
			.andExpect(jsonPath("$.service_tier").value("priority"))
			.andExpect(jsonPath("$.parallel_tool_calls").value(true))
			.andExpect(jsonPath("$.tool_choice.type").value("auto"))
			.andExpect(jsonPath("$.instructions").value("be terse"))
			.andExpect(jsonPath("$.modalities[0]").value("image"))
			.andExpect(jsonPath("$.image_config.aspect_ratio").value("16:9"))
			.andRespond(withSuccess(RESPONSES_SUCCESS_BODY, MediaType.APPLICATION_JSON));

		fixture.api().responses(request);
		fixture.server().verify();
	}

	// ---------------------------------------------------------------------
	// Synchronous error handling
	// ---------------------------------------------------------------------

	@ParameterizedTest(name = "chat completion HTTP {0} preserves classified status + body")
	@ValueSource(ints = { 400, 401, 402, 429, 502, 503 })
	void chatCompletionPreservesProviderErrorStatusAndBody(int status) {
		Fixture fixture = fixture(baseBuilder());
		String body = "{\"error\":{\"code\":" + status + ",\"message\":\"upstream said no\"}}";
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(body));

		assertThatThrownBy(() -> fixture.api().chatCompletion(minimalChatRequest()))
			.isInstanceOf(
					status >= 429 ? OpenRouterTransientApiException.class : OpenRouterNonTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
				assertThat(ex.getStatusCode().value()).isEqualTo(status);
				assertThat(ex.getResponseBody()).isEqualTo(body);
				assertThat(ex.getEndpoint()).isEqualTo("/chat/completions");
				assertThat(ex.getMessage())
					.isEqualTo("OpenRouter /chat/completions request failed with status " + HttpStatus.valueOf(status));
				assertThat(ex.getErrorDetails().message()).isEqualTo("upstream said no");
			});
		fixture.server().verify();
	}

	@Test
	void responsesPreservesClientErrorStatusAndBody() {
		Fixture fixture = fixture(baseBuilder());
		String body = "{\"error\":{\"message\":\"bad request\"}}";
		fixture.server()
			.expect(once(), requestTo(RESPONSES))
			.andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(body));

		assertThatThrownBy(() -> fixture.api().responses(minimalResponsesRequest()))
			.isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
				assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
				assertThat(ex.getResponseBody()).contains("bad request");
				assertThat(ex.getCategory()).isEqualTo(OpenRouterErrorCategory.INVALID_REQUEST);
			});
		fixture.server().verify();
	}

	@Test
	void responsesPreservesUpstreamErrorStatusAndBody() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(RESPONSES))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("provider unavailable"));

		assertThatThrownBy(() -> fixture.api().responses(minimalResponsesRequest()))
			.isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
				assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
				assertThat(ex.getResponseBody()).contains("provider unavailable");
			});
		fixture.server().verify();
	}

	@Test
	void emptyErrorBodyStillProducesUsefulException() {
		Fixture fixture = fixture(baseBuilder());
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body(""));

		assertThatThrownBy(() -> fixture.api().chatCompletion(minimalChatRequest()))
			.isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException ex = (OpenRouterHttpException) thrown;
				assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
				assertThat(ex.getMessage()).contains("500");
			});
		fixture.server().verify();
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

	@Test
	void errorMessageNeverLeaksApiKeyOrAuthorizationHeader() {
		Fixture fixture = fixture(baseBuilder().apiKey("sk-or-secret-key-value"));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":{\"message\":\"invalid key\"}}"));

		assertThatThrownBy(() -> fixture.api().chatCompletion(minimalChatRequest()))
			.isInstanceOf(OpenRouterNonTransientApiException.class)
			.satisfies(thrown -> {
				// The message is built by the library from status + body, so this pins
				// that no code path splices credentials into it. (The response body is
				// server-controlled -- the library cannot promise anything about it, so
				// it is deliberately not asserted here.)
				assertThat(thrown.getMessage()).doesNotContain("sk-or-secret-key-value");
				assertThat(thrown.getMessage()).doesNotContain("Bearer");
			});
		fixture.server().verify();
	}

	private static org.springframework.test.web.client.RequestMatcher headerDoesNotExist(String name) {
		return request -> assertThat(request.getHeaders().headerNames()).as("header %s must be absent", name)
			.doesNotContain(name);
	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
