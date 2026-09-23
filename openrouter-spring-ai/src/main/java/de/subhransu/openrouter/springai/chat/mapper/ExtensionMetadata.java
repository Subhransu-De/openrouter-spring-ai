package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Opaque response extensions are exposed for inspection, never automatically replayed.
 *
 * @author OpenRouter Spring AI contributors
 */
final class ExtensionMetadata {

	static final String MESSAGE = "openrouter.message.extensions";

	static final String CHOICE = "openrouter.choice.extensions";

	static final String TOOLS = "openrouter.tool_call.extensions";

	static final String RESPONSE = "openrouter.response.extensions";

	private ExtensionMetadata() {
	}

	static void put(Map<String, Object> metadata, @Nullable Map<String, @Nullable Object> message,
			@Nullable Map<String, @Nullable Object> choice, @Nullable List<? extends @Nullable ToolCall> calls) {
		if (message != null && !message.isEmpty()) {
			metadata.put(MESSAGE, message);
		}
		if (choice != null && !choice.isEmpty()) {
			metadata.put(CHOICE, choice);
		}
		Map<String, @Nullable Object> tools = new LinkedHashMap<>();
		if (calls != null) {
			for (ToolCall call : calls) {
				if (call != null && !call.extensions().isEmpty()) {
					String key = call.id() != null ? call.id() : String.valueOf(call.index());
					tools.put(key, call.extensions());
				}
			}
		}
		if (!tools.isEmpty()) {
			metadata.put(TOOLS, OptionSnapshots.map(tools));
		}
	}

	static Map<String, @Nullable Object> merge(@Nullable Map<String, @Nullable Object> earlier,
			@Nullable Map<String, @Nullable Object> later) {
		return OptionSnapshots.map(combine(earlier, later));
	}

	private static Map<String, @Nullable Object> combine(@Nullable Map<String, @Nullable Object> earlier,
			@Nullable Map<String, @Nullable Object> later) {
		Map<String, @Nullable Object> result = new LinkedHashMap<>();
		if (earlier != null) {
			result.putAll(earlier);
		}
		if (later != null) {
			result.putAll(later);
		}
		return result;
	}

	static Map<String, @Nullable Object> mergeMessage(@Nullable Map<String, @Nullable Object> earlier,
			@Nullable Map<String, @Nullable Object> later) {
		Map<String, @Nullable Object> result = combine(earlier, later);
		// Only message annotations have incremental semantics. Identically named
		// fields at other wire locations remain opaque, with latest-value precedence.
		if (earlier != null && later != null && earlier.get("annotations") instanceof List<?> first
				&& later.get("annotations") instanceof List<?> second) {
			List<@Nullable Object> combined = new ArrayList<>(first);
			combined.addAll(second);
			result.put("annotations", combined);
		}
		return OptionSnapshots.map(result);
	}

	@SuppressWarnings("unchecked")
	static Map<String, @Nullable Object> mergeChoice(@Nullable Map<String, @Nullable Object> earlier,
			@Nullable Map<String, @Nullable Object> later) {
		Map<String, @Nullable Object> result = combine(earlier, later);
		if (earlier != null && earlier.get("logprobs") instanceof Map<?, ?> first) {
			Object next = later != null ? later.get("logprobs") : null;
			if (next == null) {
				result.put("logprobs", first);
			}
			else if (next instanceof Map<?, ?> second) {
				Map<String, @Nullable Object> logprobs = combine((Map<String, @Nullable Object>) first,
						(Map<String, @Nullable Object>) second);
				for (String key : List.of("content", "refusal")) {
					if (first.get(key) instanceof List<?> previous) {
						List<@Nullable Object> entries = new ArrayList<>(previous);
						if (second.get(key) instanceof List<?> additional) {
							entries.addAll(additional);
						}
						logprobs.put(key, entries);
					}
				}
				result.put("logprobs", logprobs);
			}
		}
		return OptionSnapshots.map(result);
	}

}
