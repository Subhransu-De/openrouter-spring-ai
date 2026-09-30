package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

/**
 * HTTP failure details shared by every {@link OpenRouterHttpException}.
 *
 * @param statusCode actual or derived failure status
 * @param responseBody bounded, single-line, credential-safe excerpt of the untrusted
 * provider response body
 * @param errorDetails parsed error envelope details
 * @param retryAfter parsed {@code Retry-After} header
 * @param endpoint OpenRouter endpoint that failed
 * @author Subhransu De
 */
public record OpenRouterHttpFailure(@Nullable HttpStatusCode statusCode, @Nullable String responseBody,
		@Nullable OpenRouterErrorDetails errorDetails, @Nullable OpenRouterRetryAfter retryAfter,
		@Nullable String endpoint) {

}
