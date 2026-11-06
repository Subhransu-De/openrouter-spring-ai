package org.springframework.ai.model.simulated;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Test stand-in for an official Spring AI provider chat auto-configuration. It lives in
 * {@code org.springframework.ai.model} so that Boot's name-based sorting places it where
 * {@code OpenAiChatAutoConfiguration} or {@code AnthropicChatAutoConfiguration} would be,
 * and it copies their shape: no ordering, model selection with {@code matchIfMissing},
 * and back-off on its own model type only.
 */
@AutoConfiguration
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = "simulated", matchIfMissing = true)
public final class SimulatedProviderChatAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	SimulatedChatModel simulatedChatModel() {
		return new SimulatedChatModel();
	}

	/**
	 * Chat model registered by the simulated provider.
	 */
	public static final class SimulatedChatModel implements ChatModel {

		@Override
		public ChatResponse call(Prompt prompt) {
			throw new UnsupportedOperationException("not called");
		}

	}

}
