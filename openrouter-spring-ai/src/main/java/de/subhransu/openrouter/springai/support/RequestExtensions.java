package de.subhransu.openrouter.springai.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
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
			"prompt_cache_key");

	private static final Set<String> RESPONSES = Set.of("top_logprobs", "prompt_cache_key");

	private static final Set<String> PROVIDER = Set.of("only", "zdr", "max_price", "sort", MIN_THROUGHPUT, MAX_LATENCY);

	private RequestExtensions() {
	}

	@Contract("!null -> !null")
	public static @Nullable Map<String, @Nullable Object> chatRequest(@Nullable Map<String, ?> fields) {
		Map<String, @Nullable Object> result = chat(fields, false);
		if (result != null && result.get("top_logprobs") != null && !Boolean.TRUE.equals(result.get("logprobs"))) {
			throw new IllegalArgumentException("top_logprobs requires logprobs=true in Chat Completions");
		}
		return result;
	}

	@Contract("!null, _ -> !null")
	public static @Nullable Map<String, @Nullable Object> chat(@Nullable Map<String, ?> fields, boolean responses) {
		Map<String, @Nullable Object> result = validate(fields, responses ? RESPONSES : CHAT);
		if (result == null) {
			return null;
		}
		requireType(result, "logprobs", Boolean.class);
		requireType(result, "prompt_cache_key", String.class);
		requireType(result, "logit_bias", Map.class);
		Object verbosity = result.get("verbosity");
		if (verbosity != null && !Set.of("low", "medium", "high", "xhigh", "max").contains(verbosity)) {
			throw new IllegalArgumentException("Unsupported verbosity value");
		}
		if (result.containsKey("top_logprobs") && result.get("top_logprobs") != null) {
			Object value = result.get("top_logprobs");
			if (!(value instanceof Number number) || number.doubleValue() < 0 || number.doubleValue() > 20
					|| number.doubleValue() != number.intValue()) {
				throw new IllegalArgumentException("top_logprobs must be an integer from 0 to 20");
			}
		}
		return result;
	}

	@Contract("!null, _ -> !null")
	public static @Nullable Map<String, @Nullable Object> provider(@Nullable Map<String, ?> fields,
			@Nullable String sort) {
		Map<String, @Nullable Object> result = validate(fields, PROVIDER);
		if (sort != null && result != null && result.containsKey("sort")) {
			throw new IllegalArgumentException("providerExtraBody sort conflicts with provider.sort");
		}
		if (result != null) {
			requireType(result, "only", List.class);
			if (result.get("only") instanceof List<?> only
					&& only.stream().anyMatch(value -> !(value instanceof String))) {
				throw new IllegalArgumentException("only must contain provider strings");
			}
			requireType(result, "zdr", Boolean.class);
			requireType(result, "max_price", Map.class);
			validateProviderPreferences(result);
			Object extendedSort = result.get("sort");
			if (extendedSort != null && !(extendedSort instanceof String) && !(extendedSort instanceof Map)) {
				throw new IllegalArgumentException("sort must be a string or object");
			}
		}
		return result;
	}

	private static void validateProviderPreferences(Map<String, @Nullable Object> result) {
		for (String key : List.of(MIN_THROUGHPUT, MAX_LATENCY)) {
			Object value = result.get(key);
			if (value != null && !(value instanceof Number) && !(value instanceof Map)) {
				throw new IllegalArgumentException(key + " must be a number or percentile object");
			}
		}
	}

	private static void requireType(Map<String, @Nullable Object> fields, String key, Class<?> type) {
		Object value = fields.get(key);
		if (value != null && !type.isInstance(value)) {
			throw new IllegalArgumentException(key + " must be a " + type.getSimpleName());
		}
	}

	@Contract("!null, _ -> !null")
	private static @Nullable Map<String, @Nullable Object> validate(@Nullable Map<String, ?> fields,
			Set<String> supported) {
		if (fields == null) {
			return null;
		}
		Map<String, @Nullable Object> result = new LinkedHashMap<>();
		fields.forEach((key, value) -> {
			if (key == null || !supported.contains(key)) {
				throw new IllegalArgumentException("Unsupported or reserved extraBody key: " + key);
			}
			Object normalized = normalize(key, value);
			validateJson(normalized);
			result.put(key, normalized);
		});
		return OptionSnapshots.map(result);
	}

	private static @Nullable Object normalize(String key, @Nullable Object value) {
		// Boot's map binding supplies scalar strings. Normalize only documented
		// boolean/numeric fields; opaque strings such as cache keys stay strings.
		Object normalized = value;
		if ("only".equals(key) && value instanceof Map<?, ?> indexed) {
			normalized = indexedProviders(indexed);
		}
		if (("logprobs".equals(key) || "zdr".equals(key)) && value instanceof String text
				&& ("true".equals(text) || "false".equals(text))) {
			normalized = Boolean.valueOf(text);
		}
		if (("top_logprobs".equals(key) || MIN_THROUGHPUT.equals(key) || MAX_LATENCY.equals(key))
				&& value instanceof String text) {
			normalized = new BigDecimal(text);
		}
		if (Set.of("logit_bias", "max_price", MIN_THROUGHPUT, MAX_LATENCY).contains(key)
				&& value instanceof Map<?, ?> map) {
			normalized = numbers(key, map);
		}
		return normalized;
	}

	private static List<@Nullable Object> indexedProviders(Map<?, ?> indexed) {
		List<@Nullable Object> values = new ArrayList<>();
		for (int index = 0; index < indexed.size(); index++) {
			String position = String.valueOf(index);
			if (!indexed.containsKey(position)) {
				throw new IllegalArgumentException("only requires contiguous indexes starting at zero");
			}
			values.add(indexed.get(position));
		}
		return values;
	}

	private static Map<String, @Nullable Object> numbers(String key, Map<?, ?> map) {
		Map<String, @Nullable Object> numbers = new LinkedHashMap<>();
		map.forEach((name, number) -> {
			if (!(name instanceof String)) {
				throw new IllegalArgumentException("Extension object keys must be strings");
			}
			Object normalizedNumber = number instanceof String text ? new BigDecimal(text) : number;
			boolean nullablePercentile = normalizedNumber == null
					&& (MIN_THROUGHPUT.equals(key) || MAX_LATENCY.equals(key));
			if (!(normalizedNumber instanceof Number) && !nullablePercentile) {
				throw new IllegalArgumentException(key + " entries must be numbers");
			}
			numbers.put((String) name, normalizedNumber);
		});
		return numbers;
	}

	private static void validateJson(@Nullable Object value) {
		if (value instanceof Number number && !Double.isFinite(number.doubleValue())) {
			throw new IllegalArgumentException("Extension numbers must be finite");
		}
		else if (value instanceof Map<?, ?> map) {
			map.forEach((key, nested) -> {
				if (!(key instanceof String)) {
					throw new IllegalArgumentException("Extension object keys must be strings");
				}
				validateJson(nested);
			});
		}
		else if (value instanceof List<?> list) {
			list.forEach(RequestExtensions::validateJson);
		}
		else if (value != null && !(value instanceof String) && !(value instanceof Boolean)
				&& !(value instanceof Number)) {
			throw new IllegalArgumentException("Extensions accept JSON scalars, maps, and lists only");
		}
	}

}
