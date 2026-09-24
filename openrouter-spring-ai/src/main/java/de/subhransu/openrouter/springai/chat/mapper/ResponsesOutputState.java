package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;

// Subscription-local prefixes. Later parts wait until earlier parts have a final snapshot.
final class ResponsesOutputState {

	private final Map<Integer, TextItem> text = new HashMap<>();

	private final Map<String, Image> imageIds = new HashMap<>();

	private final Map<Integer, Image> imageIndexes = new HashMap<>();

	private final Map<Integer, String> indexedImageIds = new HashMap<>();

	private int outputCursor;

	private int contentCursor;

	private int lastOutput = -1;

	private int lastContent = -1;

	String delta(ResponsesStreamEvent event) {
		Part part = part(index(event.outputIndex()), index(event.contentIndex()));
		if (part.done) {
			throw protocol("Text delta follows a completed content part");
		}
		part.value.append(event.delta() != null ? event.delta() : "");
		return drain();
	}

	String done(ResponsesStreamEvent event) {
		snapshot(index(event.outputIndex()), index(event.contentIndex()),
				ResponseValues.required(event.text(), "output text snapshot"));
		return drain();
	}

	String item(ResponsesOutputItem item, @Nullable Integer outputIndex) {
		// Unindexed non-text items cannot locate a gap in the text sequence.
		if (outputIndex != null || "message".equals(item.type())) {
			snapshot(item, index(outputIndex));
		}
		return drain();
	}

	String terminal(List<? extends @Nullable ResponsesOutputItem> output) {
		if (this.text.entrySet()
			.stream()
			.anyMatch(entry -> entry.getKey() >= output.size()
					&& entry.getValue().parts.values().stream().anyMatch(part -> !part.value.isEmpty()))) {
			throw protocol("Output snapshot omits a received text item");
		}
		for (int index = 0; index < output.size(); index++) {
			snapshot(ResponseValues.required(output.get(index), "output item"), index);
		}
		return drain();
	}

	List<Media> image(@Nullable ResponsesOutputItem item, @Nullable Integer outputIndex) {
		if (item == null || !"image_generation_call".equals(item.type()) || item.result() == null) {
			return List.of();
		}
		validateImageIdentity(item, outputIndex);
		Image byId = item.id() != null ? this.imageIds.get(item.id()) : null;
		Image byIndex = outputIndex != null ? this.imageIndexes.get(index(outputIndex)) : null;
		Image previous = byId != null ? byId : byIndex;
		Image current = new Image(item.result(), item.outputFormat() != null ? item.outputFormat() : "png");
		validateImageSnapshot(byId, byIndex, previous, current);
		retainImage(item, outputIndex, previous != null ? previous : current);
		return previous != null ? List.of() : GeneratedImageMapper.responsesMedia(List.of(item));
	}

	private void validateImageSnapshot(@Nullable Image byId, @Nullable Image byIndex, @Nullable Image previous,
			Image current) {
		if (byId != null && byIndex != null && byId != byIndex || previous != null && !previous.equals(current)) {
			throw protocol("Generated image snapshot contradicts an emitted item");
		}
	}

	private void validateImageIdentity(ResponsesOutputItem item, @Nullable Integer outputIndex) {
		if (item.id() == null && outputIndex == null) {
			throw protocol("Generated image requires an item ID or output index");
		}
		if (item.id() != null && outputIndex != null) {
			String previousId = this.indexedImageIds.putIfAbsent(outputIndex, item.id());
			if (previousId != null && !previousId.equals(item.id())) {
				throw protocol("Generated image ID contradicts its output index");
			}
		}
	}

	private void retainImage(ResponsesOutputItem item, @Nullable Integer outputIndex, Image retained) {
		if (item.id() != null) {
			this.imageIds.put(item.id(), retained);
		}
		if (outputIndex != null) {
			this.imageIndexes.put(outputIndex, retained);
		}
	}

	void clear() {
		this.text.clear();
		this.imageIds.clear();
		this.imageIndexes.clear();
		this.indexedImageIds.clear();
	}

	private void snapshot(ResponsesOutputItem item, int outputIndex) {
		TextItem state = this.text.computeIfAbsent(outputIndex, ignored -> new TextItem());
		List<ResponsesContent> content = "message".equals(item.type()) && item.content() != null
				? ResponseValues.items(item.content(), "message content") : List.of();
		for (int index = 0; index < content.size(); index++) {
			ResponsesContent part = content.get(index);
			snapshot(outputIndex, index, ("output_text".equals(part.type()) || "text".equals(part.type()))
					? Objects.requireNonNullElse(part.text(), "") : "");
		}
		if (state.parts.keySet().stream().anyMatch(index -> index >= content.size())) {
			throw protocol("Output snapshot omits a received content part");
		}
		state.done = true;
	}

	private void snapshot(int outputIndex, int contentIndex, String value) {
		Part part = part(outputIndex, contentIndex);
		if (!value.startsWith(part.value.toString())) {
			throw protocol("Text snapshot contradicts a received prefix");
		}
		if (value.length() > part.emitted && (outputIndex < this.lastOutput
				|| outputIndex == this.lastOutput && contentIndex < this.lastContent)) {
			throw protocol("Text snapshot extends a content part after later output was released");
		}
		if (value.length() > part.emitted && (outputIndex < this.outputCursor
				|| outputIndex == this.outputCursor && contentIndex < this.contentCursor)) {
			this.outputCursor = outputIndex;
			this.contentCursor = contentIndex;
		}
		part.value.replace(0, part.value.length(), value);
		part.done = true;
	}

	private Part part(int outputIndex, int contentIndex) {
		return this.text.computeIfAbsent(outputIndex, ignored -> new TextItem()).parts.computeIfAbsent(contentIndex,
				ignored -> new Part());
	}

	private String drain() {
		StringBuilder suffix = new StringBuilder();
		TextItem item;
		while ((item = this.text.get(this.outputCursor)) != null) {
			Part part = item.parts.get(this.contentCursor);
			if (part == null) {
				if (!item.done) {
					break;
				}
				this.outputCursor++;
				this.contentCursor = 0;
				continue;
			}
			if (part.value.length() > part.emitted) {
				this.lastOutput = this.outputCursor;
				this.lastContent = this.contentCursor;
			}
			suffix.append(part.value, part.emitted, part.value.length());
			part.emitted = part.value.length();
			if (!part.done) {
				break;
			}
			this.contentCursor++;
		}
		return suffix.toString();
	}

	private static int index(@Nullable Integer index) {
		if (index != null && index < 0) {
			throw protocol("Output and content indexes must be nonnegative");
		}
		return index != null ? index : 0;
	}

	private static OpenRouterProtocolException protocol(String message) {
		return new OpenRouterProtocolException("Responses stream: " + message);
	}

	private static final class TextItem {

		private final Map<Integer, Part> parts = new HashMap<>();

		private boolean done;

	}

	private static final class Part {

		private final StringBuilder value = new StringBuilder();

		private int emitted;

		private boolean done;

	}

	private record Image(String result, String format) {
	}

}
