package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.model.simulated.SimulatedProviderAutoConfigurations;
import org.springframework.ai.model.simulated.SimulatedProviderAutoConfigurations.SimulatedEmbeddingModel;
import org.springframework.ai.model.simulated.SimulatedProviderAutoConfigurations.SimulatedTextEmbeddingModel;
import org.springframework.ai.model.simulated.SimulatedProviderChatAutoConfiguration;
import org.springframework.ai.model.simulated.SimulatedProviderChatAutoConfiguration.SimulatedChatModel;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

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
@ExtendWith(OutputCaptureExtension.class)
@Tag("configuration")
@Tag("configuration.model-selection")
class OpenRouterMultiProviderBackoffTests {

	private static final String SCOPE_WARNING = "also handles the tool calls of another provider's chat model";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class,
				OpenRouterToolCallingAutoConfiguration.class, OpenRouterApiAutoConfiguration.class,
				OpenRouterChatAutoConfiguration.class, SimulatedProviderChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@Test
	void unsetSelectionCreatesBothChatModels(CapturedOutput output) {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).hasSingleBean(SimulatedChatModel.class);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class))
				.isInstanceOf(OpenRouterToolExecutionExceptionProcessor.class);
			// Our processor now serves the other provider's tool calls too, so say so.
			assertThat(output).containsPattern("WARN .*" + SCOPE_WARNING);
		});
	}

	@Test
	void scopeWarningLeavesLazyChatModelsUncreated(CapturedOutput output) {
		this.contextRunner
			.withInitializer(
					(context) -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
			.run(context -> {
				assertThat(context).hasNotFailed();
				ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
				assertThat(beanFactory.getBeanNamesForType(ChatModel.class, false, false)).hasSize(2)
					.noneMatch(beanFactory::containsSingleton);
				assertThat(output).containsPattern("WARN .*" + SCOPE_WARNING);
			});
	}

	@Test
	void selectingOpenRouterKeepsOnlyItsChatModel(CapturedOutput output) {
		this.contextRunner.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=openrouter").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).doesNotHaveBean(SimulatedChatModel.class);
			assertThat(output).doesNotContain(SCOPE_WARNING);
		});
	}

	@Test
	void selectingTheOtherProviderLeavesOutOurChatModel(CapturedOutput output) {
		this.contextRunner.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=simulated").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SimulatedChatModel.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
			assertThat(context.getBean(ToolExecutionExceptionProcessor.class))
				.isInstanceOf(DefaultToolExecutionExceptionProcessor.class);
			assertThat(output).doesNotContain(SCOPE_WARNING);
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

	@Test
	void textEmbeddingProvidersNeedTheNarrowerSelector() {
		// Google GenAI and Vertex AI text embeddings select on
		// spring.ai.model.embedding.text,
		// so spring.ai.model.embedding alone does not rule them out.
		ApplicationContextRunner withTextEmbeddings = this.contextRunner
			.withConfiguration(AutoConfigurations.of(OpenRouterEmbeddingAutoConfiguration.class,
					SimulatedProviderAutoConfigurations.TextEmbedding.class))
			.withPropertyValues(SpringAIModelProperties.CHAT_MODEL + "=openrouter",
					SpringAIModelProperties.EMBEDDING_MODEL + "=openrouter");
		withTextEmbeddings.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(2);
			assertThat(context).hasSingleBean(SimulatedTextEmbeddingModel.class);
		});
		withTextEmbeddings.withPropertyValues(SpringAIModelProperties.TEXT_EMBEDDING_MODEL + "=none").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
			assertThat(context).doesNotHaveBean(SimulatedTextEmbeddingModel.class);
		});
	}

}
