package de.subhransu.openrouter.springai.errors;

import org.springframework.ai.retry.NonTransientAiException;

/**
 * A response ended before protocol completion. Not retryable because earlier tool calls
 * may already have caused side effects.
 *
 * @author Subhransu De
 */
public final class OpenRouterTruncatedResponseException extends NonTransientAiException implements OpenRouterFailure {

	public OpenRouterTruncatedResponseException(String message) {
		super(message);
	}

	/**
	 * A truncated response carries no provider category.
	 * @return {@link OpenRouterErrorCategory#UNKNOWN}
	 */
	@Override
	public OpenRouterErrorCategory getCategory() {
		return OpenRouterErrorCategory.UNKNOWN;
	}

}
