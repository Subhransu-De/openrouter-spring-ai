package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.model.simulated.SimulatedProviderAutoConfigurations;
import org.springframework.ai.model.simulated.SimulatedProviderAutoConfigurations.SimulatedEmbeddingModel;
import org.springframework.ai.model.simulated.SimulatedProviderChatAutoConfiguration;
import org.springframework.ai.model.simulated.SimulatedProviderChatAutoConfiguration.SimulatedChatModel;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Official Spring AI provider auto-configurations declare no ordering and back off only
 * on their own model type. Boot sorts auto-configurations by name before applying
 * ordering constraints, so ours, in {@code de.subhransu}, is processed before a provider
 * in {@code org.springframework.ai.model}. With {@code spring.ai.model.chat} unset, both
 * chat models are created, and Spring AI's {@code ChatClient.Builder}, which takes a
 * single {@link ChatModel}, cannot be created unless one of them is {@code @Primary}. Our
 * tool-failure processor is then installed too, so it also renders the other provider's
 * tool failures.
 */
class OpenRouterMultiProviderBackoffTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
				OpenRouterToolCallingAutoConfiguration.class, OpenRouterApiAutoConfiguration.class,
				OpenRouterChatAutoConfiguration.class, SimulatedProviderChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@Test
	void unsetSelectionCreatesBothChatModels() {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).hasSingleBean(SimulatedChatModel.class);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class))
				.isInstanceOf(OpenRouterToolExecutionExceptionProcessor.class);
		});
	}

	@Test
	void selectingOpenRouterKeepsOnlyItsChatModel() {
		this.contextRunner.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=openrouter").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).doesNotHaveBean(SimulatedChatModel.class);
		});
	}

	@Test
	void selectingTheOtherProviderLeavesOutOurChatModel() {
		this.contextRunner.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=simulated").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SimulatedChatModel.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class))
				.isInstanceOf(DefaultToolExecutionExceptionProcessor.class);
		});
	}

	@Test
	void embeddingAndImageModelsNeedTheirOwnSelection() {
		ApplicationContextRunner allModelTypes = this.contextRunner.withConfiguration(AutoConfigurations.of(
				OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class,
				SimulatedProviderAutoConfigurations.Embedding.class, SimulatedProviderAutoConfigurations.Image.class));
		// Selecting only the chat model leaves two embedding and two image models; a
		// consumer that injects a single EmbeddingModel, such as a vector store, fails.
		allModelTypes.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=openrouter").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2);
			assertThat(context.getBeansOfType(ImageModel.class)).hasSize(2);
		});
		allModelTypes.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=openrouter",
				SpringAIModelProperties.EMBEDDING_MODEL + "=openrouter", SpringAIModelProperties.IMAGE_MODEL + "=none")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(SimulatedEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(ImageModel.class);
			});
	}

}
