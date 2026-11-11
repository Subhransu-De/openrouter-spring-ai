package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;

class OpenRouterModelSelectionTests {

	private static final String CHAT_NONE = "spring.ai.model.chat=none";

	private static final String EMBEDDING_NONE = "spring.ai.model.embedding=none";

	private static final String IMAGE_NONE = "spring.ai.model.image=none";

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
					OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class,
					OpenRouterToolCallingAutoConfiguration.class, ToolCallingAutoConfiguration.class));

	@Test
	void replacementModelsDeclaredAsOpenRouterTypesWinEvenWithExplicitOpenRouterSelection() {
		OpenRouterChatModel chat = mock(OpenRouterChatModel.class);
		OpenRouterEmbeddingModel embedding = mock(OpenRouterEmbeddingModel.class);
		OpenRouterImageModel image = mock(OpenRouterImageModel.class);

		assertReplacementModelsWin(this.contextRunner.withBean("applicationChat", OpenRouterChatModel.class, () -> chat)
			.withBean("applicationEmbedding", OpenRouterEmbeddingModel.class, () -> embedding)
			.withBean("applicationImage", OpenRouterImageModel.class, () -> image), chat, embedding, image);
	}

	@Test
	void replacementModelsDeclaredAsSpringAiInterfacesWinEvenWithExplicitOpenRouterSelection() {
		ChatModel chat = mock(ChatModel.class);
		EmbeddingModel embedding = mock(EmbeddingModel.class);
		ImageModel image = mock(ImageModel.class);

		assertReplacementModelsWin(this.contextRunner.withBean("applicationChat", ChatModel.class, () -> chat)
			.withBean("applicationEmbedding", EmbeddingModel.class, () -> embedding)
			.withBean("applicationImage", ImageModel.class, () -> image), chat, embedding, image);
	}

	private static void assertReplacementModelsWin(ApplicationContextRunner runner, ChatModel chat,
			EmbeddingModel embedding, ImageModel image) {
		ToolCallingManager manager = mock(ToolCallingManager.class);
		ToolExecutionExceptionProcessor processor = exception -> "application failure";
		runner
			.withPropertyValues(API_KEY, "spring.ai.model.chat=openrouter", "spring.ai.model.embedding=openrouter",
					"spring.ai.model.image=openrouter")
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

	// Runs the three modalities with one explicit selector value, with the other
	// provider's auto-configuration ordered before or after the OpenRouter ones, so that
	// bean-order independence is exercised in both directions.
	private void withSelection(String provider, boolean otherProviderFirst,
			ContextConsumer<AssertableApplicationContext> consumer) {
		this.contextRunner
			.withConfiguration(AutoConfigurations.of(otherProviderFirst ? OtherProviderFirstAutoConfiguration.class
					: OtherProviderLastAutoConfiguration.class))
			.withPropertyValues(API_KEY, "spring.ai.model.chat=" + provider, "spring.ai.model.embedding=" + provider,
					"spring.ai.model.image=" + provider)
			.run(consumer);
	}

	@ParameterizedTest(name = "other provider ordered first = {0}")
	@ValueSource(booleans = { true, false })
	void selectingOpenRouterChoosesItsModelsAndToolPolicy(boolean otherProviderFirst) {
		withSelection("openrouter", otherProviderFirst, context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(ChatModel.class)
				.hasSingleBean(EmbeddingModel.class)
				.hasSingleBean(ImageModel.class);
			assertThat(context.getBean("openRouterChatModel", ChatModel.class))
				.isSameAs(context.getBean(ChatModel.class));
			assertThat(context.getBean("openRouterEmbeddingModel", EmbeddingModel.class))
				.isSameAs(context.getBean(EmbeddingModel.class));
			assertThat(context.getBean("openRouterImageModel", ImageModel.class))
				.isSameAs(context.getBean(ImageModel.class));
			assertThat(context).hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterApi.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterToolCallingManagerGuard.class);
		});
	}

	@ParameterizedTest(name = "other provider ordered first = {0}")
	@ValueSource(booleans = { true, false })
	void selectingAnotherProviderChoosesItsModelsAndLeavesSpringAiToolDefaults(boolean otherProviderFirst) {
		withSelection("other", otherProviderFirst, context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(ChatModel.class)
				.hasSingleBean(EmbeddingModel.class)
				.hasSingleBean(ImageModel.class);
			assertThat(context.getBean("otherChatModel", ChatModel.class)).isSameAs(context.getBean(ChatModel.class));
			assertThat(context.getBean("otherEmbeddingModel", EmbeddingModel.class))
				.isSameAs(context.getBean(EmbeddingModel.class));
			assertThat(context.getBean("otherImageModel", ImageModel.class))
				.isSameAs(context.getBean(ImageModel.class));
			assertThat(context).hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterApi.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class);
		});
	}

	@ParameterizedTest(name = "other provider ordered first = {0}")
	@ValueSource(booleans = { true, false })
	void selectingNoProviderCreatesNoModelsAndKeepsSpringAiToolDefaults(boolean otherProviderFirst) {
		withSelection("none", otherProviderFirst, context -> {
			assertThat(context).hasNotFailed()
				.doesNotHaveBean(ChatModel.class)
				.doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(ImageModel.class)
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterApi.class)
				.hasSingleBean(DefaultToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class)
				.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class);
		});
	}

	@Test
	void missingSelectorsEnableModelsAndDefaultToolPolicyTogether() {
		this.contextRunner.withPropertyValues(API_KEY).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.hasSingleBean(ChatModel.class)
				.hasSingleBean(EmbeddingModel.class)
				.hasSingleBean(ImageModel.class)
				.hasSingleBean(OpenRouterToolCallingManagerGuard.class)
				.hasSingleBean(ToolCallingManager.class)
				.hasSingleBean(ToolExecutionExceptionProcessor.class)
				.hasSingleBean(OpenRouterToolExecutionExceptionProcessor.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "chat", "embedding", "image" })
	void eachSelectedModalityRequiresCredentials(String modality) {
		this.contextRunner.withPropertyValues(CHAT_NONE, EMBEDDING_NONE, IMAGE_NONE)
			.withPropertyValues("spring.ai.model." + modality + "=openrouter")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
			});
	}

	@Test
	void disablingEveryModalityNeedsNoApiKey() {
		this.contextRunner.withPropertyValues(CHAT_NONE, EMBEDDING_NONE, IMAGE_NONE).run(context -> {
			assertThat(context).hasNotFailed()
				.doesNotHaveBean(OpenRouterApi.class)
				.doesNotHaveBean(ChatModel.class)
				.doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(ImageModel.class)
				.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "none", "other" })
	void disablingChatRetainsDefaultEmbeddingAndImageSelection(String chatSelector) {
		this.contextRunner.withPropertyValues(API_KEY, "spring.ai.model.chat=" + chatSelector).run(context -> {
			assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.doesNotHaveBean(ChatModel.class)
				.hasSingleBean(OpenRouterEmbeddingModel.class)
				.hasSingleBean(OpenRouterImageModel.class)
				.doesNotHaveBean(OpenRouterToolCallingManagerGuard.class)
				.doesNotHaveBean(OpenRouterToolExecutionExceptionProcessor.class);
		});
	}

	@ParameterizedTest
	@CsvSource({ "chat, 1, 0, 0", "embedding, 0, 1, 0", "image, 0, 0, 1" })
	void selectingOneModalityCreatesOnlyThatModel(String modality, int chats, int embeddings, int images) {
		this.contextRunner.withPropertyValues(API_KEY, CHAT_NONE, EMBEDDING_NONE, IMAGE_NONE)
			.withPropertyValues("spring.ai.model." + modality + "=" + OpenRouterIdentifiers.PROVIDER_ID)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(OpenRouterApi.class);
				assertThat(context.getBeansOfType(ChatModel.class)).hasSize(chats);
				assertThat(context.getBeansOfType(EmbeddingModel.class)).hasSize(embeddings);
				assertThat(context.getBeansOfType(ImageModel.class)).hasSize(images);
			});
	}

	@Test
	void differentOtherProvidersPerModalityDisableEveryOpenRouterBeanWithoutAnApiKey() {
		this.contextRunner
			.withPropertyValues("spring.ai.model.chat=anthropic", "spring.ai.model.embedding=ollama",
					"spring.ai.model.image=openai")
			.run(context -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(OpenRouterApi.class)
				.doesNotHaveBean(ChatModel.class)
				.doesNotHaveBean(EmbeddingModel.class)
				.doesNotHaveBean(ImageModel.class));
	}

	// The shared API bean depends only on the selectors, because application model beans
	// may inject it. An application chat model therefore still needs the key unless a
	// selector rules OpenRouter out.
	private ApplicationContextRunner applicationChatModelRunner() {
		return this.contextRunner.withBean("applicationChat", ChatModel.class, () -> mock(ChatModel.class))
			.withPropertyValues(EMBEDDING_NONE, IMAGE_NONE);
	}

	@Test
	void anApplicationChatModelStillNeedsTheApiKeyWhileTheSelectorIsUnset() {
		applicationChatModelRunner().run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
		});
	}

	@Test
	void anApplicationChatModelNeedsNoApiKeyWhenTheSelectorRulesOpenRouterOut() {
		applicationChatModelRunner().withPropertyValues(CHAT_NONE)
			.run(context -> assertThat(context).hasNotFailed()
				.doesNotHaveBean(OpenRouterApi.class)
				.hasSingleBean(ChatModel.class));
	}

	@Test
	void anApplicationChatModelKeepsTheSharedApiWhenAKeyIsConfigured() {
		applicationChatModelRunner().withPropertyValues(API_KEY)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.doesNotHaveBean(OpenRouterChatModel.class));
	}

	@AutoConfiguration(before = { OpenRouterToolCallingAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
			OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class })
	static class OtherProviderFirstAutoConfiguration extends OtherProviderConfiguration {

	}

	@AutoConfiguration(after = { OpenRouterToolCallingAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
			OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class })
	static class OtherProviderLastAutoConfiguration extends OtherProviderConfiguration {

	}

	static class OtherProviderConfiguration {

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
