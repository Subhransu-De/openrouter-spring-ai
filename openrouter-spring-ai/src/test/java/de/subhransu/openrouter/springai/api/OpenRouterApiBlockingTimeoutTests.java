package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * The blocking read timeout belongs to the caller's request factory, so every blocking
 * endpoint must hand a factory timeout back to the caller unchanged and must not retry
 * it. The factory here fails the way a real read timeout does, without waiting for one;
 * the operating system's socket timeout belongs to the JDK and Spring, not this library.
 */
class OpenRouterApiBlockingTimeoutTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	@ParameterizedTest
	@EnumSource(Endpoint.class)
	void factoryReadTimeoutReachesTheCallerFromEveryBlockingEndpointWithoutARetry(Endpoint endpoint) {
		AtomicInteger attempts = new AtomicInteger();
		RestClient.Builder rest = RestClient.builder().requestFactory((uri, method) -> {
			assertThat(uri.getPath()).as("endpoint path").endsWith(endpoint.path);
			return new MockClientHttpRequest(method, uri) {
				@Override
				protected ClientHttpResponse executeInternal() throws IOException {
					attempts.incrementAndGet();
					throw new SocketTimeoutException("Read timed out");
				}
			};
		});
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.baseUrl("https://synthetic.invalid")
			.restClientBuilder(rest)
			.timeout(Duration.ofSeconds(30))
			.build();

		assertThatThrownBy(() -> endpoint.invocation.accept(api)).isInstanceOf(ResourceAccessException.class)
			.hasRootCauseInstanceOf(SocketTimeoutException.class)
			.rootCause()
			.hasMessage("Read timed out");
		assertThat(attempts).hasValue(1);
	}

	private enum Endpoint {

		CHAT("/chat/completions", api -> api.chatCompletion(JSON.readValue("{}", ChatCompletionRequest.class))),

		RESPONSES("/responses", api -> api.responses(JSON.readValue("{}", ResponsesRequest.class))),

		EMBEDDINGS("/embeddings", api -> api.embeddings(JSON.readValue("{}", EmbeddingsRequest.class))),

		IMAGES("/images", api -> api.images(JSON.readValue("{}", ImagesRequest.class)));

		private final String path;

		private final Consumer<OpenRouterApi> invocation;

		Endpoint(String path, Consumer<OpenRouterApi> invocation) {
			this.path = path;
			this.invocation = invocation;
		}

	}

}
