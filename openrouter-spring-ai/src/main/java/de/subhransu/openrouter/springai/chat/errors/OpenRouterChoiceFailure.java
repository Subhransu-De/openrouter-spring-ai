package de.subhransu.openrouter.springai.chat.errors;

import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterFailure;

/**
 * Common structured-details contract for transient and non-transient choice failures.
 *
 * @author Subhransu De
 */
public interface OpenRouterChoiceFailure extends OpenRouterFailure {

	/**
	 * Return the choice error details. Implementations must not return {@code null}.
	 * @return the details, never {@code null}
	 */
	OpenRouterChoiceErrorDetails getErrorDetails();

	@Override
	default OpenRouterErrorCategory getCategory() {
		return getErrorDetails().category();
	}

}
