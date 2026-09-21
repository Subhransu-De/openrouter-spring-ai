package de.subhransu.openrouter.springai.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

/**
 * Container ownership for options. JSON maps and lists are recursively snapshotted;
 * opaque values (including callbacks and application objects) retain their identity.
 *
 * @author OpenRouter Spring AI contributors
 */
public final class OptionSnapshots {

	private OptionSnapshots() {
	}

	@Contract("!null -> !null")
	public static <T extends @Nullable Object> @Nullable List<T> list(@Nullable List<T> values) {
		return values == null ? null : values.stream().toList();
	}

	@Contract("!null -> !null")
	public static <K extends @Nullable Object> @Nullable Map<K, @Nullable Object> map(@Nullable Map<K, ?> values) {
		if (values == null) {
			return null;
		}
		Map<K, @Nullable Object> copy = new LinkedHashMap<>();
		values.forEach((key, value) -> copy.put(key, value(value)));
		return Collections.unmodifiableMap(copy);
	}

	@Contract("!null -> !null")
	public static @Nullable Object value(@Nullable Object value) {
		if (value instanceof Map<?, ?> map) {
			return map(map);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(OptionSnapshots::value).toList();
		}
		return value;
	}

}
