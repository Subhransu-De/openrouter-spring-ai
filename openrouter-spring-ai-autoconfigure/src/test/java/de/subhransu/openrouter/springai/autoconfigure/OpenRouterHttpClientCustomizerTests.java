package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class OpenRouterHttpClientCustomizerTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=synthetic-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
		.withPropertyValues(API_KEY, "spring.ai.openrouter.base-url=https://openrouter.invalid/api/v1");

	@Test
	void restClientCustomizersRunInOrderOnTheOpenRouterClientOnly() {
		AtomicReference<HttpHeaders> providerHeaders = new AtomicReference<>();
		AtomicReference<HttpHeaders> applicationHeaders = new AtomicReference<>();
		RestClient.Builder application = RestClient.builder()
			.requestFactory(RecordingTransport.headerCapturingFactory(applicationHeaders));
		this.contextRunner.withUserConfiguration(RestClientCustomizers.class)
			.withBean(RestClient.Builder.class, () -> application)
			.withBean(ClientHttpRequestFactoryBuilder.class,
					() -> settings -> RecordingTransport.headerCapturingFactory(providerHeaders))
			.run(context -> {
				assertThat(context).hasNotFailed();
				context.getBean(OpenRouterApi.class)
					.embeddings(new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null));
				assertThat(providerHeaders.get().get("X-Customized")).containsExactly("first", "second");
				// Credentials are installed after the customizers run.
				assertThat(providerHeaders.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer synthetic-key");

				application.build().get().uri("https://unrelated.invalid/resource").retrieve().toBodilessEntity();
				assertThat(applicationHeaders.get().headerNames()).doesNotContain("X-Customized",
						HttpHeaders.AUTHORIZATION);
			});
	}

	@Test
	void webClientCustomizersApplyToTheOpenRouterClientOnly() {
		AtomicReference<ClientRequest> providerRequest = new AtomicReference<>();
		AtomicReference<ClientRequest> applicationRequest = new AtomicReference<>();
		WebClient.Builder application = WebClient.builder().exchangeFunction(request -> {
			(request.url().getHost().equals("openrouter.invalid") ? providerRequest : applicationRequest).set(request);
			return Mono.just(org.springframework.web.reactive.function.client.ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
				.body("{\"data\":[{\"b64_json\":\"aW1n\"}]}")
				.build());
		});
		this.contextRunner
			.withBean(OpenRouterWebClientCustomizer.class,
					() -> builder -> builder.defaultHeader("X-Customized", "stream")
						.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer replaced"))
			.withBean(WebClient.Builder.class, () -> application)
			.run(context -> {
				assertThat(context).hasNotFailed();
				var images = new ImagesRequest("synthetic", "synthetic", null, null, null, null, null, null, null, null,
						null, true, null, null);
				// The stream is finite and in memory; the bound only keeps a broken
				// stream from hanging the build.
				assertThat(context.getBean(OpenRouterApi.class).imagesStream(images).blockLast(Duration.ofSeconds(5)))
					.isNotNull();
				assertThat(providerRequest.get().headers().getFirst("X-Customized")).isEqualTo("stream");
				assertThat(providerRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
					.isEqualTo("Bearer synthetic-key");

				application.build()
					.get()
					.uri("https://unrelated.invalid/resource")
					.retrieve()
					.toBodilessEntity()
					.block(Duration.ofSeconds(5));
				assertThat(applicationRequest.get().headers().headerNames()).doesNotContain("X-Customized",
						HttpHeaders.AUTHORIZATION);
			});
	}

	// The customizer runs when the auto-configured API is built, so counting its calls
	// separates "never asked" from "asked and ignored": the same customizer runs once
	// for an auto-configured API and never when the application owns the API.
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "an auto-configured API, false, 1", "an application-owned API, true, 0" })
	void customizersRunOnlyWhenAutoConfigurationBuildsTheApi(String description, boolean applicationOwnedApi,
			int expectedRuns) {
		AtomicInteger runs = new AtomicInteger();
		ApplicationContextRunner runner = this.contextRunner.withBean(OpenRouterRestClientCustomizer.class,
				() -> builder -> runs.incrementAndGet());
		if (applicationOwnedApi) {
			runner = runner.withBean(OpenRouterApi.class, () -> OpenRouterApi.builder().apiKey("user-key").build());
		}
		runner.run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(OpenRouterApi.class);
			assertThat(runs).hasValue(expectedRuns);
		});
	}

	@Configuration(proxyBeanMethods = false)
	static class RestClientCustomizers {

		@Bean
		@Order(2)
		OpenRouterRestClientCustomizer secondCustomizer() {
			return builder -> builder.requestInterceptor((request, body, execution) -> {
				request.getHeaders().add("X-Customized", "second");
				return execution.execute(request, body);
			});
		}

		@Bean
		@Order(1)
		OpenRouterRestClientCustomizer firstCustomizer() {
			return builder -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer replaced")
				.requestInterceptor((request, body, execution) -> {
					request.getHeaders().add("X-Customized", "first");
					return execution.execute(request, body);
				});
		}

	}

}
