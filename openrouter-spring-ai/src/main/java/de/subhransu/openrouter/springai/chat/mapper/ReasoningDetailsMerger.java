package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// OpenRouter streams consecutive text/summary fragments; encrypted blobs are discrete.
// Index alone is not an identity: different reasoning types may reuse the same index.
final class ReasoningDetailsMerger {

	private ReasoningDetailsMerger() {
	}

	static List<JsonNode> merge(@Nullable List<? extends @Nullable JsonNode> earlier,
			@Nullable List<? extends @Nullable JsonNode> fragments) {
		List<JsonNode> result = new ArrayList<>(ResponseValues.items(earlier, "reasoning detail"));
		for (JsonNode fragment : ResponseValues.items(fragments, "reasoning detail")) {
			JsonNode previous = result.isEmpty() ? null : result.get(result.size() - 1);
			String field = payloadField(fragment);
			if (previous != null && field != null && compatible(previous, fragment, field)) {
				ObjectNode merged = (ObjectNode) previous.deepCopy();
				for (Map.Entry<String, JsonNode> entry : fragment.properties()) {
					JsonNode original = merged.get(entry.getKey());
					if (field.equals(entry.getKey()) && original != null && original.isString()
							&& entry.getValue().isString()) {
						merged.put(field, original.asString() + entry.getValue().asString());
					}
					else if (original == null || original.isNull()) {
						merged.set(entry.getKey(), entry.getValue().deepCopy());
					}
				}
				result.set(result.size() - 1, merged);
			}
			else {
				result.add(fragment.deepCopy());
			}
		}
		return List.copyOf(result);
	}

	private static @Nullable String payloadField(JsonNode item) {
		return switch (item.path("type").asString("")) {
			case "reasoning.text" -> "text";
			case "reasoning.summary" -> "summary";
			default -> null;
		};
	}

	private static boolean compatible(JsonNode previous, JsonNode fragment, String field) {
		if (!previous.isObject() || !Objects.equals(previous.get("type"), fragment.get("type"))) {
			return false;
		}
		// Distinct identities or conflicting unknown fields denote separate blocks;
		// never discard opaque provider state to force a merge.
		for (Map.Entry<String, JsonNode> entry : fragment.properties()) {
			JsonNode original = previous.get(entry.getKey());
			JsonNode incoming = entry.getValue();
			if (field.equals(entry.getKey())) {
				if ((original != null && !original.isNull() && !original.isString())
						|| (!incoming.isNull() && !incoming.isString())) {
					return false;
				}
			}
			else if (original != null && !original.isNull() && !incoming.isNull() && !original.equals(incoming)) {
				return false;
			}
		}
		return true;
	}

}
