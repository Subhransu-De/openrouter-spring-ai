package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import de.subhransu.openrouter.springai.chat.mapper.ResponsesStreamParts.IndexedItem;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponseError;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Maps Responses stream events to chat responses. The parts of each chunk follow Spring
 * AI's streaming contract, see {@link ResponsesStreamParts}.
 *
 * @author Subhransu De
 */
public final class OpenRouterResponsesStreamingResponseMapper {

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private static final ChatGenerationMetadata EMPTY_GENERATION_METADATA = ChatGenerationMetadata.builder().build();

	private final ResponsesStreamBudget budget;

	private final OpenRouterDiagnosticSanitizer sanitizer;

	public OpenRouterResponsesStreamingResponseMapper() {
		this(new ObjectMapper(), OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_DURATION, OpenRouterDiagnosticSanitizer.keyless());
	}

	public OpenRouterResponsesStreamingResponseMapper(ObjectMapper objectMapper, long maxBytes, int maxChunks,
			Duration maxDuration, OpenRouterDiagnosticSanitizer sanitizer) {
		this.budget = new ResponsesStreamBudget(objectMapper, maxBytes, maxChunks, maxDuration);
		this.sanitizer = sanitizer;
	}

	public Flux<ChatResponse> map(Flux<ResponsesStreamEvent> events) {
		return Flux.using(State::new,
				state -> this.budget.apply(events).map(state::map).concatWith(Mono.defer(state::complete)),
				State::clear);
	}

	/**
	 * Map one event without stream history. Text-done and message-item snapshots do not
	 * emit text. Generated images are emitted only by item-done events. Parts carry no
	 * stream index. Use {@link #map(Flux)} for subscription-local snapshot reconciliation
	 * and indexed parts.
	 */
	public ChatResponse map(ResponsesStreamEvent event) {
		return map(event, new ReasoningMetadata.Accumulator(), new ArrayList<>(), new RefusalMetadata.Accumulator(),
				null, null);
	}

	private ChatResponse map(ResponsesStreamEvent event, ReasoningMetadata.Accumulator accumulator,
			List<IndexedItem> pending, RefusalMetadata.Accumulator refusal, @Nullable ResponsesOutputState outputState,
			@Nullable ResponsesStreamParts parts) {
		validateEvent(event);
		String type = event.type();
		ResponsesResult result = terminalResult(event);
		String nativeFinishReason = FinishReasonMapper.responses(result, null);
		String status = result != null ? result.status() : null;
		List<IndexedItem> toolCalls = ResponsesStreamParts.toolCalls(event, result, pending, status, this.sanitizer);
		String finishReason = toolCalls.isEmpty() ? nativeFinishReason : "tool_calls";
		boolean summary = "response.reasoning_summary_text.delta".equals(type);
		String reasoning = "response.reasoning_text.delta".equals(type) || summary ? event.delta() : null;
		Map<String, Object> snapshot = reasoning(event, result, reasoning, accumulator);
		RefusalMetadata.put(snapshot, refusal.update(event));
		Integer itemIndex = event.outputIndex();
		// Process item images before text reconciliation, as in the wire event order.
		List<MessagePart> itemMedia = itemMedia(event, itemIndex, outputState);
		Map<Integer, String> text = text(event, itemIndex, result, outputState);
		var output = result != null ? result.output() : null;
		List<MessagePart> media = terminalMedia(output, outputState, itemMedia);

		List<MessagePart> messageParts = parts != null
				? parts.map(event, reasoning, summary, text, output, toolCalls, refusal)
				: ResponsesStreamParts.unindexed(reasoning, summary, text, toolCalls);
		messageParts.addAll(media);
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.parts(MessageParts.orEmptyText(messageParts))
			.properties(snapshot)
			.build();
		ChatGenerationMetadata generationMetadata = outputState == null || result != null || !snapshot.isEmpty()
				? generationMetadata(result, finishReason, nativeFinishReason, snapshot, reasoning)
				: EMPTY_GENERATION_METADATA;
		ChatResponseMetadata.Builder responseMetadata = ChatResponseMetadata.builder();
		responseMetadata.keyValue(OpenRouterMetadata.OBJECT, type);
		String responseId = parts != null ? parts.responseId() : null;
		if (result != null) {
			ResponseValues.ifPresent(result.id(), responseMetadata::id);
			ResponseValues.ifPresent(result.model(), responseMetadata::model);
			ResponseValues.ifPresent(UsageMapper.map(result.usage()), responseMetadata::usage);
		}
		// The aggregator groups indexed parts by response id, so every chunk carries it.
		ResponseValues.ifPresent(responseId, responseMetadata::id);
		return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)),
				responseMetadata.build());
	}

	private static ChatGenerationMetadata generationMetadata(@Nullable ResponsesResult result,
			@Nullable String finishReason, @Nullable String nativeFinishReason, Map<String, Object> snapshot,
			@Nullable String reasoning) {
		ChatGenerationMetadata.Builder generationMetadataBuilder = ChatGenerationMetadata.builder();
		generationMetadataBuilder.finishReason(FinishReasonMapper.map(finishReason));
		if (result != null) {
			generationMetadataBuilder
				.finishReasonCategory(FinishReasonMapper.category(snapshot.get(RefusalMetadata.REFUSAL)));
			ResponseValues.ifPresent(result.status(),
					value -> generationMetadataBuilder.metadata(OpenRouterMetadata.RESPONSES_STATUS, value));
			ResponseValues.ifPresent(result.incompleteDetails(), value -> generationMetadataBuilder
				.metadata(OpenRouterMetadata.RESPONSES_INCOMPLETE_DETAILS, value));
		}
		ResponseValues.ifPresent(nativeFinishReason,
				value -> generationMetadataBuilder.metadata(OpenRouterMetadata.NATIVE_FINISH_REASON, value));
		ResponseValues.ifPresent(snapshot.get(RefusalMetadata.REFUSAL),
				value -> generationMetadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ResponseValues.ifPresent(reasoning,
				value -> generationMetadataBuilder.metadata(ReasoningMetadata.REASONING, value));
		return generationMetadataBuilder.build();
	}

	private void validateEvent(ResponsesStreamEvent event) {
		String type = event.type();
		if (event.error() != null || "error".equals(type) || type != null && type.endsWith(".error")) {
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					eventError(event), event.errorType(), this.sanitizer);
		}
		ResponseValues.RESPONSES.requiredText(type, "Responses event type");
		if ("response.failed".equals(type)) {
			// HTTP 200 can carry a failed generation; never emit an empty finish chunk.
			ResponsesResult failed = event.response();
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					failed != null ? failed.error() : null, failed != null ? failed.errorType() : null, this.sanitizer);
		}
	}

	private @Nullable ResponsesResult terminalResult(ResponsesStreamEvent event) {
		boolean completed = "response.completed".equals(event.type());
		if (!completed && !"response.incomplete".equals(event.type())) {
			return null;
		}
		ResponsesResult result = event.response();
		if (result == null) {
			throw new OpenRouterTruncatedResponseException("Responses stream terminated without a response snapshot");
		}
		OpenRouterResponsesResponseMapper.validateTerminal(result, completed ? "completed" : "incomplete",
				this.sanitizer);
		return result;
	}

	private Map<String, Object> reasoning(ResponsesStreamEvent event, @Nullable ResponsesResult result,
			@Nullable String reasoning, ReasoningMetadata.Accumulator accumulator) {
		Map<String, Object> metadata = ReasoningMetadata.chat(reasoning, null);
		var item = event.item();
		if (OUTPUT_ITEM_DONE.equals(event.type()) && item != null) {
			metadata.putAll(ReasoningMetadata.responses(List.of(item)));
			metadata.remove(ReasoningMetadata.REASONING);
		}
		Map<String, Object> snapshot = accumulator.append(metadata);
		var output = result != null ? result.output() : null;
		return output != null ? accumulator.replace(ReasoningMetadata.responses(output)) : snapshot;
	}

	// The released text by output index.
	private Map<Integer, String> text(ResponsesStreamEvent event, @Nullable Integer itemIndex,
			@Nullable ResponsesResult result, @Nullable ResponsesOutputState state) {
		var output = result != null ? result.output() : null;
		if (output != null) {
			return state != null ? state.terminal(output) : Map.of(0, OpenRouterResponsesResponseMapper.text(output));
		}
		if ("response.output_text.delta".equals(event.type())) {
			return state != null ? state.delta(event)
					: Map.of(0, event.delta() != null ? Objects.requireNonNull(event.delta()) : "");
		}
		if (state == null) {
			return Map.of();
		}
		if ("response.output_text.done".equals(event.type())) {
			return state.done(event);
		}
		var item = event.item();
		return OUTPUT_ITEM_DONE.equals(event.type()) && item != null ? state.item(item, itemIndex) : Map.of();
	}

	private List<MessagePart> itemMedia(ResponsesStreamEvent event, @Nullable Integer itemIndex,
			@Nullable ResponsesOutputState state) {
		var item = event.item();
		if (!OUTPUT_ITEM_DONE.equals(event.type()) || item == null || !"image_generation_call".equals(item.type())) {
			return List.of();
		}
		if (state == null) {
			return GeneratedImageMapper.responsesMedia(List.of(item)).stream().<MessagePart>map(MediaPart::of).toList();
		}
		// The terminal output places an image whose event has no output index.
		return itemIndex != null ? mediaParts(state.image(item, itemIndex), itemIndex) : List.of();
	}

	// The images of the terminal output replace those of the event.
	private List<MessagePart> terminalMedia(@Nullable List<? extends @Nullable ResponsesOutputItem> output,
			@Nullable ResponsesOutputState state, List<MessagePart> itemMedia) {
		if (output == null || state == null) {
			return itemMedia;
		}
		List<MessagePart> images = new ArrayList<>();
		for (int index = 0; index < output.size(); index++) {
			images.addAll(mediaParts(state.image(output.get(index), index), index));
		}
		return images;
	}

	// A generated image is the complete part of its output index, as in Spring AI's
	// Responses model, so the aggregated message keeps it where a call has it.
	private static List<MessagePart> mediaParts(List<Media> media, @Nullable Integer index) {
		return media.stream()
			.map(medium -> index != null ? StreamingParts.complete(MediaPart.of(medium), index) : MediaPart.of(medium))
			.toList();
	}

	private @Nullable ResponseError eventError(ResponsesStreamEvent event) {
		ResponseError nested = event.error();
		String code = nested != null && nested.code() != null ? nested.code() : event.code();
		String message = nested != null && nested.message() != null ? nested.message() : event.message();
		JsonNode metadata = mergeMetadata(event.metadata(), nested != null ? nested.metadata() : null);
		String errorType = nested != null ? nested.errorType() : null;
		return code != null || message != null || metadata != null || errorType != null
				? new ResponseError(code, message, metadata, errorType) : null;
	}

	private @Nullable JsonNode mergeMetadata(@Nullable JsonNode root, @Nullable JsonNode nested) {
		if (root == null) {
			return nested;
		}
		if (nested == null) {
			return root;
		}
		if (root.isObject() && nested.isObject()) {
			ObjectNode merged = (ObjectNode) nested.deepCopy();
			root.properties().forEach(entry -> merged.set(entry.getKey(), entry.getValue().deepCopy()));
			return merged;
		}
		return nested;
	}

	private final class State {

		private final ReasoningMetadata.Accumulator reasoning = new ReasoningMetadata.Accumulator();

		private final List<IndexedItem> pending = new ArrayList<>();

		private final RefusalMetadata.Accumulator refusal = new RefusalMetadata.Accumulator();

		private final ResponsesOutputState output = new ResponsesOutputState();

		private final ResponsesStreamParts parts = new ResponsesStreamParts();

		private synchronized ChatResponse map(ResponsesStreamEvent event) {
			return OpenRouterResponsesStreamingResponseMapper.this.map(event, this.reasoning, this.pending,
					this.refusal, this.output, this.parts);
		}

		private synchronized Mono<ChatResponse> complete() {
			return this.pending.isEmpty() ? Mono.empty() : Mono
				.error(new OpenRouterTruncatedResponseException("Responses stream ended before tool round completion"));
		}

		private synchronized void clear() {
			this.pending.clear();
			this.reasoning.clear();
			this.refusal.clear();
			this.output.clear();
			this.parts.clear();
		}

	}

}
