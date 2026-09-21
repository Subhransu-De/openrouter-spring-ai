package de.subhransu.openrouter.springai.api.errors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
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

	public static OpenRouterApiException create(String fallbackMessage, @Nullable String responseBody,
			@Nullable StreamError error, @Nullable String rootErrorType) {
		String parsedMessage = error != null ? error.message() : null;
		String diagnosticMessage = StringUtils.hasText(parsedMessage) ? parsedMessage : fallbackMessage;
		String errorType = rootErrorType;
		JsonNode metadata = error != null ? error.metadata() : null;
		if (!StringUtils.hasText(errorType) && error != null) {
			errorType = error.errorType();
		}
		if (!StringUtils.hasText(errorType) && metadata != null) {
			errorType = text(metadata.get("error_type"));
		}
		String code = error != null ? error.code() : null;
		int statusCode = numericCode(code);
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode, errorType, code,
				diagnosticMessage);
		if (statusCode < 400 || statusCode > 599) {
			statusCode = syntheticStatus(category);
		}
		OpenRouterErrorDetails details = new OpenRouterErrorDetails(code, diagnosticMessage, errorType,
				metadata != null ? text(metadata.get("provider_code")) : null,
				metadata != null ? metadata.deepCopy() : null, category);
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

	private static @Nullable String text(@Nullable JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		return value.isString() ? value.stringValue() : value.toString();
	}

}
