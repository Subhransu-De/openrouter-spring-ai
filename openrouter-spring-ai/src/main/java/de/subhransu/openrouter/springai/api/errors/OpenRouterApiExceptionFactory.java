package de.subhransu.openrouter.springai.api.errors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorEnvelope;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;

/**
 * Creates structured legacy-compatible exceptions for errors carried over HTTP 200.
 *
 * @author Subhransu De
 */
public final class OpenRouterApiExceptionFactory {

	private OpenRouterApiExceptionFactory() {
	}

	public static RuntimeException createRetryable(String message, @Nullable String responseBody,
			@Nullable StreamError error, @Nullable String errorType, String endpoint) {
		OpenRouterApiException failure = create(message, responseBody, error, errorType);
		if (OpenRouterErrorClassifier.isTransient(failure.getCategory())) {
			return new OpenRouterTransientApiException(message, failure.getStatusCode(), failure.getResponseBody(),
					failure.getErrorDetails(), null, endpoint);
		}
		return failure;
	}

	public static OpenRouterApiException create(String fallbackMessage, @Nullable String responseBody,
			@Nullable StreamError error, @Nullable String rootErrorType) {
		String parsedMessage = error != null ? error.message() : null;
		String diagnosticMessage = StringUtils.hasText(parsedMessage) ? parsedMessage : fallbackMessage;
		String code = error != null ? error.code() : null;
		int statusCode = numericCode(code);
		OpenRouterErrorEnvelope envelope = OpenRouterErrorEnvelope.interpret(statusCode, rootErrorType,
				error != null ? error.errorType() : null, code, diagnosticMessage,
				error != null ? error.metadata() : null);
		if (statusCode < 400 || statusCode > 599) {
			statusCode = syntheticStatus(envelope.category());
		}
		JsonNode metadata = envelope.metadata();
		OpenRouterErrorDetails details = new OpenRouterErrorDetails(envelope.code(), envelope.message(),
				envelope.errorType(), envelope.providerCode(), metadata != null ? metadata.deepCopy() : null,
				envelope.category());
		return new OpenRouterApiException(fallbackMessage, HttpStatusCode.valueOf(statusCode), responseBody, details);
	}

	private static int numericCode(@Nullable String code) {
		try {
			return Integer.parseInt(code);
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static int syntheticStatus(OpenRouterErrorCategory category) {
		return switch (category) {
			case AUTHENTICATION -> 401;
			case AUTHORIZATION, CONTENT_FILTER_REFUSAL -> 403;
			case BILLING_CREDITS -> 402;
			case RATE_LIMIT -> 429;
			case INVALID_REQUEST, UNSUPPORTED_PARAMETER -> 400;
			case TIMEOUT -> 504;
			case PROVIDER_UNAVAILABLE, UNKNOWN -> 500;
		};
	}

}
