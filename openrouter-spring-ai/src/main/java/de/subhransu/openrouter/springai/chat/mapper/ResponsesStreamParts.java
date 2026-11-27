package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.util.StringUtils;

/**
 * The parts of a Responses stream, following Spring AI's {@link StreamingParts} contract.
 * The output index of an item is the index of its part. Text, reasoning and tool-call
 * argument deltas are partial parts. When an item completes, its part is emitted partial
 * and emptied of what already streamed, carrying the item as payload, so the aggregated
 * message replays it verbatim. Complete tool calls are emitted once, by the terminal
 * event, so that an incomplete round never yields a call.
 * <p>
 * One instance serves one subscription. It remembers the response id, which output
 * indexes streamed reasoning deltas or completed their item, and the id and name of each
 * function call whose arguments stream.
 *
 * @author Subhransu De
 */
final class ResponsesStreamParts {

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private @Nullable String responseId;

	private final Set<Integer> streamedReasoningText = new HashSet<>();

	private final Set<Integer> streamedReasoningSummary = new HashSet<>();

	private final Set<Integer> doneItems = new HashSet<>();

	private final Map<Integer, AssistantMessage.ToolCall> streamingCalls = new HashMap<>();

	@Nullable String responseId() {
		return this.responseId;
	}

	List<MessagePart> map(ResponsesStreamEvent event, @Nullable String reasoning, boolean summary,
			Map<Integer, String> text, @Nullable List<? extends @Nullable ResponsesOutputItem> output,
			List<IndexedItem> toolCalls) {
		ResponsesResult response = event.response();
		if (this.responseId == null && response != null) {
			this.responseId = response.id();
		}
		Map<Integer, Slot> slots = new TreeMap<>();
		int index = index(event.outputIndex());
		if (StringUtils.hasLength(reasoning)) {
			(summary ? this.streamedReasoningSummary : this.streamedReasoningText).add(index);
			merge(slots, index,
					new ReasoningPart(summary ? null : reasoning, summary ? reasoning : null, null, Map.of()));
		}
		text.forEach((textIndex, value) -> merge(slots, textIndex, TextPart.of(value)));
		toolCallDelta(slots, index, event);
		completedItems(slots, index, event, output);
		List<MessagePart> parts = new ArrayList<>();
		for (IndexedItem toolCall : toolCalls) {
			ToolCallPart part = toolCallPart(toolCall.item());
			if (toolCall.index() != null) {
				slots.put(toolCall.index(), new Slot(part, false));
			}
			else {
				parts.add(part);
			}
		}
		slots.forEach((slotIndex, slot) -> parts.add(slot.partial() ? StreamingParts.partial(slot.part(), slotIndex)
				: StreamingParts.complete(slot.part(), slotIndex)));
		return parts;
	}

	/**
	 * The tool calls of the round, once the terminal event arrives: those of the terminal
	 * output, or else those of the completed items, each validated as final.
	 */
	static List<IndexedItem> toolCalls(ResponsesStreamEvent event, @Nullable ResponsesResult result,
			List<IndexedItem> pending, @Nullable String status, OpenRouterDiagnosticSanitizer sanitizer) {
		var item = event.item();
		if (OUTPUT_ITEM_DONE.equals(event.type()) && item != null && "function_call".equals(item.type())) {
			// A later incomplete item or response must prevent every callback in the
			// round.
			pending.add(new IndexedItem(event.outputIndex(), item));
		}
		if (result == null) {
			return List.of();
		}
		var details = result.incompleteDetails();
		String reason = details != null ? details.reason() : null;
		OpenRouterResponsesResponseMapper.toolCalls(status, reason, pending.stream().map(IndexedItem::item).toList(),
				sanitizer);
		List<IndexedItem> calls = List.copyOf(pending);
		var output = result.output();
		if (output != null) {
			// Validate both representations: a snapshot cannot erase a non-final item
			// status.
			List<IndexedItem> terminal = new ArrayList<>();
			List<ResponsesOutputItem> items = ResponseValues.RESPONSES.items(output, "output item");
			for (int index = 0; index < items.size(); index++) {
				if ("function_call".equals(items.get(index).type())) {
					terminal.add(new IndexedItem(index, items.get(index)));
				}
			}
			OpenRouterResponsesResponseMapper.toolCalls(status, reason, items, sanitizer);
			if (!terminal.isEmpty()) {
				calls = terminal;
			}
		}
		pending.clear();
		return calls;
	}

	/** The parts of a stateless mapping, without stream index. */
	static List<MessagePart> unindexed(@Nullable String reasoning, boolean summary, Map<Integer, String> text,
			List<IndexedItem> toolCalls) {
		List<MessagePart> parts = new ArrayList<>();
		if (StringUtils.hasLength(reasoning)) {
			parts.add(new ReasoningPart(summary ? null : reasoning, summary ? reasoning : null, null, Map.of()));
		}
		String joined = String.join("", text.values());
		if (!joined.isEmpty()) {
			parts.add(TextPart.of(joined));
		}
		toolCalls.forEach(call -> parts.add(toolCallPart(call.item())));
		return parts;
	}

	void clear() {
		this.streamedReasoningText.clear();
		this.streamedReasoningSummary.clear();
		this.doneItems.clear();
		this.streamingCalls.clear();
	}

	// A call is announced when its item is added; its arguments deltas follow.
	private void toolCallDelta(Map<Integer, Slot> slots, int index, ResponsesStreamEvent event) {
		ResponsesOutputItem item = event.item();
		if ("response.output_item.added".equals(event.type()) && item != null && "function_call".equals(item.type())
				&& StringUtils.hasText(item.callId()) && StringUtils.hasText(item.name())) {
			AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(Objects.requireNonNull(item.callId()),
					"function", Objects.requireNonNull(item.name()), "");
			this.streamingCalls.put(index, call);
			merge(slots, index, ToolCallPart.of(call));
		}
		AssistantMessage.ToolCall call = this.streamingCalls.get(index);
		String arguments = event.delta();
		if ("response.function_call_arguments.delta".equals(event.type()) && call != null
				&& StringUtils.hasLength(arguments)) {
			merge(slots, index,
					ToolCallPart.of(new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), arguments)));
		}
	}

	// An item completes with its done event, or with the terminal output when its done
	// event never arrived.
	private void completedItems(Map<Integer, Slot> slots, int index, ResponsesStreamEvent event,
			@Nullable List<? extends @Nullable ResponsesOutputItem> output) {
		ResponsesOutputItem item = event.item();
		if (OUTPUT_ITEM_DONE.equals(event.type()) && item != null) {
			this.doneItems.add(index);
			itemPart(slots, index, item);
		}
		if (output == null) {
			return;
		}
		for (int position = 0; position < output.size(); position++) {
			ResponsesOutputItem outputItem = output.get(position);
			if (outputItem != null && this.doneItems.add(position)) {
				itemPart(slots, position, outputItem);
			}
		}
	}

	// The part of a completed item, emptied of what already streamed. Its text arrives
	// through the text reconciliation; function calls complete at the end.
	private void itemPart(Map<Integer, Slot> slots, int index, ResponsesOutputItem item) {
		MessagePart part = MessageParts.responses(item);
		if (part instanceof ReasoningPart reasoning) {
			merge(slots, index,
					new ReasoningPart(this.streamedReasoningText.contains(index) ? null : reasoning.text(),
							this.streamedReasoningSummary.contains(index) ? null : reasoning.summary(),
							reasoning.payload(), Map.of()));
		}
		else if (part instanceof TextPart textPart) {
			merge(slots, index, new TextPart("", textPart.payload(), Map.of()));
		}
		else if (part instanceof UnknownPart) {
			slots.put(index, new Slot(part, false));
		}
	}

	private static ToolCallPart toolCallPart(ResponsesOutputItem item) {
		return new ToolCallPart(OpenRouterResponsesResponseMapper.toolCall(item), MessageParts.responsesPayload(item),
				Map.of());
	}

	// A chunk holds at most one part per index, so partial parts of one index combine.
	private static void merge(Map<Integer, Slot> slots, int index, MessagePart part) {
		Slot earlier = slots.get(index);
		MessagePart merged = part;
		if (earlier != null && earlier.part() instanceof TextPart first && part instanceof TextPart second) {
			merged = new TextPart(first.text() + second.text(),
					second.payload() != null ? second.payload() : first.payload(), Map.of());
		}
		else if (earlier != null && earlier.part() instanceof ReasoningPart first
				&& part instanceof ReasoningPart second) {
			merged = new ReasoningPart(concat(first.text(), second.text()), concat(first.summary(), second.summary()),
					second.payload() != null ? second.payload() : first.payload(), Map.of());
		}
		slots.put(index, new Slot(merged, true));
	}

	private static @Nullable String concat(@Nullable String first, @Nullable String second) {
		return first == null ? second : second == null ? first : first + second;
	}

	private static int index(@Nullable Integer index) {
		return index != null ? index : 0;
	}

	/**
	 * A function call item and its output index, when the stream sent one.
	 *
	 * @param index the output index, or {@code null}
	 * @param item the item
	 */
	record IndexedItem(@Nullable Integer index, ResponsesOutputItem item) {
	}

	private record Slot(MessagePart part, boolean partial) {
	}

}
