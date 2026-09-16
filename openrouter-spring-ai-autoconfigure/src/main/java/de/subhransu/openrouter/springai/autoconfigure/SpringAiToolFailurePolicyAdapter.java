package de.subhransu.openrouter.springai.autoconfigure;

import java.lang.reflect.Field;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.util.ReflectionUtils;

/**
 * Compatibility adapter for the Spring AI 2.0.1 default implementations.
 *
 * @author Subhransu De
 */
final class SpringAiToolFailurePolicyAdapter {

	private SpringAiToolFailurePolicyAdapter() {
	}

	static ToolExecutionExceptionProcessor processor(DefaultToolCallingManager manager) {
		return (ToolExecutionExceptionProcessor) readField(DefaultToolCallingManager.class,
				"toolExecutionExceptionProcessor", manager);
	}

	static boolean alwaysThrows(DefaultToolExecutionExceptionProcessor processor) {
		return Boolean.TRUE.equals(readField(DefaultToolExecutionExceptionProcessor.class, "alwaysThrow", processor));
	}

	static Object readField(Class<?> type, String name, Object target) {
		try {
			Field field = ReflectionUtils.findField(type, name);
			if (field == null) {
				throw new IllegalStateException("Missing field " + name);
			}
			ReflectionUtils.makeAccessible(field);
			return ReflectionUtils.getField(field, target);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Cannot inspect Spring AI tool failure policy on " + type.getName()
					+ ". The compatibility adapter supports Spring AI 2.0.1. Implement OpenRouterToolFailurePolicy "
					+ "with an application-declared ToolExecutionExceptionProcessor, or explicitly set "
					+ "spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true after auditing its behavior.",
					ex);
		}
	}

	static void registerHints(RuntimeHints hints) {
		registerField(hints, DefaultToolCallingManager.class, "toolExecutionExceptionProcessor");
		registerField(hints, DefaultToolExecutionExceptionProcessor.class, "alwaysThrow");
	}

	private static void registerField(RuntimeHints hints, Class<?> type, String name) {
		Field field = ReflectionUtils.findField(type, name);
		if (field != null) {
			hints.reflection().registerField(field);
		}
	}

}
