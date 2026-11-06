package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterToolExecutionExceptionProcessor;
import java.util.Arrays;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Warns when OpenRouter's tool failure processor also serves another provider's chat
 * model.
 *
 * @author Subhransu De
 */
final class OpenRouterToolFailurePolicyScope implements SmartInitializingSingleton {

	private static final Log logger = LogFactory.getLog(OpenRouterToolFailurePolicyScope.class);

	private final ListableBeanFactory beanFactory;

	OpenRouterToolFailurePolicyScope(ListableBeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterSingletonsInstantiated() {
		// Boot processes our tool auto-configuration before the official providers, so
		// with spring.ai.model.chat unset another provider's chat model can appear after
		// our processor was chosen. Spring AI's shared tool calling manager then applies
		// our failure policy to that provider's tool calls as well.
		if (this.beanFactory.getBeanNamesForType(OpenRouterToolExecutionExceptionProcessor.class, false,
				false).length == 0) {
			return;
		}
		if (Arrays.stream(this.beanFactory.getBeanNamesForType(ChatModel.class, false, false))
			.anyMatch(this::isOtherProviderModel)) {
			logger.warn("OpenRouter's tool failure policy also handles the tool calls of another provider's chat "
					+ "model in this context. Set spring.ai.model.chat to the provider you use, or declare your own "
					+ "ToolExecutionExceptionProcessor bean.");
		}
	}

	// Reads the type without creating the bean, because a diagnostic must not create
	// lazy models or their dependencies. A lazy model declared only as an interface
	// cannot be identified without creating it, so it is skipped.
	private boolean isOtherProviderModel(String beanName) {
		Class<?> type = this.beanFactory.getType(beanName, false);
		return type != null && !type.isInterface() && !OpenRouterChatModel.class.isAssignableFrom(type);
	}

}
