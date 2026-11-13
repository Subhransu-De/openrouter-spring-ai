package de.subhransu.openrouter.springai.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.lang.Contract;

/**
 * Validated, bounded extension fields; standard request fields cannot be overridden.
 *
 * @author OpenRouter Spring AI contributors
 */
public final class RequestExtensions {

	private static final String MIN_THROUGHPUT = "preferred_min_throughput";

	private static final String MAX_LATENCY = "preferred_max_latency";

	private static final Set<String> CHAT = Set.of("logit_bias", "logprobs", "top_logprobs", "verbosity",
			"prompt_cache_key", "transforms");

	private static final Set<String> RESPONSES = Set.of("top_logprobs", "prompt_cache_key");

	private static final Set<String> PROVIDER = Set.of("only", "zdr", "max_price", "sort", MIN_THROUGHPUT, MAX_LATENCY);

	private static final Set<String> INDEXED_LISTS = Set.of("only", "transforms");

	private static final List<String> VERBOSITY = List.of("low", "medium", "high", "xhigh", "max");

	private static final String EXTRA_BODY = "extraBody ";

	private static final String PROVIDER_EXTRA_BODY = "providerExtraBody ";

	private RequestExtensions() {
	}

	@Contract("!null -> !null")
	public static @Nullable Map<String, @Nullable Object> chatRequest(@Nullable Map<String, ?> fields) {
		Map<String, @Nullable Object> result = chat(fields, false);
		if (result != null && result.get("top_logprobs") != null && !Boolean.TRUE.equals(result.get("logprobs"))) {
			throw new IllegalArgumentException(
					"extraBody top_logprobs requires logprobs=true in " + ExtensionChecks.CHAT_MODE
							+ ", but logprobs was " + OptionChecks.describe(result.get("logprobs")));
		}
		return result;
	}

	@Contract("!null, _ -> !null")
	public static @Nullable Map<String, @Nullable Object> chat(@Nullable Map<String, ?> fields, boolean responses) {
		Set<String> supported = responses ? RESPONSES : CHAT;
		Map<String, @Nullable Object> result = validate(fields, EXTRA_BODY, supported,
				key -> ExtensionChecks.unsupportedRootKey(key, supported, responses, CHAT));
		if (result == null) {
			return null;
		}
		requireType(result, EXTRA_BODY, "logprobs", Boolean.class);
		requireType(result, EXTRA_BODY, "prompt_cache_key", String.class);
		requireType(result, EXTRA_BODY, "logit_bias", Map.class);
		requireStringList(result, EXTRA_BODY, "transforms", "transform name strings");
		Object verbosity = result.get("verbosity");
		if (verbosity != null && !VERBOSITY.contains(verbosity)) {
			throw new IllegalArgumentException("extraBody verbosity " + OptionChecks.describe(verbosity)
					+ " is not supported; use " + String.join(", ", VERBOSITY));
		}
		if (result.containsKey("top_logprobs") && result.get("top_logprobs") != null) {
			Object value = result.get("top_logprobs");
			if (!(value instanceof Number number) || number.doubleValue() < 0 || number.doubleValue() > 20
					|| number.doubleValue() != number.intValue()) {
				throw new IllegalArgumentException("extraBody top_logprobs must be an integer from 0 to 20, but was "
						+ OptionChecks.describe(value));
			}
		}
		return result;
	}

	@Contract("!null, _ -> !null")
	public static @Nullable Map<String, @Nullable Object> provider(@Nullable Map<String, ?> fields,
			@Nullable String sort) {
		Map<String, @Nullable Object> result = validate(fields, PROVIDER_EXTRA_BODY, PROVIDER,
				key -> ExtensionChecks.unsupportedProviderKey(key, PROVIDER));
		if (sort != null && result != null && result.containsKey("sort")) {
			throw new IllegalArgumentException("providerExtraBody sort " + OptionChecks.describe(result.get("sort"))
					+ " conflicts with provider.sort " + OptionChecks.describe(sort) + "; set only one of them");
		}
		if (result != null) {
			requireStringList(result, PROVIDER_EXTRA_BODY, "only", "provider name strings");
			requireType(result, PROVIDER_EXTRA_BODY, "zdr", Boolean.class);
			requireType(result, PROVIDER_EXTRA_BODY, "max_price", Map.class);
			validateProviderPreferences(result);
			Object extendedSort = result.get("sort");
			if (extendedSort != null && !(extendedSort instanceof String) && !(extendedSort instanceof Map)) {
				throw new IllegalArgumentException("providerExtraBody sort must be a string or object, but was "
						+ OptionChecks.describe(extendedSort));
			}
		}
		return result;
	}

	private static void validateProviderPreferences(Map<String, @Nullable Object> result) {
		for (String key : List.of(MIN_THROUGHPUT, MAX_LATENCY)) {
			Object value = result.get(key);
			if (value != null && !(value instanceof Number) && !(value instanceof Map)) {
				throw new IllegalArgumentException(PROVIDER_EXTRA_BODY + key
						+ " must be a number or percentile object, but was " + OptionChecks.describe(value));
			}
		}
	}

	private static void requireStringList(Map<String, @Nullable Object> fields, String option, String key,
			String elements) {
		requireType(fields, option, key, List.class);
		if (fields.get(key) instanceof List<?> values
				&& values.stream().anyMatch(value -> !(value instanceof String))) {
			throw new IllegalArgumentException(
					option + key + " must contain " + elements + ", but was " + OptionChecks.describe(values));
		}
	}

	private static void requireType(Map<String, @Nullable Object> fields, String option, String key, Class<?> type) {
		Object value = fields.get(key);
		if (value != null && !type.isInstance(value)) {
			throw new IllegalArgumentException(
					option + key + " must be a " + type.getSimpleName() + ", but was " + OptionChecks.describe(value));
		}
	}

	@Contract("!null, _, _, _ -> !null")
	private static @Nullable Map<String, @Nullable Object> validate(@Nullable Map<String, ?> fields, String option,
			Set<String> supported, Function<@Nullable String, String> unsupported) {
		if (fields == null) {
			return null;
		}
		Map<String, @Nullable Object> result = new LinkedHashMap<>();
		fields.forEach((key, value) -> {
			if (key == null || !supported.contains(key)) {
				throw new IllegalArgumentException(unsupported.apply(key));
			}
			Object normalized = normalize(option + key, key, value);
			validateJson(option + key, normalized);
			result.put(key, normalized);
		});
		return OptionSnapshots.map(result);
	}

	private static @Nullable Object normalize(String name, String key, @Nullable Object value) {
		// Boot's map binding supplies scalar strings. Normalize only documented
		// boolean/numeric fields; opaque strings such as cache keys stay strings.
		Object normalized = list(name, key, value);
		if (("logprobs".equals(key) || "zdr".equals(key)) && value instanceof String text
				&& ("true".equals(text) || "false".equals(text))) {
			normalized = Boolean.valueOf(text);
		}
		if (("top_logprobs".equals(key) || MIN_THROUGHPUT.equals(key) || MAX_LATENCY.equals(key))
				&& value instanceof String text) {
			normalized = ExtensionChecks.number(name, text);
		}
		if (Set.of("logit_bias", "max_price", MIN_THROUGHPUT, MAX_LATENCY).contains(key)
				&& value instanceof Map<?, ?> map) {
			normalized = numbers(name, key, map);
		}
		return normalized;
	}

	private static @Nullable Object list(String name, String key, @Nullable Object value) {
		if (INDEXED_LISTS.contains(key) && value instanceof Map<?, ?> indexed) {
			return indexedList(name, indexed);
		}
		// A Boot property with no value is the only way to write an empty list there.
		if ("transforms".equals(key) && value instanceof String text && text.isEmpty()) {
			return List.of();
		}
		return value;
	}

	// Boot binds indexed list properties, such as [only][0], to a map keyed by position.
	private static List<@Nullable Object> indexedList(String name, Map<?, ?> indexed) {
		List<@Nullable Object> values = new ArrayList<>();
		for (int index = 0; index < indexed.size(); index++) {
			String position = String.valueOf(index);
			if (!indexed.containsKey(position)) {
				throw new IllegalArgumentException(name + " requires contiguous indexes starting at zero, but the "
						+ "indexes were " + OptionChecks.describe(indexed.keySet()));
			}
			values.add(indexed.get(position));
		}
		return values;
	}

	private static Map<String, @Nullable Object> numbers(String name, String key, Map<?, ?> map) {
		Map<String, @Nullable Object> numbers = new LinkedHashMap<>();
		map.forEach((entry, number) -> {
			if (!(entry instanceof String entryName)) {
				throw new IllegalArgumentException(
						name + " object keys must be strings, but found " + OptionChecks.describe(entry));
			}
			Object normalizedNumber = number instanceof String text
					? ExtensionChecks.number(name + "." + entryName, text) : number;
			boolean nullablePercentile = normalizedNumber == null
					&& (MIN_THROUGHPUT.equals(key) || MAX_LATENCY.equals(key));
			if (!(normalizedNumber instanceof Number) && !nullablePercentile) {
				throw new IllegalArgumentException(
						name + " entries must be numbers, but " + entryName + " was " + OptionChecks.describe(number));
			}
			numbers.put(entryName, normalizedNumber);
		});
		return numbers;
	}

	private static void validateJson(String name, @Nullable Object value) {
		if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
			throw new IllegalArgumentException(name + " numbers must be finite, but found " + number);
		}
		else if (value instanceof Map<?, ?> map) {
			map.forEach((key, nested) -> {
				if (!(key instanceof String)) {
					throw new IllegalArgumentException(
							name + " object keys must be strings, but found " + OptionChecks.describe(key));
				}
				validateJson(name, nested);
			});
		}
		else if (value instanceof List<?> list) {
			list.forEach(element -> validateJson(name, element));
		}
		else if (value != null && !(value instanceof String) && !(value instanceof Boolean)
				&& !(value instanceof Number)) {
			throw new IllegalArgumentException(name + " accepts JSON strings, numbers, booleans, maps and lists, "
					+ "but found a " + value.getClass().getName());
		}
	}

}
