package de.subhransu.openrouter.springai.errors;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

/**
 * Parsed details from OpenRouter's error envelope.
 *
 * @param code provider or HTTP-compatible error code
 * @param message bounded, single-line provider diagnostic excerpt
 * @param errorType OpenRouter's canonical {@code error_type}, when supplied
 * @param providerCode the upstream provider's original code, when supplied
 * @param metadata the complete provider metadata object, or a {@link MissingNode} when
 * the error has none; never {@code null} after construction
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
		// MissingNode is an immutable singleton, path() on it is safe, and Jackson writes
		// it as null, as it wrote the absent value before.
		metadata = metadata != null ? OpenRouterExceptionMessage.sanitizeMetadata(metadata, null)
				: MissingNode.getInstance();
		category = category != null ? category : OpenRouterErrorCategory.UNKNOWN;
	}

	public OpenRouterErrorDetails(@Nullable String code, @Nullable String message, @Nullable String errorType,
			@Nullable String providerCode, @Nullable JsonNode metadata) {
		this(code, message, errorType, providerCode, metadata,
				OpenRouterErrorClassifier.category(errorType, code, message));
	}

	/**
	 * Return details that carry nothing: every value {@code null}, missing metadata and
	 * the {@link OpenRouterErrorCategory#UNKNOWN} category.
	 * @return empty details
	 */
	public static OpenRouterErrorDetails empty() {
		return new OpenRouterErrorDetails(null, null, null, null, null, null);
	}

	/**
	 * Return the provider metadata. Use {@link JsonNode#isMissingNode()} to test whether
	 * the error had a metadata value.
	 * @return the metadata, never {@code null}
	 */
	public JsonNode metadata() {
		return Objects.requireNonNull(this.metadata);
	}

	public OpenRouterErrorCategory category() {
		return Objects.requireNonNull(this.category);
	}

}
