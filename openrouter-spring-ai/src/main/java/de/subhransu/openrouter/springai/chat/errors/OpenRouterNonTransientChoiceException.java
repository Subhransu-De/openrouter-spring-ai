package de.subhransu.openrouter.springai.chat.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * A deterministic choice-level OpenRouter failure that Spring AI must not retry.
 *
 * @author Subhransu De
 */
public final class OpenRouterNonTransientChoiceException extends NonTransientAiException
		implements OpenRouterChoiceFailure {

	private final @Nullable OpenRouterChoiceErrorDetails errorDetails;

	public OpenRouterNonTransientChoiceException(String message, @Nullable OpenRouterChoiceErrorDetails errorDetails) {
		super(message);
		this.errorDetails = errorDetails;
	}

	@Override
	public @Nullable OpenRouterChoiceErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

}
