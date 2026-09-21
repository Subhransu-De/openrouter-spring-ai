package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

/**
 * Shared policy for host-controlled exception messages and retained provider diagnostics.
 *
 * @author Subhransu De
 */
public final class OpenRouterExceptionMessage {

	/** Maximum length of any retained provider-controlled diagnostic string. */
	public static final int MAX_DIAGNOSTIC_LENGTH = 1000;

	private static final Pattern AUTHORIZATION_JSON = Pattern.compile("(?i)(\"authorization\"\\s*:\\s*\")[^\"]*(\")");

	private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");

	private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i)\\bsk-[a-z0-9_-]{8,}\\b");

	private static final Pattern NAMED_CREDENTIAL = Pattern
		.compile("(?i)((?:api[_-]?key|x-api-key|authorization)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"'}]+");

	private static final Pattern CREDENTIAL_FIELD_NAME = Pattern
		.compile("(?i)(?:api[_-]?key|x-api-key|authorization|token|access[_-]?token)");

	private OpenRouterExceptionMessage() {
	}

	/**
	 * Convert provider-controlled text into a bounded, single-line, credential-safe
	 * diagnostic excerpt. The returned value remains untrusted provider data and must not
	 * be used as a host exception message.
	 * @param value diagnostic text
	 * @return sanitized text, or {@code null} when the input is {@code null}
	 */
	@Contract("!null -> !null")
	public static @Nullable String sanitize(@Nullable String value) {
		return sanitize(value, null);
	}

	@Contract("!null, _ -> !null")
	static @Nullable String sanitize(@Nullable String value, @Nullable String apiKey) {
		if (value == null) {
			return null;
		}
		String safeValue = neutralizeControls(value);
		if (StringUtils.hasText(apiKey)) {
			safeValue = safeValue.replace(apiKey, "[REDACTED]");
		}
		safeValue = AUTHORIZATION_JSON.matcher(safeValue).replaceAll("$1[REDACTED]$2");
		safeValue = BEARER_TOKEN.matcher(safeValue).replaceAll("Bearer [REDACTED]");
		safeValue = OPENAI_STYLE_KEY.matcher(safeValue).replaceAll("[REDACTED]");
		safeValue = NAMED_CREDENTIAL.matcher(safeValue).replaceAll("$1[REDACTED]");
		return bound(safeValue);
	}

	/**
	 * Retained for source compatibility. Provider diagnostics are deliberately excluded
	 * from {@link Throwable#getMessage()}.
	 * @param message host-controlled exception message
	 * @param responseBody provider-controlled response body that must not become the
	 * exception message
	 * @return the host-controlled message
	 */
	public static String build(String message, @Nullable String responseBody) {
		if (responseBody == null) {
			return message;
		}
		// A non-null provider response is deliberately excluded from the host message.
		return message;
	}

	/**
	 * Recursively sanitize provider metadata while preserving maps, lists, and scalar
	 * value types.
	 * @param metadata provider-controlled metadata
	 * @return a detached, sanitized metadata map
	 */
	public static Map<String, @Nullable Object> sanitizeMetadata(@Nullable Map<String, ?> metadata) {
		if (metadata == null || metadata.isEmpty()) {
			return Map.of();
		}
		Map<String, @Nullable Object> sanitized = new LinkedHashMap<>();
		metadata.forEach((key, value) -> sanitized.put(sanitize(key),
				isCredentialField(key) ? "[REDACTED]" : sanitizeMetadataValue(value)));
		return sanitized;
	}

	/**
	 * Recursively detach and sanitize one provider-controlled diagnostic value.
	 * @param value provider-controlled scalar, JSON tree, map, or iterable
	 * @return a detached, bounded, credential-safe value
	 */
	@Contract("!null -> !null")
	public static @Nullable Object sanitizeDiagnosticValue(@Nullable Object value) {
		if (value instanceof JsonNode node) {
			return sanitizeMetadata(node, null);
		}
		return sanitizeMetadataValue(value);
	}

	@Contract("!null, _ -> !null")
	static @Nullable JsonNode sanitizeMetadata(@Nullable JsonNode metadata, @Nullable String apiKey) {
		if (metadata == null) {
			return null;
		}
		if (metadata.isString()) {
			return JsonNodeFactory.instance.stringNode(sanitize(metadata.stringValue(), apiKey));
		}
		if (metadata.isObject()) {
			ObjectNode sanitized = JsonNodeFactory.instance.objectNode();
			metadata.asObject().properties().forEach(entry -> {
				String key = sanitize(entry.getKey(), apiKey);
				JsonNode value = isCredentialField(entry.getKey()) ? JsonNodeFactory.instance.stringNode("[REDACTED]")
						: sanitizeMetadata(entry.getValue(), apiKey);
				sanitized.set(key, value);
			});
			return sanitized;
		}
		if (metadata.isArray()) {
			ArrayNode sanitized = JsonNodeFactory.instance.arrayNode();
			metadata.asArray().elements().forEach(value -> sanitized.add(sanitizeMetadata(value, apiKey)));
			return sanitized;
		}
		return metadata.deepCopy();
	}

	@Contract("!null -> !null")
	private static @Nullable Object sanitizeMetadataValue(@Nullable Object value) {
		if (value instanceof String text) {
			return sanitize(text);
		}
		if (value instanceof Map<?, ?> map) {
			Map<String, @Nullable Object> sanitized = new LinkedHashMap<>();
			map.forEach((key, nestedValue) -> {
				String name = String.valueOf(key);
				sanitized.put(sanitize(name),
						isCredentialField(name) ? "[REDACTED]" : sanitizeMetadataValue(nestedValue));
			});
			return sanitized;
		}
		if (value instanceof Iterable<?> iterable) {
			List<@Nullable Object> sanitized = new ArrayList<>();
			iterable.forEach(item -> sanitized.add(sanitizeMetadataValue(item)));
			return sanitized;
		}
		return value;
	}

	private static boolean isCredentialField(@Nullable String name) {
		return name != null && CREDENTIAL_FIELD_NAME.matcher(name).matches();
	}

	private static String neutralizeControls(String value) {
		StringBuilder sanitized = new StringBuilder(value.length());
		boolean pendingSpace = false;
		for (int offset = 0; offset < value.length();) {
			int codePoint = value.codePointAt(offset);
			offset += Character.charCount(codePoint);
			if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)) {
				pendingSpace = sanitized.length() > 0;
				continue;
			}
			if (pendingSpace) {
				sanitized.append(' ');
				pendingSpace = false;
			}
			sanitized.appendCodePoint(codePoint);
		}
		return sanitized.toString();
	}

	private static String bound(String value) {
		if (value.length() <= MAX_DIAGNOSTIC_LENGTH) {
			return value;
		}
		int end = MAX_DIAGNOSTIC_LENGTH;
		if (Character.isHighSurrogate(value.charAt(end - 1))) {
			end--;
		}
		return value.substring(0, end) + "...";
	}

}
