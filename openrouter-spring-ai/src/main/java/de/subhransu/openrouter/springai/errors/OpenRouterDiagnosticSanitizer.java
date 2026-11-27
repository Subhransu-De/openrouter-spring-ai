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
	 * Create a sanitizer that also redacts the given values, such as the custom header
	 * values of one request, which a gateway can echo in an error body.
	 * @param values values to redact; blank values are ignored
	 * @return this sanitizer when there is nothing more to redact, otherwise a new one
	 */
	public OpenRouterDiagnosticSanitizer redacting(Collection<String> values) {
		// Longer values first, so a value that contains another is redacted whole.
		List<String> patterns = values.stream()
			.filter(StringUtils::hasText)
			.distinct()
			.sorted(Comparator.comparingInt(String::length).reversed())
			.map(value -> OpenRouterExceptionMessage.encodedKeyPattern(value).pattern())
			.collect(Collectors.toCollection(ArrayList::new));
		if (patterns.isEmpty()) {
			return this;
		}
		if (this.apiKey != null) {
			patterns.add(this.apiKey.pattern());
		}
		return new OpenRouterDiagnosticSanitizer(Pattern.compile(String.join("|", patterns)));
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
