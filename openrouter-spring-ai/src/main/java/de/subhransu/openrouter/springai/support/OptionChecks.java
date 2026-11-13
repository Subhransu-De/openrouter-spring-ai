package de.subhransu.openrouter.springai.support;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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

	// Bounds the remembered keys, so values that vary per request cannot grow memory.
	private static final int MAX_WARNINGS = 256;

	private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

	private OptionChecks() {
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
