package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Tag("configuration")
@Tag("configuration.model-selection")
class OpenRouterModelSelectionAutoConfigurationTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private static final String CHAT_NONE = "spring.ai.model.chat=none";

	private static final String EMBEDDING_NONE = "spring.ai.model.embedding=none";

	private static final String IMAGE_NONE = "spring.ai.model.image=none";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class,
					OpenRouterEmbeddingAutoConfiguration.class, OpenRouterImageAutoConfiguration.class));

	@Test
	void missingSelectorsEnableEveryOpenRouterModelOnTheClasspath() {
		this.contextRunner.withPropertyValues(API_KEY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
			assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
			assertThat(context).hasSingleBean(OpenRouterImageModel.class);
		});
	}

	@Test
	void openRouterChatSelectorCreatesOnlyTheSelectedModel() {
		this.contextRunner
			.withPropertyValues(API_KEY, "spring.ai.model.chat=" + OpenRouterIdentifiers.PROVIDER_ID, EMBEDDING_NONE,
					IMAGE_NONE)
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).hasSingleBean(OpenRouterChatModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterImageModel.class);
			});
	}

	@Test
	void openRouterEmbeddingSelectorCreatesOnlyTheSelectedModel() {
		this.contextRunner
			.withPropertyValues(API_KEY, CHAT_NONE, "spring.ai.model.embedding=" + OpenRouterIdentifiers.PROVIDER_ID,
					IMAGE_NONE)
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
				assertThat(context).hasSingleBean(OpenRouterEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterImageModel.class);
			});
	}

	@Test
	void openRouterImageSelectorCreatesOnlyTheSelectedModel() {
		this.contextRunner
			.withPropertyValues(API_KEY, CHAT_NONE, EMBEDDING_NONE,
					"spring.ai.model.image=" + OpenRouterIdentifiers.PROVIDER_ID)
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class);
				assertThat(context).hasSingleBean(OpenRouterImageModel.class);
			});
	}

	@Test
	void noneDisablesAllModelsAndApiWithoutRequiringAnApiKey() {
		this.contextRunner.withPropertyValues(CHAT_NONE, EMBEDDING_NONE, IMAGE_NONE).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(OpenRouterApi.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
			assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class);
			assertThat(context).doesNotHaveBean(OpenRouterImageModel.class);
		});
	}

	@Test
	void otherProvidersDisableAllModelsAndApiWithoutRequiringAnApiKey() {
		this.contextRunner
			.withPropertyValues("spring.ai.model.chat=anthropic", "spring.ai.model.embedding=ollama",
					"spring.ai.model.image=openai")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(OpenRouterApi.class);
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterEmbeddingModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterImageModel.class);
			});
	}

	@Test
	void selectingAnotherChatProviderIsIndependentOfChatModelBeanOrder() {
		this.contextRunner.withUserConfiguration(OtherChatProviderConfiguration.class)
			.withPropertyValues("spring.ai.model.chat=other", EMBEDDING_NONE, IMAGE_NONE)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(ChatModel.class);
				assertThat(context).doesNotHaveBean(OpenRouterApi.class);
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
			});
	}

	@Test
	void selectedOpenRouterModelStillRequiresAnApiKey() {
		this.contextRunner
			.withPropertyValues("spring.ai.model.chat=" + OpenRouterIdentifiers.PROVIDER_ID, EMBEDDING_NONE, IMAGE_NONE)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
			});
	}

	@Test
	void replacingTheChatModelKeepsTheSharedApiWhileTheSelectorIsUnset() {
		// The shared API bean depends only on the selectors, because application model
		// beans may inject it. Replacing the chat model therefore still requires the key
		// unless the selector rules OpenRouter out.
		ApplicationContextRunner ownChatModel = this.contextRunner
			.withUserConfiguration(OtherChatProviderConfiguration.class)
			.withPropertyValues(EMBEDDING_NONE, IMAGE_NONE);
		ownChatModel.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("OpenRouter API key");
		});
		ownChatModel.withPropertyValues(CHAT_NONE).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(ChatModel.class);
		});
		ownChatModel.withPropertyValues(API_KEY).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
		});
	}

	@Test
	void providerSelectionIsSeparateFromConcreteModelSelection() {
		this.contextRunner
			.withPropertyValues(API_KEY, "spring.ai.model.chat=" + OpenRouterIdentifiers.PROVIDER_ID, EMBEDDING_NONE,
					IMAGE_NONE, "spring.ai.openrouter.chat.model=anthropic/claude-sonnet-4")
			.run(context -> {
				OpenRouterChatOptions options = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
					.getOptions();
				assertThat(options.getModel()).isEqualTo("anthropic/claude-sonnet-4");
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class OtherChatProviderConfiguration {

		@Bean
		ChatModel otherChatModel() {
			return org.mockito.Mockito.mock(ChatModel.class);
		}

	}

}
