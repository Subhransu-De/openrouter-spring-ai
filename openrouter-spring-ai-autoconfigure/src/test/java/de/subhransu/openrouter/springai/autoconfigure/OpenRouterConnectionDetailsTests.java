package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

@Tag("configuration")
@Tag("configuration.beans")
class OpenRouterConnectionDetailsTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=property-key";

	private final AtomicReference<URI> uri = new AtomicReference<>();

	private final AtomicReference<HttpHeaders> headers = new AtomicReference<>();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withBean(ClientHttpRequestFactoryBuilder.class, () -> settings -> (uri, method) -> {
			this.uri.set(uri);
			return new MockClientHttpRequest(method, uri) {
				@Override
				protected MockClientHttpResponse executeInternal() {
					OpenRouterConnectionDetailsTests.this.headers.set(new HttpHeaders(getHeaders()));
					return new MockClientHttpResponse("{\"data\":[]}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
				}
			};
		});

	@Test
	void thePropertiesBackTheDefaultConnectionDetails() {
		this.runner.withPropertyValues(API_KEY, "spring.ai.openrouter.base-url=https://property.invalid/api/v1")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterConnectionDetails details = context.getBean(OpenRouterConnectionDetails.class);
				assertThat(details.getApiKey()).isEqualTo("property-key");
				assertThat(details.getBaseUrl()).isEqualTo("https://property.invalid/api/v1");
				embed(context.getBean(OpenRouterApi.class));
				assertThat(this.uri.get()).hasToString("https://property.invalid/api/v1/embeddings");
				assertThat(this.headers.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer property-key");
			});
	}

	@Test
	void theDefaultBaseUrlAppliesWhenNoneIsConfigured() {
		this.runner.withPropertyValues(API_KEY).run(context -> {
			assertThat(context.getBean(OpenRouterConnectionDetails.class).getBaseUrl())
				.isEqualTo(OpenRouterApi.DEFAULT_BASE_URL);
			embed(context.getBean(OpenRouterApi.class));
			assertThat(this.uri.get()).hasToString(OpenRouterApi.DEFAULT_BASE_URL + "/embeddings");
		});
	}

	@Test
	void aConnectionDetailsBeanSupersedesTheProperties() {
		this.runner
			.withBean(OpenRouterConnectionDetails.class, () -> details("https://bean.invalid/api/v1", "bean-key"))
			.withPropertyValues(API_KEY, "spring.ai.openrouter.base-url=https://property.invalid/api/v1")
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(OpenRouterConnectionDetails.class);
				embed(context.getBean(OpenRouterApi.class));
				assertThat(this.uri.get()).hasToString("https://bean.invalid/api/v1/embeddings");
				assertThat(this.headers.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer bean-key");
			});
	}

	@Test
	void aConnectionDetailsBeanNeedsNoApiKeyProperty() {
		this.runner.withBean(OpenRouterConnectionDetails.class, () -> details(null, "bean-key")).run(context -> {
			assertThat(context).hasNotFailed();
			embed(context.getBean(OpenRouterApi.class));
			assertThat(this.uri.get()).hasToString(OpenRouterApi.DEFAULT_BASE_URL + "/embeddings");
			assertThat(this.headers.get().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer bean-key");
		});
	}

	@Test
	void aConnectionDetailsBeanWithoutAKeyFailsStartup() {
		this.runner.withBean(OpenRouterConnectionDetails.class, () -> details("https://bean.invalid/api/v1", ""))
			.withPropertyValues(API_KEY)
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessage("OpenRouter API key must not be empty"));
	}

	@Test
	void noConnectionDetailsWhileNoModelTypeSelectsOpenRouter() {
		this.runner
			.withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none")
			.run(context -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(OpenRouterConnectionDetails.class)
				.doesNotHaveBean(OpenRouterApi.class));
	}

	@Tag("configuration.properties")
	@Test
	void customHeadersBindToTheChatDefaults() {
		this.runner.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.custom-headers.X-Request-Tag=default")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterChatOptions options = context.getBean(OpenRouterChatModel.class).getOptions();
				assertThat(options.getCustomHeaders()).containsExactlyEntriesOf(Map.of("X-Request-Tag", "default"));
			});
		this.runner.withPropertyValues(API_KEY, "spring.ai.openrouter.chat.custom-headers.Authorization=Bearer other")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("must not set Authorization")
				.hasMessageNotContaining("Bearer other"));
	}

	private static void embed(OpenRouterApi api) {
		api.embeddings(new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null));
	}

	private static OpenRouterConnectionDetails details(@Nullable String baseUrl, String apiKey) {
		return new OpenRouterConnectionDetails() {

			@Override
			public @Nullable String getBaseUrl() {
				return baseUrl;
			}

			@Override
			public String getApiKey() {
				return apiKey;
			}

		};
	}

}
