package de.subhransu.openrouter.springai.chat.errors;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;

/**
 * Common structured-details contract for transient and non-transient choice failures.
 *
 * @author Subhransu De
 */
public interface OpenRouterChoiceFailure {

	@Nullable OpenRouterChoiceErrorDetails getErrorDetails();

	default OpenRouterErrorCategory getCategory() {
		OpenRouterChoiceErrorDetails details = getErrorDetails();
		return details != null ? details.category() : OpenRouterErrorCategory.UNKNOWN;
	}

}
