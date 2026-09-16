package de.subhransu.openrouter.springai.errors;

import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatusCode;

/**
 * An OpenRouter HTTP or in-band Responses failure that Spring AI's default retry policy
 * may retry.
 *
 * @author Subhransu De
 */
public final class OpenRouterTransientApiException extends TransientAiException implements OpenRouterHttpException {

	private final HttpStatusCode statusCode;

	private final String responseBody;

	private final OpenRouterErrorDetails errorDetails;

	private final OpenRouterRetryAfter retryAfter;

	private final String endpoint;

	public OpenRouterTransientApiException(String message, HttpStatusCode statusCode, String responseBody,
			OpenRouterErrorDetails errorDetails, OpenRouterRetryAfter retryAfter, String endpoint) {
		super(message);
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.errorDetails = errorDetails;
		this.retryAfter = retryAfter;
		this.endpoint = endpoint;
	}

	@Override
	public HttpStatusCode getStatusCode() {
		return this.statusCode;
	}

	@Override
	public String getResponseBody() {
		return this.responseBody;
	}

	@Override
	public OpenRouterErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

	@Override
	public OpenRouterRetryAfter getRetryAfter() {
		return this.retryAfter;
	}

	@Override
	public String getEndpoint() {
		return this.endpoint;
	}

}
