package de.subhransu.openrouter.springai.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Container ownership for options. JSON maps and lists are recursively snapshotted;
 * opaque values (including callbacks and application objects) retain their identity.
 *
 * @author OpenRouter Spring AI contributors
 */
public final class OptionSnapshots {

	private OptionSnapshots() {
	}

	public static <T> List<T> list(List<T> values) {
		return values == null ? null : values.stream().toList();
	}

	public static <K> Map<K, Object> map(Map<K, ?> values) {
		if (values == null) {
			return null;
		}
		Map<K, Object> copy = new LinkedHashMap<>();
		values.forEach((key, value) -> copy.put(key, value(value)));
		return Collections.unmodifiableMap(copy);
	}

	public static Object value(Object value) {
		if (value instanceof Map<?, ?> map) {
			return map(map);
		}
		if (value instanceof List<?> list) {
			return list.stream().map(OptionSnapshots::value).toList();
		}
		return value;
	}

}
