package de.subhransu.openrouter.springai.errors;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

/**
 * Common inspectable contract implemented by retryable and non-retryable OpenRouter HTTP
 * and in-band Responses failures.
 *
 * <p>
 * The interface is sealed: every implementation is one of the permitted exception
 * classes, so a caught {@code OpenRouterHttpFailure} is always a
 * {@link RuntimeException}.
 *
 * @author Subhransu De
 */
public sealed interface OpenRouterHttpFailure extends OpenRouterFailure
		permits OpenRouterTransientApiException, OpenRouterNonTransientApiException, OpenRouterLimitExceededException {

	@Nullable String getMessage();

	/**
	 * Return the shared HTTP failure details.
	 * @return failure details
	 */
	OpenRouterHttpFailureDetails getFailureDetails();

	/**
	 * Return the HTTP status, or a status derived from an in-band Responses error.
	 * @return actual or derived failure status
	 */
	default @Nullable HttpStatusCode getStatusCode() {
		return getFailureDetails().statusCode();
	}

	/**
	 * Return a bounded, single-line, credential-safe excerpt of the untrusted provider
	 * response body.
	 * @return provider diagnostic excerpt, or {@code null}
	 */
	default @Nullable String getResponseBody() {
		return getFailureDetails().responseBody();
	}

	/**
	 * Return the parsed error details. A limit the library raised on stream state has
	 * empty details.
	 * @return the details, never {@code null}
	 */
	default OpenRouterErrorDetails getErrorDetails() {
		return getFailureDetails().errorDetails();
	}

	@Override
	default OpenRouterErrorCategory getCategory() {
		return getErrorDetails().category();
	}

	default @Nullable OpenRouterRetryAfter getRetryAfter() {
		return getFailureDetails().retryAfter();
	}

	/**
	 * Return the delay that the {@code Retry-After} header asks for.
	 * @return the delay, or {@code null} when the response had no header or a value that
	 * could not be parsed
	 */
	default @Nullable Duration getRetryDelay() {
		OpenRouterRetryAfter retryAfter = getRetryAfter();
		return retryAfter != null ? retryAfter.delay() : null;
	}

	default @Nullable String getEndpoint() {
		return getFailureDetails().endpoint();
	}

}
