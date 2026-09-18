package de.subhransu.openrouter.springai.errors;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;

/**
 * Shared retry classification for HTTP failures and in-band OpenRouter errors.
 *
 * @author Subhransu De
 */
@org.jspecify.annotations.NullUnmarked
public final class OpenRouterErrorClassifier {

	private static final Set<String> TRANSIENT_ERROR_TYPES = Set.of("api_error", "insufficient_system_resources",
			"rate_limit_error", "rate_limit_exceeded", "provider_overloaded", "provider_unavailable", "server",
			"server_error", "timeout", "timeout_error");

	private static final Set<String> AUTHENTICATION_TYPES = Set.of("authentication", "authentication_error",
			"invalid_api_key", "unauthorized");

	private static final Set<String> AUTHORIZATION_TYPES = Set.of("permission_denied", "permission_error", "forbidden");

	private static final Set<String> BILLING_TYPES = Set.of("payment_required", "billing_error", "insufficient_credits",
			"insufficient_quota", "token_limit_exceeded");

	private static final Set<String> RATE_LIMIT_TYPES = Set.of("rate_limit_exceeded", "rate_limit_error");

	private static final Set<String> INVALID_REQUEST_TYPES = Set.of("context_length_exceeded", "invalid_prompt",
			"invalid_request", "invalid_request_error", "max_tokens_exceeded", "not_found", "precondition_failed",
			"payload_too_large", "string_too_long", "unprocessable");

	private static final Set<String> UNSUPPORTED_PARAMETER_TYPES = Set.of("unsupported_parameter",
			"unsupported_parameters", "unsupported_image_format");

	private static final Set<String> PROVIDER_UNAVAILABLE_TYPES = Set.of("api_error", "insufficient_system_resources",
			"provider_overloaded", "provider_unavailable", "server", "server_error");

	private static final Set<String> CONTENT_FILTER_TYPES = Set.of("content_filter", "content_policy_violation",
			"image_content_policy_violation", "moderation", "refusal");

	private static final Set<String> TIMEOUT_TYPES = Set.of("timeout", "timeout_error");

	private OpenRouterErrorClassifier() {
	}

	/**
	 * Determine whether an OpenRouter failure is retryable. A recognized canonical
	 * {@code error_type} takes precedence over the status. Unknown combinations default
	 * to non-transient so a potentially chargeable request is not repeated.
	 */
	public static boolean isTransient(HttpStatusCode statusCode, String errorType) {
		return isTransient(statusCode.value(), errorType);
	}

	/**
	 * Variant for in-band errors whose numeric code has not been converted into an HTTP
	 * status object.
	 */
	public static boolean isTransient(int statusCode, String errorType) {
		if (StringUtils.hasText(errorType)) {
			if (TRANSIENT_ERROR_TYPES.contains(errorType)) {
				return true;
			}
			return false;
		}
		return switch (statusCode) {
			case 408, 429, 500, 502, 503, 504, 524, 529 -> true;
			default -> false;
		};
	}

	/**
	 * Determine retryability from an already normalized error category.
	 */
	public static boolean isTransient(OpenRouterErrorCategory category) {
		return category == OpenRouterErrorCategory.RATE_LIMIT || category == OpenRouterErrorCategory.TIMEOUT
				|| category == OpenRouterErrorCategory.PROVIDER_UNAVAILABLE;
	}

	/**
	 * Classify an OpenRouter failure into an application-facing category. A recognized
	 * canonical {@code error_type} takes precedence, followed by symbolic/native codes,
	 * narrowly-scoped message hints, and finally the HTTP-compatible status.
	 */
	public static OpenRouterErrorCategory category(int statusCode, String errorType, String code, String message) {
		String canonical = normalize(errorType);
		OpenRouterErrorCategory category = category(canonical);
		if (category != null) {
			return category;
		}
		// A supplied but unknown canonical type is intentionally not guessed from an
		// unrelated status. This keeps newly-added OpenRouter values inspectable.
		if (StringUtils.hasText(canonical)) {
			return OpenRouterErrorCategory.UNKNOWN;
		}
		String normalizedCode = normalize(code);
		category = category(normalizedCode);
		if (category != null) {
			return category;
		}
		category = category(numericStatus(normalizedCode));
		if (category != OpenRouterErrorCategory.UNKNOWN) {
			return category;
		}
		String normalizedMessage = normalize(message);
		if (containsUnsupportedParameter(normalizedCode) || containsUnsupportedParameter(normalizedMessage)) {
			return OpenRouterErrorCategory.UNSUPPORTED_PARAMETER;
		}
		if (containsAny(normalizedMessage, "content filter", "content policy", "moderation", "policy refusal",
				"safety refusal")) {
			return OpenRouterErrorCategory.CONTENT_FILTER_REFUSAL;
		}
		return category(statusCode);
	}

	private static OpenRouterErrorCategory category(int statusCode) {
		return switch (statusCode) {
			case 400, 404, 413, 422 -> OpenRouterErrorCategory.INVALID_REQUEST;
			case 401 -> OpenRouterErrorCategory.AUTHENTICATION;
			case 402 -> OpenRouterErrorCategory.BILLING_CREDITS;
			case 403 -> OpenRouterErrorCategory.CONTENT_FILTER_REFUSAL;
			case 408, 504, 524 -> OpenRouterErrorCategory.TIMEOUT;
			case 429 -> OpenRouterErrorCategory.RATE_LIMIT;
			case 500, 502, 503, 529 -> OpenRouterErrorCategory.PROVIDER_UNAVAILABLE;
			default -> OpenRouterErrorCategory.UNKNOWN;
		};
	}

	/**
	 * Classify an in-band error whose code may contain an HTTP-compatible status.
	 */
	public static OpenRouterErrorCategory category(String errorType, String code, String message) {
		return category(numericStatus(code), errorType, code, message);
	}

	private static int numericStatus(String code) {
		try {
			int statusCode = Integer.parseInt(code);
			return statusCode >= 400 && statusCode <= 599 ? statusCode : -1;
		}
		catch (NumberFormatException ex) {
			return -1;
		}
	}

	private static OpenRouterErrorCategory category(String value) {
		if (!StringUtils.hasText(value) || "unmapped".equals(value) || "error".equals(value)) {
			return null;
		}
		if (AUTHENTICATION_TYPES.contains(value)) {
			return OpenRouterErrorCategory.AUTHENTICATION;
		}
		if (AUTHORIZATION_TYPES.contains(value)) {
			return OpenRouterErrorCategory.AUTHORIZATION;
		}
		if (BILLING_TYPES.contains(value)) {
			return OpenRouterErrorCategory.BILLING_CREDITS;
		}
		if (RATE_LIMIT_TYPES.contains(value)) {
			return OpenRouterErrorCategory.RATE_LIMIT;
		}
		if (UNSUPPORTED_PARAMETER_TYPES.contains(value)) {
			return OpenRouterErrorCategory.UNSUPPORTED_PARAMETER;
		}
		if (INVALID_REQUEST_TYPES.contains(value)) {
			return OpenRouterErrorCategory.INVALID_REQUEST;
		}
		if (PROVIDER_UNAVAILABLE_TYPES.contains(value)) {
			return OpenRouterErrorCategory.PROVIDER_UNAVAILABLE;
		}
		if (CONTENT_FILTER_TYPES.contains(value)) {
			return OpenRouterErrorCategory.CONTENT_FILTER_REFUSAL;
		}
		if (TIMEOUT_TYPES.contains(value)) {
			return OpenRouterErrorCategory.TIMEOUT;
		}
		return null;
	}

	private static boolean containsUnsupportedParameter(String value) {
		return containsAny(value, "unsupported parameter", "unsupported_parameter", "parameter is not supported",
				"does not support parameter");
	}

	private static boolean containsAny(String value, String... candidates) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		for (String candidate : candidates) {
			if (value.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	private static String normalize(String value) {
		return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
	}

}
