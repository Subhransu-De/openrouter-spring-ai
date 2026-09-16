package de.subhransu.openrouter.springai.chat;

import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * Explicit failure-policy contract for custom Spring AI tool calling managers.
 * Implementations must return the processor actually used for tool failures, including
 * delegated execution. The same policy must apply to synchronous and streaming use. The
 * processor must be an application-declared bean, an
 * {@link OpenRouterToolExecutionExceptionProcessor}, or a Spring AI throwing processor.
 * This declaration is an application responsibility, not an inspection of execution.
 *
 * @author Subhransu De
 */
public interface OpenRouterToolFailurePolicy {

	/**
	 * Return the processor used to handle tool execution failures.
	 * @return the actual failure processor, never {@code null}
	 */
	ToolExecutionExceptionProcessor toolExecutionExceptionProcessor();

}
