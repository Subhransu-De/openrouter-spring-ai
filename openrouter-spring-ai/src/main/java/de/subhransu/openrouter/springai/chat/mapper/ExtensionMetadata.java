package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	static void put(Map<String, Object> metadata, Map<String, Object> message, Map<String, Object> choice,
			List<ToolCall> calls) {
		if (message != null && !message.isEmpty()) {
			metadata.put(MESSAGE, message);
		}
		if (choice != null && !choice.isEmpty()) {
			metadata.put(CHOICE, choice);
		}
		Map<String, Object> tools = new LinkedHashMap<>();
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

	static Map<String, Object> merge(Map<String, Object> earlier, Map<String, Object> later) {
		Map<String, Object> result = new LinkedHashMap<>();
		if (earlier != null) {
			result.putAll(earlier);
		}
		if (later != null) {
			result.putAll(later);
		}
		return OptionSnapshots.map(result);
	}

	static Map<String, Object> mergeMessage(Map<String, Object> earlier, Map<String, Object> later) {
		Map<String, Object> result = new LinkedHashMap<>(merge(earlier, later));
		// Only message annotations have incremental semantics. Identically named
		// fields at other wire locations remain opaque, with latest-value precedence.
		if (earlier != null && later != null && earlier.get("annotations") instanceof List<?> first
				&& later.get("annotations") instanceof List<?> second) {
			List<Object> combined = new ArrayList<>(first);
			combined.addAll(second);
			result.put("annotations", combined);
		}
		return OptionSnapshots.map(result);
	}

}
