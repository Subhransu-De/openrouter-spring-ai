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
public final class OpenRouterNonTransientApiException extends NonTransientAiException
		implements OpenRouterHttpException {

	private final @Nullable HttpStatusCode statusCode;

	private final @Nullable String responseBody;

	private final @Nullable OpenRouterErrorDetails errorDetails;

	private final @Nullable OpenRouterRetryAfter retryAfter;

	private final @Nullable String endpoint;

	public OpenRouterNonTransientApiException(String message, @Nullable HttpStatusCode statusCode,
			@Nullable String responseBody, @Nullable OpenRouterErrorDetails errorDetails,
			@Nullable OpenRouterRetryAfter retryAfter, @Nullable String endpoint) {
		super(message);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.errorDetails = errorDetails;
		this.retryAfter = retryAfter;
		this.endpoint = endpoint;
	}

	@Override
	public @Nullable HttpStatusCode getStatusCode() {
		return this.statusCode;
	}

	@Override
	public @Nullable String getResponseBody() {
		return this.responseBody;
	}

	@Override
	public @Nullable OpenRouterErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

	@Override
	public @Nullable OpenRouterRetryAfter getRetryAfter() {
		return this.retryAfter;
	}

	@Override
	public @Nullable String getEndpoint() {
		return this.endpoint;
	}

}
