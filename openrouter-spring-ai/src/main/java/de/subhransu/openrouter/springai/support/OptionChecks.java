package de.subhransu.openrouter.springai.support;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.apache.commons.logging.Log;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

/**
 * Option checks shared by builders and mappers. Error messages name the option, echo the
 * value and say what is allowed; warnings are logged once per distinct key.
 *
 * @author Subhransu De
 */
public final class OptionChecks {

	private static final int MAX_ECHO_LENGTH = 120;

	private static final int MAX_SESSION_ID_LENGTH = 256;

	// OpenRouter's documented input_audio.format pattern.
	private static final Pattern AUDIO_FORMAT = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9+._-]{0,15}");

	// Bounds the remembered keys, so values that vary per request cannot grow memory.
	private static final int MAX_WARNINGS = 256;

	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private static final List<String> VERBOSITY = List.of("low", "medium", "high", "xhigh", "max");

	private static final int MAX_TOP_LOGPROBS = 20;

	// Headers that OpenRouterApi sets itself, lower-cased. A request value would replace
	// the configured API key, which the diagnostic sanitizer does not know, or the
	// attribution and media types that the client owns.
	private static final Set<String> CLIENT_HEADERS = Set.of("authorization", "content-type", "accept", "http-referer",
			"x-openrouter-title", "x-openrouter-categories");

	private OptionChecks() {
	}

	/**
	 * Rejects an {@code extraBody} key that a typed field of the same options or request
	 * also sets. As with provider sort, an extension never overrides a typed option
	 * silently.
	 * @param extraBody the extension fields
	 * @param typed the non-null typed fields, keyed by wire name
	 */
	public static void rejectTypedConflicts(@Nullable Map<String, ?> extraBody, Map<String, Object> typed) {
		if (extraBody != null) {
			typed.forEach((key, value) -> {
				if (extraBody.containsKey(key)) {
					throw new IllegalArgumentException("extraBody " + key + " " + describe(extraBody.get(key))
							+ " conflicts with the typed option " + describe(value) + "; set only one of them");
				}
			});
		}
	}

	/**
	 * Checks a verbosity against the values that OpenRouter documents.
	 * @param option the option name used in the message
	 * @param verbosity the verbosity to check
	 * @return the same verbosity
	 */
	@Contract("_, !null -> !null")
	public static @Nullable Object verbosity(String option, @Nullable Object verbosity) {
		if (verbosity != null && !VERBOSITY.contains(verbosity)) {
			throw new IllegalArgumentException(
					option + " " + describe(verbosity) + " is not supported; use " + String.join(", ", VERBOSITY));
		}
		return verbosity;
	}

	/**
	 * Checks that a {@code top_logprobs} value is an integer from 0 to 20.
	 * @param option the option name used in the message
	 * @param value the value to check
	 * @return the same value
	 */
	@Contract("_, !null -> !null")
	public static @Nullable Object topLogprobs(String option, @Nullable Object value) {
		if (value != null && (!(value instanceof Number number) || number.doubleValue() < 0
				|| number.doubleValue() > MAX_TOP_LOGPROBS || number.doubleValue() != number.intValue())) {
			throw new IllegalArgumentException(option + " must be an integer from 0 to 20, but was " + describe(value));
		}
		return value;
	}

	/**
	 * Renders a value for an error message: strings are quoted, and long values are
	 * shortened.
	 * @param value the value to render
	 * @return the rendered value
	 */
	public static String describe(@Nullable Object value) {
		String text = value instanceof CharSequence chars ? "\"" + chars + "\"" : String.valueOf(value);
		return text.length() <= MAX_ECHO_LENGTH ? text : text.substring(0, MAX_ECHO_LENGTH) + "…";
	}

	/**
	 * Rejects a list that holds a null element.
	 * @param option the option name used in the message
	 * @param values the list to check
	 * @return the same list
	 */
	@Contract("_, !null -> !null")
	public static <T extends @Nullable Object> @Nullable List<T> noNullElements(String option,
			@Nullable List<T> values) {
		if (values == null) {
			return null;
		}
		// List.of(...).indexOf(null) throws, so look for the null element by hand.
		int index = 0;
		for (T value : values) {
			if (value == null) {
				throw new IllegalArgumentException(option + " must not contain null elements, but element " + index
						+ " of " + describe(values) + " is null");
			}
			index++;
		}
		return values;
	}

	/**
	 * Checks a {@code session_id}, which OpenRouter limits to 256 characters.
	 * @param sessionId the session identifier to check
	 * @return the same session identifier
	 */
	@Contract("!null -> !null")
	public static @Nullable String sessionId(@Nullable String sessionId) {
		return maxLength("sessionId", sessionId, MAX_SESSION_ID_LENGTH);
	}

	/**
	 * Checks an audio format, such as {@code wav}, against the pattern OpenRouter
	 * documents for {@code input_audio.format}.
	 * @param source where the format came from, used in the message
	 * @param format the format to check
	 * @return the same format
	 */
	@Contract("_, !null -> !null")
	public static @Nullable String audioFormat(String source, @Nullable String format) {
		if (format != null && !AUDIO_FORMAT.matcher(format).matches()) {
			throw new IllegalArgumentException(source + " " + describe(format)
					+ " is not a valid audio format: use 1 to 16 letters, digits or +._-, starting with a letter or digit");
		}
		return format;
	}

	/**
	 * Checks per-request HTTP headers: names must not be blank, values must not be null,
	 * and no name may be one that the OpenRouter client sets itself. The message names
	 * the header but never echoes a value, which can be a credential.
	 * @param headers the headers to check
	 * @return the same headers
	 */
	@Contract("!null -> !null")
	public static @Nullable Map<String, String> customHeaders(@Nullable Map<String, String> headers) {
		if (headers == null) {
			return null;
		}
		headers.forEach((name, value) -> {
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("customHeaders must not contain a blank header name");
			}
			if (value == null) {
				throw new IllegalArgumentException("customHeaders " + name + " must not have a null value");
			}
			if (CLIENT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException("customHeaders must not set " + name
						+ ", which the OpenRouter client sets itself; configure it on OpenRouterApi instead");
			}
		});
		return headers;
	}

	/**
	 * Rejects a string longer than a documented limit. Characters are counted as code
	 * points, as JSON Schema {@code maxLength} counts them.
	 * @param option the option name used in the message
	 * @param value the string to check
	 * @param maxLength the documented maximum length
	 * @return the same string
	 */
	@Contract("_, !null, _ -> !null")
	private static @Nullable String maxLength(String option, @Nullable String value, int maxLength) {
		if (value != null && value.codePointCount(0, value.length()) > maxLength) {
			throw new IllegalArgumentException(option + " must be at most " + maxLength + " characters, but was "
					+ value.codePointCount(0, value.length()) + " characters: " + describe(value));
		}
		return value;
	}

	/**
	 * Logs a warning once when a value is not one that OpenRouter documents. The value is
	 * still sent, so values that OpenRouter adds later keep working.
	 * @param log the log to write to
	 * @param option the option name used in the message
	 * @param value the configured value
	 * @param known the documented values
	 */
	public static void warnIfUnknown(Log log, String option, @Nullable String value, Collection<String> known) {
		if (value != null && !known.contains(value)) {
			warnOnce(log, option + "=" + value, () -> option + " " + describe(value)
					+ " is not a value OpenRouter documents (" + String.join(", ", known) + "); it is sent unchanged");
		}
	}

	/**
	 * Logs a warning the first time a key is seen. CR and LF in the message are replaced,
	 * because it can echo a configured value.
	 * @param log the log to write to
	 * @param key the identity of the warning
	 * @param message the warning text
	 */
	public static void warnOnce(Log log, String key, Supplier<String> message) {
		if (log.isWarnEnabled() && WARNED.size() < MAX_WARNINGS && WARNED.add(key)) {
			log.warn(message.get().replace('\r', '_').replace('\n', '_'));
		}
	}

}
