package de.subhransu.openrouter.springai.chat.errors;

import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Structured diagnostics for an error embedded in a blocking or streamed chat-completion
 * choice.
 *
 * <p>
 * Provider-controlled strings and metadata are retained as bounded, single-line,
 * credential-safe excerpts for programmatic inspection. They remain untrusted and are
 * deliberately excluded from the host exception message.
 *
 * @param responseId OpenRouter response identifier
 * @param model model reported by OpenRouter
 * @param provider provider reported by OpenRouter
 * @param choiceIndex failed choice index
 * @param finishReason failed choice finish reason
 * @param nativeFinishReason original provider-native finish reason
 * @param code provider or HTTP-compatible error code
 * @param message provider diagnostic message
 * @param errorType OpenRouter's stable {@code error_type}, when supplied
 * @param providerCode upstream provider's original code, when supplied
 * @param metadata complete provider metadata object
 * @param partialOutput bounded partial assistant output
 * @param partialOutputTruncated whether partial assistant output was truncated
 * @param category stable application-facing failure category
 * @author Subhransu De
 */
public record OpenRouterChoiceErrorDetails(@Nullable String responseId, @Nullable String model,
		@Nullable String provider, @Nullable Integer choiceIndex, @Nullable String finishReason,
		@Nullable Object nativeFinishReason, @Nullable String code, @Nullable String message,
		@Nullable String errorType, @Nullable String providerCode, @Nullable Map<String, @Nullable Object> metadata,
		@Nullable String partialOutput, boolean partialOutputTruncated, @Nullable OpenRouterErrorCategory category) {

	public OpenRouterChoiceErrorDetails(@Nullable String responseId, @Nullable String model, @Nullable String provider,
			@Nullable Integer choiceIndex, @Nullable String finishReason, @Nullable String code,
			@Nullable String message, @Nullable String errorType, @Nullable String providerCode,
			@Nullable Map<String, @Nullable Object> metadata, @Nullable String partialOutput,
			boolean partialOutputTruncated) {
		this(responseId, model, provider, choiceIndex, finishReason, null, code, message, errorType, providerCode,
				metadata, partialOutput, partialOutputTruncated,
				OpenRouterErrorClassifier.category(errorType, code, message));
	}

	public OpenRouterChoiceErrorDetails {
		responseId = OpenRouterExceptionMessage.sanitize(responseId);
		model = OpenRouterExceptionMessage.sanitize(model);
		provider = OpenRouterExceptionMessage.sanitize(provider);
		finishReason = OpenRouterExceptionMessage.sanitize(finishReason);
		nativeFinishReason = OpenRouterExceptionMessage.sanitizeDiagnosticValue(nativeFinishReason);
		code = OpenRouterExceptionMessage.sanitize(code);
		message = OpenRouterExceptionMessage.sanitize(message);
		errorType = OpenRouterExceptionMessage.sanitize(errorType);
		providerCode = OpenRouterExceptionMessage.sanitize(providerCode);
		metadata = Collections
			.unmodifiableMap(new LinkedHashMap<>(OpenRouterExceptionMessage.sanitizeMetadata(metadata)));
		partialOutput = OpenRouterExceptionMessage.sanitize(partialOutput);
		category = category != null ? category : OpenRouterErrorCategory.UNKNOWN;
	}

	public OpenRouterErrorCategory category() {
		return Objects.requireNonNull(this.category);
	}

	public Map<String, @Nullable Object> metadata() {
		return Objects.requireNonNull(this.metadata);
	}

}
