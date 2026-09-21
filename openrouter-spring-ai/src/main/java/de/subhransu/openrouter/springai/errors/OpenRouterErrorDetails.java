package de.subhransu.openrouter.springai.errors;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Parsed details from OpenRouter's error envelope.
 *
 * @param code provider or HTTP-compatible error code
 * @param message bounded, single-line provider diagnostic excerpt
 * @param errorType OpenRouter's canonical {@code error_type}, when supplied
 * @param providerCode the upstream provider's original code, when supplied
 * @param metadata the complete provider metadata object, when supplied
 * @param category stable application-facing failure category
 * @author Subhransu De
 */
public record OpenRouterErrorDetails(@Nullable String code, @Nullable String message, @Nullable String errorType,
		@Nullable String providerCode, @Nullable JsonNode metadata, @Nullable OpenRouterErrorCategory category) {

	public OpenRouterErrorDetails {
		code = OpenRouterExceptionMessage.sanitize(code);
		message = OpenRouterExceptionMessage.sanitize(message);
		errorType = OpenRouterExceptionMessage.sanitize(errorType);
		providerCode = OpenRouterExceptionMessage.sanitize(providerCode);
		metadata = OpenRouterExceptionMessage.sanitizeMetadata(metadata, null);
		category = category != null ? category : OpenRouterErrorCategory.UNKNOWN;
	}

	public OpenRouterErrorDetails(@Nullable String code, @Nullable String message, @Nullable String errorType,
			@Nullable String providerCode, @Nullable JsonNode metadata) {
		this(code, message, errorType, providerCode, metadata,
				OpenRouterErrorClassifier.category(errorType, code, message));
	}

	public OpenRouterErrorCategory category() {
		return Objects.requireNonNull(this.category);
	}

}
