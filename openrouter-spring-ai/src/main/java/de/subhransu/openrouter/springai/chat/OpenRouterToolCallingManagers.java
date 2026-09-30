package de.subhransu.openrouter.springai.chat;

import java.util.List;
import java.util.function.Consumer;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.util.Assert;

/**
 * Factory for custom Spring AI tool calling managers that declare their tool failure
 * policy.
 *
 * <p>
 * Spring AI does not expose the processor used by {@code DefaultToolCallingManager}. A
 * manager built here installs a known processor and implements
 * {@link OpenRouterToolFailurePolicy}, so startup validation can verify it through the
 * public contract.
 *
 * @author Subhransu De
 */
public final class OpenRouterToolCallingManagers {

	private OpenRouterToolCallingManagers() {
	}

	/**
	 * Build a Spring AI tool calling manager that uses and declares the given processor.
	 * The customizer receives Spring AI's own builder. The processor is installed after
	 * the customizer runs, so a processor set by the customizer is replaced.
	 * @param processor the processor for tool execution failures; to pass startup
	 * validation it must be an application-declared bean or an
	 * {@link OpenRouterToolExecutionExceptionProcessor}
	 * @param customizer customizer for the other builder options
	 * @return a manager that implements {@link OpenRouterToolFailurePolicy}
	 */
	public static ToolCallingManager withFailurePolicy(ToolExecutionExceptionProcessor processor,
			Consumer<DefaultToolCallingManager.Builder> customizer) {
		Assert.notNull(processor, "processor must not be null");
		Assert.notNull(customizer, "customizer must not be null");
		DefaultToolCallingManager.Builder builder = ToolCallingManager.builder();
		customizer.accept(builder);
		return new PolicyDeclaringToolCallingManager(builder.toolExecutionExceptionProcessor(processor).build(),
				processor);
	}

	private static final class PolicyDeclaringToolCallingManager
			implements ToolCallingManager, OpenRouterToolFailurePolicy {

		private final ToolCallingManager delegate;

		private final ToolExecutionExceptionProcessor processor;

		PolicyDeclaringToolCallingManager(ToolCallingManager delegate, ToolExecutionExceptionProcessor processor) {
			this.delegate = delegate;
			this.processor = processor;
		}

		@Override
		public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
			return this.delegate.resolveToolDefinitions(chatOptions);
		}

		@Override
		public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
			return this.delegate.executeToolCalls(prompt, chatResponse);
		}

		@Override
		public ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return this.processor;
		}

	}

}
