package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;

class OpenRouterEmbeddingAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class));

	@Test
	void createsEmbeddingModelWhenApiKeyIsConfigured() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
			assertThat(context).hasSingleBean(EmbeddingModel.class);
			assertThat(context.getBean(EmbeddingModel.class)
				.getEmbeddingContent(new Document("synthetic passage", Map.of("topic", "synthetic-topic"))))
				.isEqualTo("synthetic passage");
		});
	}

	@Test
	void bindsEmbeddingOptionsFromProperties() {
		this.contextRunner
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.embedding.model=openai/text-embedding-3-small",
					"spring.ai.openrouter.embedding.dimensions=256",
					"spring.ai.openrouter.embedding.metadata-mode=embed",
					"spring.ai.openrouter.embedding.encoding-format=float",
					"spring.ai.openrouter.embedding.user=embed-user")
			.run(context -> {
				OpenRouterEmbeddingModel embeddingModel = context.getBean(OpenRouterEmbeddingModel.class);
				OpenRouterEmbeddingOptions options = (OpenRouterEmbeddingOptions) ReflectionTestUtils
					.getField(embeddingModel, "defaultOptions");
				assertThat(options.getModel()).isEqualTo("openai/text-embedding-3-small");
				assertThat(options.getDimensions()).isEqualTo(256);
				assertThat(embeddingModel.dimensions()).isEqualTo(256);
				assertThat(embeddingModel
					.getEmbeddingContent(new Document("synthetic passage", Map.of("topic", "synthetic-topic"))))
					.isEqualTo("topic: synthetic-topic\n\nsynthetic passage");
				assertThat(options.getEncodingFormat()).isEqualTo("float");
				assertThat(options.getUser()).isEqualTo("embed-user");
			});
	}

	@Test
	void disablesEmbeddingModelWhenAnotherProviderIsSelected() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.model.embedding=other")
			.run(context -> assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class));
	}

	@Test
	void backsOffWhenUserDefinesOwnEmbeddingModel() {
		this.contextRunner.withUserConfiguration(CustomEmbeddingModelConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class);
				assertThat(context).hasSingleBean(EmbeddingModel.class);
			});
	}

	@Test
	void injectsUserDefinedObservationRegistryIntoEmbeddingModel() {
		this.contextRunner.withUserConfiguration(ObservationRegistryConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterEmbeddingModel embeddingModel = context.getBean(OpenRouterEmbeddingModel.class);
				assertThat(ReflectionTestUtils.getField(embeddingModel, "observationRegistry"))
					.isSameAs(context.getBean(ObservationRegistry.class));
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class CustomEmbeddingModelConfiguration {

		@Bean
		EmbeddingModel customEmbeddingModel() {
			return new EmbeddingModel() {

				@Override
				public EmbeddingResponse call(EmbeddingRequest request) {
					throw new UnsupportedOperationException();
				}

				@Override
				public float[] embed(org.springframework.ai.document.Document document) {
					throw new UnsupportedOperationException();
				}
			};
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ObservationRegistryConfiguration {

		@Bean
		ObservationRegistry observationRegistry() {
			return ObservationRegistry.create();
		}

	}

}
