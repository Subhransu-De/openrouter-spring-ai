package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenRouterModelHttpRetryTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	private static final String CHAT_COMPLETIONS = BASE_URL + "/chat/completions";

	private static final String CHAT_SUCCESS = """
			{"id":"gen-1","object":"chat.completion","model":"openai/gpt-5.4-mini",
			 "choices":[{"index":0,"message":{"role":"assistant","content":"recovered"},"finish_reason":"stop"}]}
			""";

	private static final String RESPONSES_SUCCESS = """
			{"id":"resp-1","object":"response","model":"openai/gpt-5.4","status":"completed",
			 "output":[{"type":"message","role":"assistant",
			   "content":[{"type":"output_text","text":"recovered"}]}]}
			""";

	private static final String EMBEDDINGS_SUCCESS = """
			{"object":"list","model":"openai/text-embedding-3-small",
			 "data":[{"object":"embedding","index":0,"embedding":[0.25,-0.5]}]}
			""";

	private static final String IMAGES_SUCCESS = """
			{"created":123,"data":[{"b64_json":"aW1hZ2U=","media_type":"image/png"}]}
			""";

	@Test
	void retriesChatCompletionAfter429AndReturnsSuccess() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
				.body(error(429, "rate_limit_exceeded")));
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withSuccess(CHAT_SUCCESS, MediaType.APPLICATION_JSON));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(fixture.api())
			.retryTemplate(retryTemplate(1))
			.build();

		var response = model.call(chatPrompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("recovered");
		fixture.server().verify();
	}

	@Test
	void retriesResponsesAfter502AndReturnsSuccess() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/responses"))
			.andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
				.body(error(502, "provider_unavailable")));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/responses"))
			.andRespond(withSuccess(RESPONSES_SUCCESS, MediaType.APPLICATION_JSON));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(fixture.api())
			.retryTemplate(retryTemplate(1))
			.build();

		var response = model.call(chatPrompt(OpenRouterRequestMode.OPENAI_RESPONSES));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("recovered");
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "server_error", "rate_limit_exceeded", "timeout" })
	void defaultPolicyRetriesTransientInBandResponsesFailure(String code) {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/responses"))
			.andRespond(withSuccess(failedResponse(code), MediaType.APPLICATION_JSON));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/responses"))
			.andRespond(withSuccess(RESPONSES_SUCCESS, MediaType.APPLICATION_JSON));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(fixture.api()).build();

		assertThat(model.call(chatPrompt(OpenRouterRequestMode.OPENAI_RESPONSES)).getResult().getOutput().getText())
			.isEqualTo("recovered");
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "invalid_api_key", "invalid_request", "payment_required", "refusal",
			"unsupported_parameter", "future_unknown_error" })
	void defaultPolicyDoesNotRetryPermanentInBandResponsesFailure(String code) {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/responses"))
			.andRespond(withSuccess(failedResponse(code), MediaType.APPLICATION_JSON));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(fixture.api()).build();

		assertThatThrownBy(() -> model.call(chatPrompt(OpenRouterRequestMode.OPENAI_RESPONSES)))
			.isInstanceOf(OpenRouterNonTransientApiException.class);
		fixture.server().verify();
	}

	private String failedResponse(String code) {
		return """
				{"status":"failed","error":{"code":"%s","message":"synthetic failure"}}
				""".formatted(code);
	}

	@Test
	void retriesEmbeddingAfter503AndReturnsSuccess() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
				.body(error(503, "provider_overloaded")));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess(EMBEDDINGS_SUCCESS, MediaType.APPLICATION_JSON));
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(fixture.api())
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model("openai/text-embedding-3-small").build())
			.retryTemplate(retryTemplate(1))
			.build();

		var response = model.call(new EmbeddingRequest(List.of("hello"), null));

		assertThat(response.getResult().getOutput()).containsExactly(0.25f, -0.5f);
		fixture.server().verify();
	}

	@Test
	void retriesImageAfter503AndReturnsSuccess() {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/images"))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON)
				.body(error(503, "provider_overloaded")));
		fixture.server()
			.expect(once(), requestTo(BASE_URL + "/images"))
			.andRespond(withSuccess(IMAGES_SUCCESS, MediaType.APPLICATION_JSON));
		OpenRouterImageModel model = OpenRouterImageModel.builder()
			.openRouterApi(fixture.api())
			.defaultOptions(OpenRouterImageOptions.builder().model("openai/gpt-image-1").build())
			.retryTemplate(retryTemplate(1))
			.build();

		var response = model.call(new ImagePrompt("draw a circle"));

		assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2U=");
		fixture.server().verify();
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 402 })
	void deterministicClientFailureIsAttemptedOnlyOnce(int status) {
		Fixture fixture = fixture();
		fixture.server()
			.expect(once(), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON)
				.body(error(status, status == 402 ? "payment_required" : "invalid_request")));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(fixture.api())
			.retryTemplate(retryTemplate(5))
			.build();

		assertThatThrownBy(() -> model.call(chatPrompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)))
			.isInstanceOf(OpenRouterNonTransientApiException.class);
		fixture.server().verify();
	}

	@Test
	void configuredRetryExhaustionReturnsFinalSafeProviderFailure() {
		Fixture fixture = fixture("sk-or-final-secret");
		String body = """
				{"authorization":"Bearer sk-or-final-secret",
				 "error":{"code":503,"message":"provider still overloaded",
				   "metadata":{"error_type":"provider_overloaded","provider_code":"capacity"}}}
				""";
		fixture.server()
			.expect(times(3), requestTo(CHAT_COMPLETIONS))
			.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body(body));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(fixture.api())
			.retryTemplate(retryTemplate(2))
			.build();

		assertThatThrownBy(() -> model.call(chatPrompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)))
			.isInstanceOf(OpenRouterTransientApiException.class)
			.satisfies(thrown -> {
				OpenRouterHttpException exception = (OpenRouterHttpException) thrown;
				assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
				assertThat(exception.getEndpoint()).isEqualTo("/chat/completions");
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("provider_overloaded");
				assertThat(exception.getErrorDetails().providerCode()).isEqualTo("capacity");
				assertThat(exception.getResponseBody()).contains("provider still overloaded", "[REDACTED]")
					.doesNotContain("sk-or-final-secret");
				assertThat(exception.getMessage()).doesNotContain("sk-or-final-secret");
			});
		fixture.server().verify();
	}

	private Prompt chatPrompt(OpenRouterRequestMode mode) {
		return new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini").requestMode(mode).build());
	}

	private RetryTemplate retryTemplate(long maxRetries) {
		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(maxRetries)
			.includes(TransientAiException.class)
			.delay(Duration.ZERO)
			.build();
		return new RetryTemplate(policy);
	}

	private String error(int status, String errorType) {
		return """
				{"error":{"code":%d,"message":"provider failure",
				  "metadata":{"error_type":"%s"}}}
				""".formatted(status, errorType);
	}

	private Fixture fixture() {
		return fixture("test-key");
	}

	private Fixture fixture(String apiKey) {
		RestClient.Builder restClientBuilder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(restClientBuilder).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey(apiKey)
			.baseUrl(BASE_URL)
			.restClientBuilder(restClientBuilder)
			.build();
		return new Fixture(api, server);
	}

	private record Fixture(OpenRouterApi api, MockRestServiceServer server) {
	}

}
