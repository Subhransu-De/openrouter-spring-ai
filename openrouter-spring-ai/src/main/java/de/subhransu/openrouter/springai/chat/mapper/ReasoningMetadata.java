package de.subhransu.openrouter.springai.chat.mapper;

import tools.jackson.databind.JsonNode;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Provider reasoning is opaque conversation state; preserve its wire order.
final class ReasoningMetadata {

	static final String REASONING = "openrouter.reasoning";
	static final String DETAILS = "openrouter.reasoning_details";
	static final String RESPONSES_OUTPUT_ITEMS = "openrouter.responses.output_items";

	static final String RESPONSES_ITEMS = "openrouter.responses.reasoning_items";

	private ReasoningMetadata() {
	}

	static Map<String, Object> chat(String reasoning, List<JsonNode> details) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (reasoning != null) {
			metadata.put(REASONING, reasoning);
		}
		if (details != null) {
			metadata.put(DETAILS, List.copyOf(details));
		}
		return metadata;
	}

	@SuppressWarnings("unchecked")
	static List<JsonNode> details(Map<String, Object> metadata) {
		return (List<JsonNode>) metadata.get(DETAILS);
	}

	static Map<String, Object> responses(List<ResponsesOutputItem> output) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (output != null) {
			metadata.put(RESPONSES_OUTPUT_ITEMS, List.copyOf(output));
			List<ResponsesOutputItem> items = output.stream().filter(item -> "reasoning".equals(item.type())).toList();
			if (!items.isEmpty()) {
				metadata.put(RESPONSES_ITEMS, items);
			}
			StringBuilder text = new StringBuilder();
			for (ResponsesOutputItem item : items) {
				if (item.content() != null) {
					item.content()
						.stream()
						.filter(content -> "reasoning_text".equals(content.type()) && content.text() != null)
						.forEach(content -> text.append(content.text()));
				}
				else if (item.rawItem() != null) {
					JsonNode raw = item.rawItem();
					JsonNode parts = raw.hasNonNull("content") ? raw.get("content") : raw.get("summary");
					if (parts != null && parts.isArray()) {
						for (JsonNode part : parts) {
							if (part.hasNonNull("text")) {
								text.append(part.get("text").asString());
							}
						}
					}
				}
			}
			if (!text.isEmpty()) {
				metadata.put(REASONING, text.toString());
			}
		}
		return metadata;
	}

	static <T> List<T> concat(List<T> earlier, List<T> later) {
		if (earlier == null) {
			return later;
		}
		if (later == null) {
			return earlier;
		}
		List<T> result = new ArrayList<>(earlier);
		result.addAll(later);
		return result;
	}

	static final class Accumulator {

		private final Map<String, Object> metadata = new LinkedHashMap<>();

		void clear() {
			this.metadata.clear();
		}

		Map<String, Object> replace(Map<String, Object> complete) {
			this.metadata.putAll(complete);
			return new LinkedHashMap<>(this.metadata);
		}

		@SuppressWarnings("unchecked")
		Map<String, Object> append(Map<String, Object> delta) {
			if (delta.containsKey(DETAILS)) {
				this.metadata.put(DETAILS, ReasoningDetailsMerger.merge(details(this.metadata), details(delta)));
			}
			delta.forEach((key, value) -> {
				if (DETAILS.equals(key)) {
					return;
				}
				this.metadata.merge(key, value, (earlier, later) -> {
					if ((ExtensionMetadata.MESSAGE.equals(key) || ExtensionMetadata.CHOICE.equals(key)
							|| ExtensionMetadata.TOOLS.equals(key)) && earlier instanceof Map<?, ?> first
							&& later instanceof Map<?, ?> second) {
						return switch (key) {
							case ExtensionMetadata.MESSAGE -> ExtensionMetadata
								.mergeMessage((Map<String, Object>) first, (Map<String, Object>) second);
							case ExtensionMetadata.CHOICE -> ExtensionMetadata.mergeChoice((Map<String, Object>) first,
									(Map<String, Object>) second);
							default ->
								ExtensionMetadata.merge((Map<String, Object>) first, (Map<String, Object>) second);
						};
					}
					if (earlier instanceof String first && later instanceof String second) {
						return first + second;
					}
					if (earlier instanceof List<?> first && later instanceof List<?> second) {
						List<Object> items = new ArrayList<>(first);
						items.addAll(second);
						return List.copyOf(items);
					}
					return later;
				});
			});
			return new LinkedHashMap<>(this.metadata);
		}

	}

}
