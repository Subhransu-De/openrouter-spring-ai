package de.subhransu.openrouter.springai.chat.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.TransientAiException;

/**
 * A choice-level OpenRouter failure that Spring AI's default retry policy may retry.
 *
 * @author Subhransu De
 */
public final class OpenRouterTransientChoiceException extends TransientAiException implements OpenRouterChoiceFailure {

	private final @Nullable OpenRouterChoiceErrorDetails errorDetails;

	public OpenRouterTransientChoiceException(String message, @Nullable OpenRouterChoiceErrorDetails errorDetails) {
		super(message);
		this.errorDetails = errorDetails;
	}

	@Override
	public @Nullable OpenRouterChoiceErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

}
