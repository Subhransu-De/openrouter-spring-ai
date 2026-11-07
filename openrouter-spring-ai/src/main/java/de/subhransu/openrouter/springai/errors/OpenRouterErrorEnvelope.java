package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * Interpreted fields of an OpenRouter error envelope, shared by HTTP and in-band errors.
 *
 * <p>
 * Values are unsanitized so that classification sees the original provider text.
 * {@link OpenRouterErrorDetails} applies generic sanitization; a caller may redact more,
 * such as the configured API key, before building it.
 *
 * @param code provider or HTTP-compatible error code
 * @param message provider diagnostic message, normally {@code error.message}
 * @param errorType the first {@code error_type} with text from the root, the nested error
 * object, then its metadata; otherwise the last candidate read, which may be blank or
 * {@code null}
 * @param providerCode the upstream provider's original code from the metadata
 * @param metadata the provider metadata object
 * @param category category classified from the original values
 * @author Subhransu De
 */
public record OpenRouterErrorEnvelope(@Nullable String code, @Nullable String message, @Nullable String errorType,
		@Nullable String providerCode, @Nullable JsonNode metadata, OpenRouterErrorCategory category) {

	/**
	 * Interpret the envelope fields and classify them against an HTTP-compatible status.
	 * @param statusCode HTTP status, or the numeric in-band code
	 * @param rootErrorType root {@code error_type}
	 * @param nestedErrorType {@code error.error_type}
	 * @param code {@code error.code}
	 * @param message diagnostic message, normally {@code error.message}
	 * @param metadata {@code error.metadata}
	 * @return the interpreted fields and category
	 */
	public static OpenRouterErrorEnvelope interpret(int statusCode, @Nullable String rootErrorType,
			@Nullable String nestedErrorType, @Nullable String code, @Nullable String message,
			@Nullable JsonNode metadata) {
		String errorType = rootErrorType;
		if (!StringUtils.hasText(errorType)) {
			errorType = nestedErrorType;
		}
		if (!StringUtils.hasText(errorType) && metadata != null) {
			errorType = text(metadata.get("error_type"));
		}
		String providerCode = metadata != null ? text(metadata.get("provider_code")) : null;
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode, errorType, code, message);
		return new OpenRouterErrorEnvelope(code, message, errorType, providerCode, metadata, category);
	}

	static @Nullable String text(@Nullable JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		return node.isString() ? node.stringValue() : node.toString();
	}

}
