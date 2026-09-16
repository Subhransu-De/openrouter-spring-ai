package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class RefusalMetadata {

	static final String REFUSAL = "openrouter.refusal";

	private RefusalMetadata() {
	}

	static void put(Map<String, Object> metadata, String refusal) {
		if (refusal != null) {
			metadata.put(REFUSAL, refusal);
		}
	}

	static String responses(List<ResponsesOutputItem> output) {
		String refusal = null;
		if (output != null) {
			for (ResponsesOutputItem item : output) {
				if ("message".equals(item.type()) && item.content() != null) {
					for (ResponsesContent part : item.content()) {
						if ("refusal".equals(part.type())) {
							refusal = (refusal != null ? refusal : "") + (part.refusal() != null ? part.refusal() : "");
						}
					}
				}
			}
		}
		return refusal;
	}

	static final class Accumulator {

		private final Map<Integer, Map<Integer, String>> parts = new TreeMap<>();

		String update(ResponsesStreamEvent event) {
			int outputIndex = event.outputIndex() != null ? event.outputIndex() : 0;
			int contentIndex = event.contentIndex() != null ? event.contentIndex() : 0;
			if ("response.refusal.delta".equals(event.type())) {
				this.parts.computeIfAbsent(outputIndex, key -> new TreeMap<>())
					.merge(contentIndex, event.delta() != null ? event.delta() : "", String::concat);
			}
			else if ("response.refusal.done".equals(event.type()) && event.refusal() != null) {
				this.parts.computeIfAbsent(outputIndex, key -> new TreeMap<>()).put(contentIndex, event.refusal());
			}
			else if ("response.output_item.done".equals(event.type()) && event.item() != null) {
				snapshot(outputIndex, event.item());
			}
			if (("response.completed".equals(event.type()) || "response.incomplete".equals(event.type()))
					&& event.response() != null && event.response().output() != null) {
				List<ResponsesOutputItem> output = event.response().output();
				for (int index = 0; index < output.size(); index++) {
					snapshot(index, output.get(index));
				}
			}
			return this.parts.isEmpty() ? null
					: this.parts.values().stream().flatMap(part -> part.values().stream()).reduce("", String::concat);
		}

		private void snapshot(int outputIndex, ResponsesOutputItem item) {
			if ("message".equals(item.type()) && item.content() != null) {
				for (int index = 0; index < item.content().size(); index++) {
					ResponsesContent part = item.content().get(index);
					if ("refusal".equals(part.type())) {
						Map<Integer, String> content = this.parts.computeIfAbsent(outputIndex, key -> new TreeMap<>());
						// Sparse snapshots must not erase an explanation already
						// received.
						if (part.refusal() != null) {
							content.put(index, part.refusal());
						}
						else {
							content.putIfAbsent(index, "");
						}
					}
				}
			}
		}

	}

}
