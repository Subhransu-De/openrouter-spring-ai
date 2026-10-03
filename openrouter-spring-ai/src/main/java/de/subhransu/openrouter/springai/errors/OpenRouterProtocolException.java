package de.subhransu.openrouter.springai.errors;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * An OpenRouter response does not satisfy the minimum protocol shape.
 *
 * @author Subhransu De
 */
public final class OpenRouterProtocolException extends NonTransientAiException {

	public OpenRouterProtocolException(String message) {
		super(message);
	}

	public OpenRouterProtocolException(String message, Throwable cause) {
		super(message, cause);
	}

}
