package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;

// Subscription-local prefixes. Later parts wait until earlier parts have a final snapshot.
final class ResponsesOutputState {

	private final Map<Integer, TextItem> text = new HashMap<>();

	private final Map<Integer, Image> images = new HashMap<>();

	private final Map<Integer, String> imageIds = new HashMap<>();

	private int outputCursor;

	private int contentCursor;

	private int lastOutput = -1;

	private int lastContent = -1;

	Map<Integer, String> delta(ResponsesStreamEvent event) {
		Part part = part(index(event.outputIndex()), index(event.contentIndex()));
		if (part.done) {
			throw protocol("Text delta follows a completed content part");
		}
		part.value.append(event.delta() != null ? event.delta() : "");
		return drain();
	}

	Map<Integer, String> done(ResponsesStreamEvent event) {
		snapshot(index(event.outputIndex()), index(event.contentIndex()),
				ResponseValues.RESPONSES.required(event.text(), "output text snapshot"));
		return drain();
	}

	// An item without an output index waits for the terminal output, which places it.
	Map<Integer, String> item(ResponsesOutputItem item, @Nullable Integer outputIndex) {
		if (outputIndex == null) {
			return Map.of();
		}
		snapshot(item, index(outputIndex));
		return drain();
	}

	Map<Integer, String> terminal(List<? extends @Nullable ResponsesOutputItem> output) {
		if (this.text.entrySet()
			.stream()
			.anyMatch(entry -> entry.getKey() >= output.size()
					&& entry.getValue().parts.values().stream().anyMatch(part -> !part.value.isEmpty()))) {
			throw protocol("Output snapshot omits a received text item");
		}
		for (int index = 0; index < output.size(); index++) {
			snapshot(ResponseValues.RESPONSES.required(output.get(index), "output item"), index);
		}
		return drain();
	}

	// The media of an image item the first time its output index completes.
	List<Media> image(@Nullable ResponsesOutputItem item, int outputIndex) {
		if (item == null || !"image_generation_call".equals(item.type()) || item.result() == null) {
			return List.of();
		}
		index(outputIndex);
		if (item.id() != null) {
			String previousId = this.imageIds.putIfAbsent(outputIndex, item.id());
			if (previousId != null && !previousId.equals(item.id())) {
				throw protocol("Generated image ID contradicts its output index");
			}
		}
		Image current = new Image(item.result(), item.outputFormat() != null ? item.outputFormat() : "png");
		Image previous = this.images.putIfAbsent(outputIndex, current);
		if (previous != null && !previous.equals(current)) {
			throw protocol("Generated image snapshot contradicts an emitted item");
		}
		return previous != null ? List.of() : GeneratedImageMapper.responsesMedia(List.of(item));
	}

	void clear() {
		this.text.clear();
		this.images.clear();
		this.imageIds.clear();
	}

	private void snapshot(ResponsesOutputItem item, int outputIndex) {
		TextItem state = this.text.computeIfAbsent(outputIndex, ignored -> new TextItem());
		List<ResponsesContent> content = "message".equals(item.type()) && item.content() != null
				? ResponseValues.RESPONSES.items(item.content(), "message content") : List.of();
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

	// The released text by output index, in output order.
	private Map<Integer, String> drain() {
		Map<Integer, String> released = new LinkedHashMap<>();
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
				released.merge(this.outputCursor, part.value.substring(part.emitted), String::concat);
			}
			part.emitted = part.value.length();
			if (!part.done) {
				break;
			}
			this.contentCursor++;
		}
		return released;
	}

	private static int index(@Nullable Integer index) {
		if (index != null && index < 0) {
			throw protocol("Output and content indexes must be nonnegative");
		}
		return index != null ? index : 0;
	}

	private static OpenRouterProtocolException protocol(String message) {
		return protocol(OpenRouterProtocolException.Kind.CONTRACT, message);
	}

	private static OpenRouterProtocolException protocol(OpenRouterProtocolException.Kind kind, String message) {
		return new OpenRouterProtocolException(kind, "/responses", "Responses stream: " + message);
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
