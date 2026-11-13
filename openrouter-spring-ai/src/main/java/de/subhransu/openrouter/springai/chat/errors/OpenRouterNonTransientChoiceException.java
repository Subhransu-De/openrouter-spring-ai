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

	private final OpenRouterChoiceErrorDetails errorDetails;

	public OpenRouterNonTransientChoiceException(String message, @Nullable OpenRouterChoiceErrorDetails errorDetails) {
		super(message);
		this.errorDetails = errorDetails != null ? errorDetails : OpenRouterChoiceErrorDetails.empty();
	}

	@Override
	public OpenRouterChoiceErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

}
