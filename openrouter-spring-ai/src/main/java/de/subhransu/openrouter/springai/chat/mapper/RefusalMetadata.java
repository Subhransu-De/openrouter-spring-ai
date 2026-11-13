package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

final class RefusalMetadata {

	static final String REFUSAL = OpenRouterMetadata.REFUSAL;

	private RefusalMetadata() {
	}

	static void put(Map<String, Object> metadata, @Nullable String refusal) {
		if (refusal != null) {
			metadata.put(REFUSAL, refusal);
		}
	}

	static @Nullable String responses(@Nullable List<? extends @Nullable ResponsesOutputItem> output) {
		if (output == null) {
			return null;
		}
		String refusal = null;
		for (ResponsesOutputItem item : ResponseValues.RESPONSES.items(output, "output item")) {
			if (!"message".equals(item.type()) || item.content() == null) {
				continue;
			}
			for (ResponsesContent part : ResponseValues.RESPONSES.items(item.content(), "message content")) {
				if ("refusal".equals(part.type())) {
					refusal = (refusal != null ? refusal : "") + (part.refusal() != null ? part.refusal() : "");
				}
			}
		}
		return refusal;
	}

	static final class Accumulator {

		private final Map<Integer, Map<Integer, String>> parts = new TreeMap<>();

		void clear() {
			this.parts.clear();
		}

		@Nullable String update(ResponsesStreamEvent event) {
			var item = event.item();
			int outputIndex = Objects.requireNonNullElse(event.outputIndex(), 0);
			int contentIndex = Objects.requireNonNullElse(event.contentIndex(), 0);
			if ("response.refusal.delta".equals(event.type())) {
				this.parts.computeIfAbsent(outputIndex, key -> new TreeMap<>())
					.merge(contentIndex, event.delta() != null ? event.delta() : "", String::concat);
			}
			else if ("response.refusal.done".equals(event.type()) && event.refusal() != null) {
				this.parts.computeIfAbsent(outputIndex, key -> new TreeMap<>()).put(contentIndex, event.refusal());
			}
			else if ("response.output_item.done".equals(event.type()) && item != null) {
				snapshot(outputIndex, item);
			}
			terminal(event);
			return this.parts.isEmpty() ? null
					: this.parts.values().stream().flatMap(part -> part.values().stream()).reduce("", String::concat);
		}

		private void terminal(ResponsesStreamEvent event) {
			var response = event.response();
			if (("response.completed".equals(event.type()) || "response.incomplete".equals(event.type()))
					&& response != null && response.output() != null) {
				List<ResponsesOutputItem> output = ResponseValues.RESPONSES.items(response.output(), "output item");
				for (int index = 0; index < output.size(); index++) {
					snapshot(index, output.get(index));
				}
			}
		}

		private void snapshot(int outputIndex, ResponsesOutputItem item) {
			var messageContent = item.content();
			if ("message".equals(item.type()) && messageContent != null) {
				for (int index = 0; index < messageContent.size(); index++) {
					ResponsesContent part = ResponseValues.RESPONSES.required(messageContent.get(index),
							"message content");
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
