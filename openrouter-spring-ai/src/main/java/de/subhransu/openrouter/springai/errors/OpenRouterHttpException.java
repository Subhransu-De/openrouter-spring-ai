package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

/**
 * Common inspectable contract implemented by retryable and non-retryable OpenRouter HTTP
 * and in-band Responses failures.
 *
 * <p>
 * The interface is sealed: every implementation is one of the permitted exception
 * classes, so a caught {@code OpenRouterHttpException} is always a
 * {@link RuntimeException}.
 *
 * @author Subhransu De
 */
public sealed interface OpenRouterHttpException
		permits OpenRouterTransientApiException, OpenRouterNonTransientApiException, OpenRouterLimitExceededException {

	@Nullable String getMessage();

	/**
	 * Return the shared HTTP failure details.
	 * @return failure details
	 */
	OpenRouterHttpFailure getFailure();

	/**
	 * Return the HTTP status, or a status derived from an in-band Responses error.
	 * @return actual or derived failure status
	 */
	default @Nullable HttpStatusCode getStatusCode() {
		return getFailure().statusCode();
	}

	/**
	 * Return a bounded, single-line, credential-safe excerpt of the untrusted provider
	 * response body.
	 * @return provider diagnostic excerpt, or {@code null}
	 */
	default @Nullable String getResponseBody() {
		return getFailure().responseBody();
	}

	default @Nullable OpenRouterErrorDetails getErrorDetails() {
		return getFailure().errorDetails();
	}

	default OpenRouterErrorCategory getCategory() {
		OpenRouterErrorDetails details = getErrorDetails();
		return details != null ? details.category() : OpenRouterErrorCategory.UNKNOWN;
	}

	default @Nullable OpenRouterRetryAfter getRetryAfter() {
		return getFailure().retryAfter();
	}

	default @Nullable String getEndpoint() {
		return getFailure().endpoint();
	}

}
