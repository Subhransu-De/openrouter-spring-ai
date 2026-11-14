package de.subhransu.openrouter.springai.api.errors;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.dto.ResponseError;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorEnvelope;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;

/**
 * Creates structured legacy-compatible exceptions for errors carried over HTTP 200.
 *
 * <p>
 * Like the HTTP path, it classifies the original provider values, then redacts every
 * retained value with the client's sanitizer.
 *
 * @author Subhransu De
 */
public final class OpenRouterInBandErrorExceptionFactory {

	private OpenRouterInBandErrorExceptionFactory() {
	}

	public static RuntimeException createRetryable(String message, @Nullable String responseBody,
			@Nullable ResponseError error, @Nullable String errorType, String endpoint,
			OpenRouterDiagnosticSanitizer sanitizer) {
		OpenRouterInBandErrorException failure = create(message, responseBody, error, errorType, endpoint, sanitizer);
		if (OpenRouterErrorClassifier.isTransient(failure.getCategory())) {
			return new OpenRouterTransientApiException(Objects.requireNonNull(failure.getMessage()),
					failure.getStatusCode(), failure.getResponseBody(), failure.getErrorDetails(), null, endpoint);
		}
		return failure;
	}

	/**
	 * Create the exception for an error in a successful response. Its message names the
	 * endpoint, the actual or derived status and the category; provider text stays in the
	 * details. The fallback message stands in for a missing provider message in the
	 * details.
	 */
	public static OpenRouterInBandErrorException create(String fallbackMessage, @Nullable String responseBody,
			@Nullable ResponseError error, @Nullable String rootErrorType, String endpoint,
			OpenRouterDiagnosticSanitizer sanitizer) {
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
		OpenRouterErrorDetails details = new OpenRouterErrorDetails(sanitizer.sanitize(envelope.code()),
				sanitizer.sanitize(envelope.message()), sanitizer.sanitize(envelope.errorType()),
				sanitizer.sanitize(envelope.providerCode()), sanitizer.sanitizeMetadata(envelope.metadata()),
				envelope.category());
		HttpStatusCode status = HttpStatusCode.valueOf(statusCode);
		String message = fallbackMessage + " in " + endpoint + " with status " + status + ", category "
				+ details.category();
		return new OpenRouterInBandErrorException(message, status, sanitizer.sanitize(responseBody), details);
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
