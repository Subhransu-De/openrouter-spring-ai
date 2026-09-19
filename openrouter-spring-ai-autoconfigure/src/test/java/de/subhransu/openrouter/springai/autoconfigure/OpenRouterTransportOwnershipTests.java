package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class OpenRouterTransportOwnershipTests {

	@ParameterizedTest
	@ValueSource(strings = { "15s", "45s" })
	void composesTimeoutsWithoutChangingApplicationSingletonBuilder(String timeout) {
		AtomicReference<HttpHeaders> unrelatedHeaders = new AtomicReference<>();
		AtomicReference<HttpHeaders> providerHeaders = new AtomicReference<>();
		AtomicReference<HttpClientSettings> settings = new AtomicReference<>();
		RestClient.Builder supplied = RestClient.builder()
			.baseUrl("https://unrelated.invalid")
			.defaultHeader("X-Synthetic", "shared")
			.requestFactory(factory(unrelatedHeaders));
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
			.withBean(RestClient.Builder.class, () -> supplied)
			.withBean(HttpClientSettings.class,
					() -> HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(3)))
			.withBean(ClientHttpRequestFactoryBuilder.class, () -> configured -> {
				settings.set(configured);
				return factory(providerHeaders);
			})
			.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key",
					"spring.ai.openrouter.base-url=https://openrouter.invalid/api/v1",
					"spring.ai.openrouter.app.title=Synthetic", "spring.ai.openrouter.connection.timeout=" + timeout)
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterApi.class)
					.embeddings(new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null));
				assertThat(providerHeaders.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-key");
				assertThat(providerHeaders.get().getFirst("X-Synthetic")).isEqualTo("shared");
				assertThat(settings.get())
					.extracting(HttpClientSettings::connectTimeout, HttpClientSettings::readTimeout)
					.containsExactly(Duration.parse("PT" + timeout), Duration.parse("PT" + timeout));
				assertThat(context.getBean(HttpClientSettings.class).readTimeout()).isEqualTo(Duration.ofSeconds(3));

				supplied.build().get().uri("/resource").retrieve().toBodilessEntity();
				assertThat(unrelatedHeaders.get()).isNotNull();
				assertThat(unrelatedHeaders.get().headerNames()).doesNotContain(HttpHeaders.AUTHORIZATION,
						"HTTP-Referer", "X-OpenRouter-Title", "X-OpenRouter-Categories");
				assertThat(unrelatedHeaders.get().getFirst("X-Synthetic")).isEqualTo("shared");
			});
	}

	private ClientHttpRequestFactory factory(AtomicReference<HttpHeaders> headers) {
		return (uri, method) -> new MockClientHttpRequest(method, uri) {
			@Override
			protected ClientHttpResponse executeInternal() {
				headers.set(new HttpHeaders(getHeaders()));
				return new MockClientHttpResponse("{\"data\":[]}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
			}
		};
	}

}
