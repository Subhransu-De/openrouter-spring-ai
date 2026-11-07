package de.subhransu.openrouter.springai.errors;

import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Applies {@link OpenRouterExceptionMessage}'s diagnostic policy and also redacts one
 * client's configured API key, including its percent-encoded and JSON unicode-escaped
 * forms.
 *
 * <p>
 * The key is compiled into a private pattern once per client and is never exposed. Each
 * result is detached from its input.
 *
 * @author Subhransu De
 */
public final class OpenRouterDiagnosticSanitizer {

	private static final OpenRouterDiagnosticSanitizer KEYLESS = new OpenRouterDiagnosticSanitizer(null);

	private final @Nullable Pattern apiKey;

	private OpenRouterDiagnosticSanitizer(@Nullable Pattern apiKey) {
		this.apiKey = apiKey;
	}

	/**
	 * Create a sanitizer that redacts the given key.
	 * @param apiKey configured API key; blank applies only the generic policy
	 * @return the sanitizer
	 */
	public static OpenRouterDiagnosticSanitizer forApiKey(@Nullable String apiKey) {
		return StringUtils.hasText(apiKey)
				? new OpenRouterDiagnosticSanitizer(OpenRouterExceptionMessage.encodedKeyPattern(apiKey)) : KEYLESS;
	}

	/**
	 * Return the sanitizer that applies only the generic credential patterns.
	 * @return the shared keyless sanitizer
	 */
	public static OpenRouterDiagnosticSanitizer keyless() {
		return KEYLESS;
	}

	@Contract("!null -> !null")
	public @Nullable String sanitize(@Nullable String value) {
		return OpenRouterExceptionMessage.sanitize(value, this.apiKey);
	}

	@Contract("!null -> !null")
	public @Nullable JsonNode sanitizeMetadata(@Nullable JsonNode metadata) {
		return OpenRouterExceptionMessage.sanitizeMetadata(metadata, this.apiKey);
	}

	public Map<String, @Nullable Object> sanitizeMetadata(@Nullable Map<String, ?> metadata) {
		return OpenRouterExceptionMessage.sanitizeMetadata(metadata, this.apiKey);
	}

	@Contract("!null -> !null")
	public @Nullable Object sanitizeDiagnosticValue(@Nullable Object value) {
		return OpenRouterExceptionMessage.sanitizeMetadataValue(value, this.apiKey);
	}

}
