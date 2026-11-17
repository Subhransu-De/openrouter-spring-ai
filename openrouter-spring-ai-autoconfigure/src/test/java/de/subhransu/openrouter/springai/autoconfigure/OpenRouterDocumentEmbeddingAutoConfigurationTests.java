package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.embedding.OpenRouterDocumentEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.DocumentEmbeddingModel;
import org.springframework.ai.embedding.DocumentEmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("configuration")
class OpenRouterDocumentEmbeddingAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private static final String SELECTED = "spring.ai.model.embedding.multimodal=openrouter";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
				OpenRouterEmbeddingAutoConfiguration.class, OpenRouterDocumentEmbeddingAutoConfiguration.class));

	@Tag("configuration.model-selection")
	@Test
	void createsNoDocumentEmbeddingModelUnlessSelected() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY).run(context -> {
			assertThat(context).doesNotHaveBean(DocumentEmbeddingModel.class);
			assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
		});
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.model.embedding.multimodal=other")
			.run(context -> assertThat(context).doesNotHaveBean(DocumentEmbeddingModel.class));
	}

	@Tag("configuration.model-selection")
	@Test
	void createsTheDocumentEmbeddingModelNextToTheTextModelWhenSelected() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, SELECTED).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterDocumentEmbeddingModel.class);
			assertThat(context).hasSingleBean(DocumentEmbeddingModel.class);
			assertThat(context).hasSingleBean(EmbeddingModel.class);
		});
	}

	@Tag("configuration.model-selection")
	@Test
	void createsTheClientWhenOnlyTheMultimodalSelectorNamesOpenRouter() {
		this.contextRunner
			.withPropertyValues(API_KEY_PROPERTY, SELECTED, "spring.ai.model.chat=none",
					"spring.ai.model.embedding=none", "spring.ai.model.image=none")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).hasSingleBean(OpenRouterDocumentEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(EmbeddingModel.class);
			});
	}

	@Tag("configuration.properties")
	@Test
	void sharesTheEmbeddingProperties() {
		this.contextRunner
			.withPropertyValues(API_KEY_PROPERTY, SELECTED,
					"spring.ai.openrouter.embedding.model=synthetic/multimodal-embed",
					"spring.ai.openrouter.embedding.dimensions=64", "spring.ai.openrouter.embedding.user=embed-user")
			.run(context -> {
				OpenRouterDocumentEmbeddingModel model = context.getBean(OpenRouterDocumentEmbeddingModel.class);
				OpenRouterEmbeddingOptions options = model.getOptions();
				assertThat(options.getModel()).isEqualTo("synthetic/multimodal-embed");
				assertThat(options.getUser()).isEqualTo("embed-user");
				assertThat(model.dimensions()).isEqualTo(64);
			});
	}

	@Tag("configuration.beans")
	@Test
	void backsOffForAUserDocumentEmbeddingModel() {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, SELECTED)
			.withUserConfiguration(UserDocumentEmbeddingModelConfiguration.class)
			.run(context -> {
				assertThat(context).doesNotHaveBean(OpenRouterDocumentEmbeddingModel.class);
				assertThat(context).hasSingleBean(DocumentEmbeddingModel.class);
				assertThat(context).hasBean("userDocumentEmbeddingModel");
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class UserDocumentEmbeddingModelConfiguration {

		@Bean
		DocumentEmbeddingModel userDocumentEmbeddingModel() {
			return new DocumentEmbeddingModel() {

				@Override
				public EmbeddingResponse call(DocumentEmbeddingRequest request) {
					throw new UnsupportedOperationException("synthetic user model");
				}

				@Override
				public int dimensions() {
					return 1;
				}
			};
		}

	}

}
