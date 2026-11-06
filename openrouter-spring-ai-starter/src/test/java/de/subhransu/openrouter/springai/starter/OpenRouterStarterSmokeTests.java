package de.subhransu.openrouter.springai.starter;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterImageAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.chat.memory.autoconfigure.ChatMemoryAutoConfiguration;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Smoke test for the starter module. The starter declares no code of its own; its job is
 * to drag in the autoconfigure module so the OpenRouter beans appear on the classpath and
 * auto-configure. This test proves that wiring without any real OpenRouter network call.
 */
class OpenRouterStarterSmokeTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, ToolCallingAutoConfiguration.class,
					ChatMemoryAutoConfiguration.class, OpenRouterApiAutoConfiguration.class,
					OpenRouterChatAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class,
					OpenRouterImageAutoConfiguration.class, ChatClientAutoConfiguration.class));

	@Test
	void autoConfigurationImportsRegisterRequiredAutoConfigurations() {
		// The starter's real contract is runtime registration: Boot merges the imports
		// files of every module on the classpath. Spring AI's retry module registers its
		// own auto-configuration; the starter only has to bring that module along.
		List<String> candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
			.getCandidates();
		assertThat(candidates).contains(OpenRouterApiAutoConfiguration.class.getName(),
				OpenRouterChatAutoConfiguration.class.getName(), OpenRouterEmbeddingAutoConfiguration.class.getName(),
				OpenRouterImageAutoConfiguration.class.getName(), SpringAiRetryAutoConfiguration.class.getName(),
				ToolCallingAutoConfiguration.class.getName());
		assertThat(candidates).as("Spring AI's retry auto-configuration is registered only by its own module")
			.filteredOn(name -> name.equals(SpringAiRetryAutoConfiguration.class.getName()))
			.hasSize(1);
	}

	@Test
	void ownImportsFileListsOnlyOpenRouterAutoConfigurations() throws IOException {
		Enumeration<URL> imports = getClass().getClassLoader()
			.getResources("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
		List<String> openRouterEntries = new ArrayList<>();
		while (imports.hasMoreElements()) {
			try (InputStream in = imports.nextElement().openStream()) {
				List<String> entries = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
					.map(String::trim)
					.filter(line -> !line.isEmpty() && !line.startsWith("#"))
					.toList();
				if (entries.contains(OpenRouterApiAutoConfiguration.class.getName())) {
					openRouterEntries.addAll(entries);
				}
			}
		}
		assertThat(openRouterEntries).isNotEmpty()
			.allSatisfy(entry -> assertThat(entry).startsWith("de.subhransu.openrouter.springai.autoconfigure."));
	}

	@Test
	void retryPropertiesConfigureOneTemplateForEveryBlockingModel() {
		contextRunner
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.retry.max-attempts=2",
					"spring.ai.retry.backoff.initial-interval=25ms", "spring.ai.retry.backoff.multiplier=2",
					"spring.ai.retry.backoff.max-interval=50ms", "spring.ai.retry.on-client-errors=true",
					"spring.ai.retry.on-http-codes=408,429", "spring.ai.retry.exclude-on-http-codes=400,401")
			.run(context -> {
				assertThat(context).hasSingleBean(SpringAiRetryProperties.class);
				assertThat(context).hasSingleBean(RetryTemplate.class);

				SpringAiRetryProperties properties = context.getBean(SpringAiRetryProperties.class);
				assertThat(properties.getMaxAttempts()).isEqualTo(2);
				assertThat(properties.getBackoff().getInitialInterval()).isEqualTo(Duration.ofMillis(25));
				assertThat(properties.getBackoff().getMultiplier()).isEqualTo(2);
				assertThat(properties.getBackoff().getMaxInterval()).isEqualTo(Duration.ofMillis(50));
				assertThat(properties.isOnClientErrors()).isTrue();
				assertThat(properties.getOnHttpCodes()).containsExactly(408, 429);
				assertThat(properties.getExcludeOnHttpCodes()).containsExactly(400, 401);

				RetryTemplate retryTemplate = context.getBean(RetryTemplate.class);
				assertThat(context.getBean(OpenRouterChatModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);
				assertThat(context.getBean(OpenRouterEmbeddingModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);
				assertThat(context.getBean(OpenRouterImageModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);

				BackOffExecution backOff = retryTemplate.getRetryPolicy().getBackOff().start();
				assertThat(List.of(backOff.nextBackOff(), backOff.nextBackOff(), backOff.nextBackOff()))
					.containsExactly(25L, 50L, BackOffExecution.STOP);

				AtomicInteger attempts = new AtomicInteger();
				String result = retryTemplate.invoke(() -> {
					if (attempts.incrementAndGet() <= 2) {
						throw new TransientAiException("retryable test failure");
					}
					return "success";
				});
				assertThat(result).isEqualTo("success");
				assertThat(attempts).hasValue(3);
			});
	}

	@Test
	void userRetryTemplateOverridesTheStandardAutoConfiguredBean() {
		contextRunner.withUserConfiguration(UserRetryConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.retry.max-attempts=9")
			.run(context -> {
				assertThat(context).hasSingleBean(RetryTemplate.class);
				RetryTemplate retryTemplate = context.getBean("userRetryTemplate", RetryTemplate.class);
				assertThat(context.getBean(RetryTemplate.class)).isSameAs(retryTemplate);
				assertThat(context.getBean(OpenRouterChatModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);
				assertThat(context.getBean(OpenRouterEmbeddingModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);
				assertThat(context.getBean(OpenRouterImageModel.class)).extracting("retryTemplate")
					.isSameAs(retryTemplate);
			});
	}

	@Test
	void minimalContextStartsWithApiKeyAndNoNetworkCall() {
		contextRunner.withPropertyValues(API_KEY_PROPERTY)
			.run((ContextConsumer<org.springframework.boot.test.context.assertj.AssertableApplicationContext>) context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(context).hasSingleBean(OpenRouterChatModel.class);
				assertThat(context).hasSingleBean(ToolCallingManager.class);
				assertThat(context).hasSingleBean(ChatMemoryRepository.class);
				assertThat(context).hasSingleBean(ChatMemory.class);
				assertThat(context.getBean(OpenRouterChatModel.class)).extracting("toolCallingManager")
					.isSameAs(context.getBean(ToolCallingManager.class));
				assertThat(context.getBeanNamesForType(ChatClient.Builder.class)).hasSize(1);
				assertThat(context.getBean(ChatClient.Builder.class))
					.isNotSameAs(context.getBean(ChatClient.Builder.class));
			});
	}

	@Test
	void userOwnedSharedInfrastructureTakesPrecedence() {
		contextRunner.withUserConfiguration(UserInfrastructureConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(ToolCallingManager.class))
					.isSameAs(context.getBean("userToolCallingManager"));
				assertThat(context.getBean(ChatMemoryRepository.class))
					.isSameAs(context.getBean("userChatMemoryRepository"));
				assertThat(context.getBean(ChatMemory.class)).isSameAs(context.getBean("userChatMemory"));
				assertThat(context.getBean(OpenRouterChatModel.class)).extracting("toolCallingManager")
					.isSameAs(context.getBean("userToolCallingManager"));
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class UserInfrastructureConfiguration {

		@Bean
		ToolCallingManager userToolCallingManager() {
			return ToolCallingManager.builder().build();
		}

		@Bean
		ChatMemoryRepository userChatMemoryRepository() {
			return new InMemoryChatMemoryRepository();
		}

		@Bean
		ChatMemory userChatMemory(ChatMemoryRepository chatMemoryRepository) {
			return MessageWindowChatMemory.builder().chatMemoryRepository(chatMemoryRepository).build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class UserRetryConfiguration {

		@Bean
		RetryTemplate userRetryTemplate() {
			return new RetryTemplate(RetryPolicy.withMaxRetries(0));
		}

	}

}
