package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class OpenRouterModelSelectionTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
					OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class,
					OpenRouterToolCallingAutoConfiguration.class, ToolCallingAutoConfiguration.class));

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void replacementModelsWinEvenWithExplicitOpenRouterSelection(boolean concreteModels) {
		ChatModel chat = concreteModels ? mock(OpenRouterChatModel.class) : mock(ChatModel.class);
		EmbeddingModel embedding = concreteModels ? mock(OpenRouterEmbeddingModel.class) : mock(EmbeddingModel.class);
		ImageModel image = concreteModels ? mock(OpenRouterImageModel.class) : mock(ImageModel.class);
		ToolCallingManager manager = mock(ToolCallingManager.class);
		ToolExecutionExceptionProcessor processor = exception -> "application failure";
		ApplicationContextRunner runner = concreteModels
				? this.contextRunner
					.withBean("applicationChat", OpenRouterChatModel.class, () -> (OpenRouterChatModel) chat)
					.withBean("applicationEmbedding", OpenRouterEmbeddingModel.class,
							() -> (OpenRouterEmbeddingModel) embedding)
					.withBean("applicationImage", OpenRouterImageModel.class, () -> (OpenRouterImageModel) image)
				: this.contextRunner.withBean("applicationChat", ChatModel.class, () -> chat)
					.withBean("applicationEmbedding", EmbeddingModel.class, () -> embedding)
					.withBean("applicationImage", ImageModel.class, () -> image);
		runner
			.withPropertyValues("spring.ai.openrouter.api-key=test-key", "spring.ai.model.chat=openrouter",
					"spring.ai.model.embedding=openrouter", "spring.ai.model.image=openrouter")
			.withBean(ToolCallingManager.class, () -> manager)
			.withBean(ToolExecutionExceptionProcessor.class, () -> processor)
			.run(context -> {
				assertThat(context).hasNotFailed()
					.hasSingleBean(ChatModel.class)
					.hasSingleBean(EmbeddingModel.class)
					.hasSingleBean(ImageModel.class)
					.hasSingleBean(OpenRouterApi.class);
				assertThat(context.getBean("applicationChat", ChatModel.class)).isSameAs(chat);
				assertThat(context.getBean("applicationEmbedding", EmbeddingModel.class)).isSameAs(embedding);
				assertThat(context.getBean("applicationImage", ImageModel.class)).isSameAs(image);
				assertThat(context).doesNotHaveBean("openRouterChatModel")
					.doesNotHaveBean("openRouterEmbeddingModel")
					.doesNotHaveBean("openRouterImageModel")
					.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
					.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
				assertThat(context.getBean(ToolCallingManager.class)).isSameAs(manager);
				assertThat(context.getBean(ToolExecutionExceptionProcessor.class)).isSameAs(processor);
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "openrouter", "other", "none" })
	void explicitSelectionChoosesOneProviderAcrossModalities(String provider) {
		this.contextRunner.withConfiguration(AutoConfigurations.of(OtherProviderAutoConfiguration.class))
			.withPropertyValues("spring.ai.openrouter.api-key=test-key", "spring.ai.model.chat=" + provider,
					"spring.ai.model.embedding=" + provider, "spring.ai.model.image=" + provider)
			.run(context -> {
				assertThat(context).hasNotFailed();
				if ("none".equals(provider)) {
					assertThat(context).doesNotHaveBean(ChatModel.class)
						.doesNotHaveBean(EmbeddingModel.class)
						.doesNotHaveBean(ImageModel.class);
				}
				else {
					assertThat(context).hasSingleBean(ChatModel.class)
						.hasSingleBean(EmbeddingModel.class)
						.hasSingleBean(ImageModel.class);
					String prefix = "openrouter".equals(provider) ? "openRouter" : "other";
					assertThat(context.getBean(prefix + "ChatModel", ChatModel.class))
						.isSameAs(context.getBean(ChatModel.class));
					assertThat(context.getBean(prefix + "EmbeddingModel", EmbeddingModel.class))
						.isSameAs(context.getBean(EmbeddingModel.class));
					assertThat(context.getBean(prefix + "ImageModel", ImageModel.class))
						.isSameAs(context.getBean(ImageModel.class));
				}
				assertThat(context).hasSingleBean(ToolCallingManager.class)
					.hasSingleBean(ToolExecutionExceptionProcessor.class);
				if ("openrouter".equals(provider)) {
					assertThat(context).hasSingleBean(OpenRouterApi.class)
						.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class)
						.hasSingleBean(OpenRouterToolCallingManagerGuard.class);
				}
				else {
					assertThat(context).doesNotHaveBean(OpenRouterApi.class)
						.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
						.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class);
				}
			});
	}

	@Test
	void disablingEveryModalityNeedsNoApiKey() {
		this.contextRunner
			.withPropertyValues("spring.ai.model.chat=none", "spring.ai.model.embedding=none",
					"spring.ai.model.image=none")
			.run(context -> {
				assertThat(context).hasNotFailed()
					.doesNotHaveBean(OpenRouterApi.class)
					.doesNotHaveBean(ChatModel.class)
					.doesNotHaveBean(EmbeddingModel.class)
					.doesNotHaveBean(ImageModel.class)
					.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
					.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
			});
	}

	@Test
	void disablingChatRetainsDefaultEmbeddingAndImageSelection() {
		this.contextRunner.withPropertyValues("spring.ai.openrouter.api-key=test-key", "spring.ai.model.chat=none")
			.run(context -> {
				assertThat(context).hasNotFailed()
					.hasSingleBean(OpenRouterApi.class)
					.doesNotHaveBean(ChatModel.class)
					.hasSingleBean(OpenRouterEmbeddingModel.class)
					.hasSingleBean(OpenRouterImageModel.class)
					.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
					.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
			});
	}

	@AutoConfiguration
	static class OtherProviderAutoConfiguration {

		@Bean
		@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "other")
		ChatModel otherChatModel() {
			return mock(ChatModel.class);
		}

		@Bean
		@ConditionalOnProperty(name = "spring.ai.model.embedding", havingValue = "other")
		EmbeddingModel otherEmbeddingModel() {
			return mock(EmbeddingModel.class);
		}

		@Bean
		@ConditionalOnProperty(name = "spring.ai.model.image", havingValue = "other")
		ImageModel otherImageModel() {
			return mock(ImageModel.class);
		}

	}

}
