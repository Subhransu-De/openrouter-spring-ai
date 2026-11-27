package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
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
 * argument deltas are partial parts. When an item completes, and again when the terminal
 * output repeats it, its part is emitted partial with only the text that has not streamed
 * yet, carrying the latest item as payload, so the aggregated message replays the final
 * item verbatim and a consumer of the stream never sees text twice. Reasoning that the
 * item replaces rather than extends is emitted complete instead, so that the aggregator
 * replaces the slot. An event without an output index has no part; the terminal output
 * places its item. Complete tool calls are emitted once, by the terminal event, so that
 * an incomplete round never yields a call.
 * <p>
 * One instance serves one subscription. It remembers the id that groups the chunks, the
 * reasoning text and summary each output index emitted, and the id and name of each
 * function call whose arguments stream.
 *
 * @author Subhransu De
 */
final class ResponsesStreamParts {

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private @Nullable String responseId;

	private final Map<Integer, StringBuilder> reasoningText = new HashMap<>();

	private final Map<Integer, StringBuilder> reasoningSummary = new HashMap<>();

	private final Map<Integer, AssistantMessage.ToolCall> streamingCalls = new HashMap<>();

	/**
	 * The id that groups the chunks of this stream in Spring AI's aggregator. The first
	 * event fixes it: the response id when that event carries one, as
	 * {@code response.created} does, or else a generated id, so that a stream whose id
	 * arrives only with its terminal event still aggregates to one message.
	 */
	@Nullable String responseId() {
		return this.responseId;
	}

	List<MessagePart> map(ResponsesStreamEvent event, @Nullable String reasoning, boolean summary,
			Map<Integer, String> text, @Nullable List<? extends @Nullable ResponsesOutputItem> output,
			List<IndexedItem> toolCalls, RefusalMetadata.Accumulator refusal) {
		remember(event);
		Map<Integer, Slot> slots = new TreeMap<>();
		int index = index(event.outputIndex());
		if (StringUtils.hasLength(reasoning)) {
			(summary ? this.reasoningSummary : this.reasoningText)
				.computeIfAbsent(index, ignored -> new StringBuilder())
				.append(reasoning);
			merge(slots, index,
					new ReasoningPart(summary ? null : reasoning, summary ? reasoning : null, null, Map.of()));
		}
		text.forEach((textIndex, value) -> merge(slots, textIndex, TextPart.of(value)));
		toolCallDelta(slots, index, event);
		completedItems(slots, event, output, refusal);
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
		this.reasoningText.clear();
		this.reasoningSummary.clear();
		this.streamingCalls.clear();
	}

	// The first event fixes the grouping id.
	private void remember(ResponsesStreamEvent event) {
		if (this.responseId == null) {
			ResponsesResult response = event.response();
			String id = response != null ? response.id() : null;
			this.responseId = StringUtils.hasText(id) ? id : "openrouter-stream-" + UUID.randomUUID();
		}
	}

	// An item completes with its done event, and again with the terminal output, which
	// may have changed it. A done event without an output index has no part: the
	// terminal output places its item.
	private void completedItems(Map<Integer, Slot> slots, ResponsesStreamEvent event,
			@Nullable List<? extends @Nullable ResponsesOutputItem> output, RefusalMetadata.Accumulator refusal) {
		ResponsesOutputItem item = event.item();
		Integer itemIndex = event.outputIndex();
		if (OUTPUT_ITEM_DONE.equals(event.type()) && item != null && itemIndex != null) {
			itemPart(slots, itemIndex, item, refusal, false);
		}
		if (output != null) {
			for (int position = 0; position < output.size(); position++) {
				itemPart(slots, position, ResponseValues.RESPONSES.required(output.get(position), "output item"),
						refusal, true);
			}
		}
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

	// The part of a completed item, emptied of what already streamed. Its text arrives
	// through the text reconciliation, function calls complete at the end, and generated
	// images are mapped with the media. Reasoning that the item does not extend is
	// emitted complete, so that the aggregator replaces what streamed.
	private void itemPart(Map<Integer, Slot> slots, int index, ResponsesOutputItem item,
			RefusalMetadata.Accumulator refusal, boolean terminal) {
		MessagePart part = MessageParts.responses(refusal.reconcile(index, item));
		if (part instanceof ReasoningPart reasoning
				&& (!extendsStream(this.reasoningText, index, reasoning.text(), terminal)
						|| !extendsStream(this.reasoningSummary, index, reasoning.summary(), terminal))) {
			replace(this.reasoningText, index, reasoning.text());
			replace(this.reasoningSummary, index, reasoning.summary());
			slots.put(index, new Slot(reasoning, false));
		}
		else if (part instanceof ReasoningPart reasoning) {
			merge(slots, index, new ReasoningPart(unseen(this.reasoningText, index, reasoning.text()),
					unseen(this.reasoningSummary, index, reasoning.summary()), reasoning.payload(), Map.of()));
		}
		else if (part instanceof TextPart textPart) {
			merge(slots, index, new TextPart("", textPart.payload(), Map.of()));
		}
		else if (part instanceof UnknownPart) {
			slots.put(index, new Slot(part, false));
		}
	}

	// Whether a snapshot continues what streamed. A done event may omit a field; the
	// terminal output omitting it clears it.
	private static boolean extendsStream(Map<Integer, StringBuilder> emitted, int index, @Nullable String snapshot,
			boolean terminal) {
		StringBuilder seen = emitted.get(index);
		return seen == null || (snapshot != null ? snapshot.startsWith(seen.toString()) : !terminal);
	}

	private static void replace(Map<Integer, StringBuilder> emitted, int index, @Nullable String snapshot) {
		if (snapshot != null) {
			emitted.put(index, new StringBuilder(snapshot));
		}
		else {
			emitted.remove(index);
		}
	}

	// The end of a snapshot that has not streamed yet; nothing when the snapshot is no
	// longer than what streamed.
	private static @Nullable String unseen(Map<Integer, StringBuilder> emitted, int index, @Nullable String snapshot) {
		StringBuilder seen = emitted.get(index);
		if (snapshot == null || seen != null && snapshot.length() <= seen.length()) {
			return null;
		}
		if (seen == null) {
			emitted.put(index, new StringBuilder(snapshot));
			return snapshot;
		}
		String suffix = snapshot.substring(seen.length());
		seen.append(suffix);
		return suffix;
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
