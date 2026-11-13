package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceErrorDetails;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Converts an embedded OpenRouter choice error into Spring AI's retry taxonomy.
 *
 * <p>
 * This factory is deliberately separate from normal generation mapping and delegates
 * retry classification to the same policy used for HTTP failures. As on the HTTP path, it
 * classifies the original provider values before redacting the retained ones.
 *
 * @author Subhransu De
 */
final class OpenRouterChoiceErrorExceptionFactory {

	static final int MAX_DIAGNOSTIC_LENGTH = 500;

	private static final Set<String> NATIVE_FAILURE_REASONS = Set.of("error", "insufficient_system_resources",
			"insufficient_quota");

	private final OpenRouterDiagnosticSanitizer sanitizer;

	OpenRouterChoiceErrorExceptionFactory(OpenRouterDiagnosticSanitizer sanitizer) {
		this.sanitizer = sanitizer;
	}

	static boolean isFailure(Choice choice) {
		return choice.error() != null || failureReason(choice) != null;
	}

	RuntimeException create(ChatCompletionResponse response, Choice choice) {
		return create(response.id(), response.model(), response.provider(), choice, partialOutput(choice));
	}

	RuntimeException create(ChatCompletionChunk chunk, Choice choice) {
		return create(chunk.id(), chunk.model(), chunk.provider(), choice, partialOutput(choice));
	}

	RuntimeException create(ChatCompletionChunk chunk, Choice choice, String partialOutput, boolean truncated) {
		return create(chunk.id(), chunk.model(), chunk.provider(), choice, bounded(partialOutput, truncated));
	}

	private RuntimeException create(@Nullable String responseId, @Nullable String model, @Nullable String provider,
			Choice choice, Diagnostic partialOutput) {
		ChoiceError error = choice.error();
		String nativeFailureReason = failureReason(choice);
		Map<String, @Nullable Object> metadata = error != null ? error.metadata()
				: nativeFailureReason != null ? Map.of("native_finish_reason", nativeFailureReason) : Map.of();
		String errorType = metadataText(metadata, "error_type");
		if (error == null && !StringUtils.hasText(errorType)) {
			errorType = nativeErrorType(nativeFailureReason);
		}
		String providerCode = metadataText(metadata, "provider_code");
		String code = error != null ? error.code() : nativeFailureReason;
		String errorMessage = error != null ? error.message()
				: "OpenRouter generation ended with native failure reason " + nativeFailureReason;
		Integer statusCode = numericCode(code);
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode != null ? statusCode : -1,
				errorType, code, errorMessage);
		String nativeClassification = nativeErrorType(nativeFailureReason);
		if (!StringUtils.hasText(errorType) && category == OpenRouterErrorCategory.UNKNOWN
				&& StringUtils.hasText(nativeClassification)) {
			category = OpenRouterErrorClassifier.category(-1, nativeClassification, null, null);
		}
		Object nativeFinishReason = choice.nativeFinishReason() != null ? choice.nativeFinishReason()
				: nativeFailureReason;
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails(sanitize(responseId), sanitize(model),
				sanitize(provider), choice.index(), sanitize(choice.finishReason()),
				this.sanitizer.sanitizeDiagnosticValue(nativeFinishReason), sanitize(code), sanitize(errorMessage),
				sanitize(errorType), sanitize(providerCode), this.sanitizer.sanitizeMetadata(metadata),
				partialOutput.text(), partialOutput.truncated(), category);
		String message = "OpenRouter chat-completion choice failed in /chat/completions"
				+ (choice.index() != null ? " for choice " + choice.index() : "") + ", category " + category;
		if (OpenRouterErrorClassifier.isTransient(category)) {
			return new OpenRouterTransientChoiceException(message, details);
		}
		return new OpenRouterNonTransientChoiceException(message, details);
	}

	private static @Nullable String failureReason(Choice choice) {
		String nativeReason = Objects.toString(choice.nativeFinishReason(), null);
		if (nativeReason != null && NATIVE_FAILURE_REASONS.contains(nativeReason)) {
			return nativeReason;
		}
		return choice.finishReason() != null && NATIVE_FAILURE_REASONS.contains(choice.finishReason())
				? choice.finishReason() : null;
	}

	private @Nullable String nativeErrorType(@Nullable String nativeReason) {
		return switch (nativeReason != null ? nativeReason : "") {
			case "insufficient_system_resources" -> "provider_unavailable";
			case "insufficient_quota" -> "payment_required";
			case "error" -> "unmapped";
			default -> null;
		};
	}

	private @Nullable Integer numericCode(@Nullable String code) {
		try {
			return Integer.parseInt(code);
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private Diagnostic partialOutput(Choice choice) {
		var delta = choice.delta();
		var message = choice.message();
		Object content = message != null ? message.content() : delta != null ? delta.content() : null;
		return bounded(AssistantContentMapper.map(content).text(), false);
	}

	private Diagnostic bounded(@Nullable String value, boolean alreadyTruncated) {
		// Redact before cutting, so a key that crosses the bound leaves no prefix.
		String normalized = sanitize(value);
		if (!StringUtils.hasText(normalized)) {
			return new Diagnostic(null, alreadyTruncated);
		}
		boolean cut = normalized.length() > MAX_DIAGNOSTIC_LENGTH;
		if (!cut && !alreadyTruncated) {
			return new Diagnostic(normalized, false);
		}
		if (!cut && normalized.length() < MAX_DIAGNOSTIC_LENGTH && value != null && value.endsWith(" ")) {
			// A stream cut can fall just after a space, which sanitizing trims as
			// trailing.
			normalized += " ";
		}
		int end = cut ? MAX_DIAGNOSTIC_LENGTH : normalized.length();
		// Either cut can fall inside a surrogate pair; drop the unpaired half.
		if (Character.isHighSurrogate(normalized.charAt(end - 1))) {
			end--;
		}
		return new Diagnostic(normalized.substring(0, end) + "...", true);
	}

	private @Nullable String sanitize(@Nullable String value) {
		return this.sanitizer.sanitize(value);
	}

	private @Nullable String metadataText(Map<String, @Nullable Object> metadata, String key) {
		Object value = metadata.get(key);
		return value != null ? value.toString() : null;
	}

	private record Diagnostic(@Nullable String text, boolean truncated) {
	}

}
