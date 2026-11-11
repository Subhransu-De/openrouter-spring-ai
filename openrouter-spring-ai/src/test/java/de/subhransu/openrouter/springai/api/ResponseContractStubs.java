package de.subhransu.openrouter.springai.api;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Stub transports for the response contract tests: one canned blocking reply or one
 * canned SSE reply, with retries disabled so a failure surfaces on the first attempt.
 */
final class ResponseContractStubs {

	static final RetryTemplate NO_RETRY = new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());

	private ResponseContractStubs() {
	}

	static OpenRouterApi blocking(String path, String body, HttpStatus status) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://openrouter.test" + path))
			.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(builder)
			.build();
	}

	static OpenRouterApi streaming(String body) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
					.body("data: " + body + "\n\ndata: [DONE]\n\n")
					.build())))
			.build();
	}

	static OpenRouterChatModel chatModel(OpenRouterApi api, OpenRouterRequestMode mode,
			TestObservationRegistry registry) {
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().model("test-model").requestMode(mode).build())
			.retryTemplate(NO_RETRY)
			.observationRegistry(registry)
			.build();
	}

}
