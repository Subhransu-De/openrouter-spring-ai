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
			later.forEach((key, value) -> {
				// Annotation arrays are incremental. Other opaque values are replaced
				// whole by the latest value, without guessing provider semantics.
				if ("annotations".equals(key) && result.get(key) instanceof List<?> first
						&& value instanceof List<?> second) {
					List<Object> combined = new ArrayList<>(first);
					combined.addAll(second);
					result.put(key, combined);
				}
				else {
					result.put(key, value);
				}
			});
		}
		return OptionSnapshots.map(result);
	}

}
