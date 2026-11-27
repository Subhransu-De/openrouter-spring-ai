package de.subhransu.openrouter.springai.errors;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;

/**
 * Applies {@link OpenRouterExceptionMessage}'s diagnostic policy and also redacts one
 * client's configured API key and any {@linkplain #redacting(Collection) request values},
 * including their percent-encoded and JSON unicode-escaped forms.
 *
 * <p>
 * The key is compiled into a private pattern once per client and is never exposed. Each
 * result is detached from its input.
 *
 * @author Subhransu De
 */
public final class OpenRouterDiagnosticSanitizer {

	private static final OpenRouterDiagnosticSanitizer KEYLESS = new OpenRouterDiagnosticSanitizer(List.of());

	// Shorter values are not credentials, and redacting them would mangle diagnostics,
	// for example a header value "3" inside a status code.
	private static final int MIN_REDACTED_LENGTH = 8;

	private final @Nullable Pattern apiKey;

	// The secrets behind the pattern, kept to order a combined pattern longest first.
	private final List<String> secrets;

	private OpenRouterDiagnosticSanitizer(List<String> secrets) {
		this.secrets = secrets;
		// Longer secrets first, so a secret that starts with another is redacted whole.
		this.apiKey = secrets.isEmpty() ? null
				: Pattern.compile(secrets.stream()
					.sorted(Comparator.comparingInt(String::length).reversed())
					.map(secret -> OpenRouterExceptionMessage.encodedKeyPattern(secret).pattern())
					.collect(Collectors.joining("|")));
	}

	/**
	 * Create a sanitizer that redacts the given key.
	 * @param apiKey configured API key; blank applies only the generic policy
	 * @return the sanitizer
	 */
	public static OpenRouterDiagnosticSanitizer forApiKey(@Nullable String apiKey) {
		return StringUtils.hasText(apiKey) ? new OpenRouterDiagnosticSanitizer(List.of(apiKey)) : KEYLESS;
	}

	/**
	 * Create a sanitizer that also redacts the given values, such as the custom header
	 * values of one request, which a gateway can echo in an error body.
	 * @param values values to redact; values shorter than eight characters are ignored,
	 * because they are not credentials and would mangle the diagnostics
	 * @return this sanitizer when there is nothing more to redact, otherwise a new one
	 */
	public OpenRouterDiagnosticSanitizer redacting(Collection<String> values) {
		List<String> combined = new ArrayList<>(this.secrets);
		values.stream()
			.filter(value -> value.strip().length() >= MIN_REDACTED_LENGTH && !combined.contains(value))
			.distinct()
			.forEach(combined::add);
		return combined.size() == this.secrets.size() ? this : new OpenRouterDiagnosticSanitizer(List.copyOf(combined));
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
