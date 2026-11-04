package de.subhransu.openrouter.springai.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

/**
 * Container ownership for options. JSON maps and lists are recursively snapshotted;
 * opaque values (including callbacks and application objects) retain their identity.
 *
 * @author OpenRouter Spring AI contributors
 */
public final class OptionSnapshots {

	private static final Map<@Nullable Object, @Nullable Object> EMPTY_MAP = Collections
		.unmodifiableMap(new LinkedHashMap<>());

	private static final List<?> EMPTY_LIST = Stream.empty().toList();

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
		if (values.isEmpty()) {
			@SuppressWarnings("unchecked")
			Map<K, @Nullable Object> empty = (Map<K, @Nullable Object>) EMPTY_MAP;
			return empty;
		}
		Map<K, @Nullable Object> copy = new LinkedHashMap<>(Math.min(values.size(), 8) * 2);
		values.forEach((key, value) -> copy.put(key, value(value)));
		return Collections.unmodifiableMap(copy);
	}

	@Contract("!null -> !null")
	public static @Nullable Object value(@Nullable Object value) {
		if (value instanceof Map<?, ?> map) {
			return map(map);
		}
		if (value instanceof List<?> list) {
			return list.isEmpty() ? EMPTY_LIST : list.stream().map(OptionSnapshots::value).toList();
		}
		return value;
	}

}
