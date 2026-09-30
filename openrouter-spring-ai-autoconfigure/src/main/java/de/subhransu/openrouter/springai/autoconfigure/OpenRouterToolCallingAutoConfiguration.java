package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Installs provider-safe tool failure handling before Spring AI creates its shared tool
 * calling manager.
 *
 * @author Subhransu De
 */
@AutoConfiguration(beforeName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass({ OpenRouterToolExecutionExceptionProcessor.class, ToolExecutionExceptionProcessor.class })
@ConditionalOnMissingBean(ChatModel.class)
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
		matchIfMissing = true)
public class OpenRouterToolCallingAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(ToolExecutionExceptionProcessor.class)
	@ConditionalOnProperty(prefix = "spring.ai.tools", name = "throw-exception-on-error", havingValue = "false",
			matchIfMissing = true)
	OpenRouterToolExecutionExceptionProcessor openRouterToolExecutionExceptionProcessor(
			ObjectProvider<ObservationRegistry> observationRegistryProvider) {
		return new OpenRouterToolExecutionExceptionProcessor(
				observationRegistryProvider.getIfUnique(() -> ObservationRegistry.NOOP));
	}

	@Bean
	@ConditionalOnProperty(prefix = OpenRouterChatProperties.CONFIG_PREFIX, name = "allow-unsafe-tool-failure-results",
			havingValue = "false", matchIfMissing = true)
	static OpenRouterToolCallingManagerGuard openRouterToolCallingManagerGuard(
			ConfigurableListableBeanFactory beanFactory) {
		return new OpenRouterToolCallingManagerGuard(beanFactory);
	}

}
