package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatusCode;

/**
 * An OpenRouter HTTP or in-band Responses failure that Spring AI's default retry policy
 * must not retry.
 *
 * @author Subhransu De
 */
public final class OpenRouterNonTransientApiException extends NonTransientAiException implements OpenRouterHttpFailure {

	private final OpenRouterHttpFailureDetails failure;

	public OpenRouterNonTransientApiException(String message, @Nullable HttpStatusCode statusCode,
			@Nullable String responseBody, @Nullable OpenRouterErrorDetails errorDetails,
			@Nullable OpenRouterRetryAfter retryAfter, @Nullable String endpoint) {
		super(message);
		this.failure = new OpenRouterHttpFailureDetails(statusCode, responseBody, errorDetails, retryAfter, endpoint);
	}

	@Override
	public OpenRouterHttpFailureDetails getFailureDetails() {
		return this.failure;
	}

}
