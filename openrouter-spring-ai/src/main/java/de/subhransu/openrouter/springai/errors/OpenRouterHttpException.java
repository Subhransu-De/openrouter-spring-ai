package de.subhransu.openrouter.springai.errors;

import org.springframework.http.HttpStatusCode;

/**
 * Common inspectable contract implemented by retryable and non-retryable OpenRouter HTTP
 * and in-band Responses failures.
 *
 * @author Subhransu De
 */
public interface OpenRouterHttpException {

	String getMessage();

	/**
	 * Return the HTTP status, or a status derived from an in-band Responses error.
	 * @return actual or derived failure status
	 */
	HttpStatusCode getStatusCode();

	/**
	 * Return a bounded, single-line, credential-safe excerpt of the untrusted provider
	 * response body.
	 * @return provider diagnostic excerpt, or {@code null}
	 */
	String getResponseBody();

	OpenRouterErrorDetails getErrorDetails();

	default OpenRouterErrorCategory getCategory() {
		OpenRouterErrorDetails details = getErrorDetails();
		return details != null ? details.category() : OpenRouterErrorCategory.UNKNOWN;
	}

	OpenRouterRetryAfter getRetryAfter();

	String getEndpoint();

}
