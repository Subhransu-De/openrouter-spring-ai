package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public final class OpenRouterResponsesStreamingResponseMapper {

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private final ResponsesStreamBudget budget;

	public OpenRouterResponsesStreamingResponseMapper() {
		this(new ObjectMapper(), OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS,
				OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_DURATION);
	}

	public OpenRouterResponsesStreamingResponseMapper(ObjectMapper objectMapper, long maxBytes, int maxChunks,
			Duration maxDuration) {
		this.budget = new ResponsesStreamBudget(objectMapper, maxBytes, maxChunks, maxDuration);
	}

	public Flux<ChatResponse> map(Flux<ResponsesStreamEvent> events) {
		return Flux.using(State::new,
				state -> this.budget.apply(events).map(state::map).concatWith(Mono.defer(state::complete)),
				State::clear);
	}

	/**
	 * Map one event without stream history. Text-done and message-item snapshots do not
	 * emit text. Generated images are emitted only by item-done events. Use
	 * {@link #map(Flux)} for subscription-local snapshot reconciliation.
	 */
	public ChatResponse map(ResponsesStreamEvent event) {
		return map(event, new ReasoningMetadata.Accumulator(), new ArrayList<>(), new RefusalMetadata.Accumulator(),
				null);
	}

	private ChatResponse map(ResponsesStreamEvent event, ReasoningMetadata.Accumulator accumulator,
			List<ResponsesOutputItem> pending, RefusalMetadata.Accumulator refusal,
			@Nullable ResponsesOutputState outputState) {
		validateEvent(event);
		String type = event.type();
		boolean incomplete = "response.incomplete".equals(type);
		ResponsesResult result = terminalResult(event);
		String nativeFinishReason = result != null ? FinishReasonMapper.responses(result, result.status()) : null;
		String status = result != null ? result.status() : null;
		List<AssistantMessage.ToolCall> toolCalls = toolCalls(event, result, pending,
				incomplete ? "incomplete" : status);
		String finishReason = toolCalls.isEmpty() ? nativeFinishReason : "tool_calls";
		String reasoning = "response.reasoning_text.delta".equals(type)
				|| "response.reasoning_summary_text.delta".equals(type) ? event.delta() : null;
		Map<String, Object> snapshot = reasoning(event, result, reasoning, accumulator);
		RefusalMetadata.put(snapshot, refusal.update(event));
		// Process item images before text reconciliation, as in the wire event order.
		List<Media> media = itemMedia(event, outputState);
		String text = text(event, result, outputState);
		if (result != null && result.output() != null && outputState != null) {
			media = terminalMedia(result.output(), outputState);
		}

		AssistantMessage assistantMessage = AssistantMessage.builder()
			.properties(snapshot)
			.content(text)
			.toolCalls(toolCalls)
			.media(media)
			.build();
		ChatGenerationMetadata.Builder generationMetadataBuilder = ChatGenerationMetadata.builder();
		generationMetadataBuilder.finishReason(FinishReasonMapper.map(finishReason));
		ResponseValues.ifPresent(nativeFinishReason,
				value -> generationMetadataBuilder.metadata("openrouter.native_finish_reason", value));
		ResponseValues.ifPresent(status,
				value -> generationMetadataBuilder.metadata("openrouter.responses.status", value));
		ResponseValues.ifPresent(result != null ? result.incompleteDetails() : null,
				value -> generationMetadataBuilder.metadata("openrouter.responses.incomplete_details", value));
		ResponseValues.ifPresent(snapshot.get(RefusalMetadata.REFUSAL),
				value -> generationMetadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ResponseValues.ifPresent(reasoning, value -> generationMetadataBuilder.metadata("openrouter.reasoning", value));
		ChatGenerationMetadata generationMetadata = generationMetadataBuilder.build();
		ChatResponseMetadata.Builder responseMetadata = ChatResponseMetadata.builder();
		responseMetadata.keyValue("openrouter.object", type);
		if (result != null) {
			ResponseValues.ifPresent(result.id(), responseMetadata::id);
			ResponseValues.ifPresent(result.model(), responseMetadata::model);
			ResponseValues.ifPresent(UsageMapper.map(result.usage()), responseMetadata::usage);
		}
		return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)),
				responseMetadata.build());
	}

	private void validateEvent(ResponsesStreamEvent event) {
		String type = event.type();
		if (event.error() != null || "error".equals(type) || type != null && type.endsWith(".error")) {
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					eventError(event), event.errorType());
		}
		if ("response.failed".equals(type)) {
			// HTTP 200 can carry a failed generation; never emit an empty finish chunk.
			ResponsesResult failed = event.response();
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					failed != null ? failed.error() : null, failed != null ? failed.errorType() : null);
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
		OpenRouterResponsesResponseMapper.validateTerminal(result, completed ? "completed" : "incomplete");
		return result;
	}

	private List<AssistantMessage.ToolCall> toolCalls(ResponsesStreamEvent event, @Nullable ResponsesResult result,
			List<ResponsesOutputItem> pending, @Nullable String status) {
		if (OUTPUT_ITEM_DONE.equals(event.type()) && event.item() != null
				&& "function_call".equals(event.item().type())) {
			// A later incomplete item or response must prevent every callback in the
			// round.
			pending.add(event.item());
		}
		if (result == null) {
			return List.of();
		}
		String reason = result.incompleteDetails() != null ? result.incompleteDetails().reason() : null;
		List<AssistantMessage.ToolCall> calls = OpenRouterResponsesResponseMapper.toolCalls(status, reason, pending);
		if (result.output() != null) {
			// Validate both representations: a snapshot cannot erase a non-final item
			// status.
			List<AssistantMessage.ToolCall> terminal = OpenRouterResponsesResponseMapper.toolCalls(status, reason,
					result.output());
			if (!terminal.isEmpty()) {
				calls = terminal;
			}
		}
		pending.clear();
		return calls;
	}

	private Map<String, Object> reasoning(ResponsesStreamEvent event, @Nullable ResponsesResult result,
			@Nullable String reasoning, ReasoningMetadata.Accumulator accumulator) {
		Map<String, Object> metadata = ReasoningMetadata.chat(reasoning, null);
		if (OUTPUT_ITEM_DONE.equals(event.type()) && event.item() != null) {
			metadata.putAll(ReasoningMetadata.responses(List.of(event.item())));
			metadata.remove(ReasoningMetadata.REASONING);
		}
		Map<String, Object> snapshot = accumulator.append(metadata);
		return result != null && result.output() != null
				? accumulator.replace(ReasoningMetadata.responses(result.output())) : snapshot;
	}

	private String text(ResponsesStreamEvent event, @Nullable ResponsesResult result,
			@Nullable ResponsesOutputState state) {
		if (result != null && result.output() != null) {
			return state != null ? state.terminal(result.output())
					: OpenRouterResponsesResponseMapper.text(result.output());
		}
		if ("response.output_text.delta".equals(event.type())) {
			return state != null ? state.delta(event) : event.delta() != null ? event.delta() : "";
		}
		if (state == null) {
			return "";
		}
		if ("response.output_text.done".equals(event.type())) {
			return state.done(event);
		}
		return OUTPUT_ITEM_DONE.equals(event.type()) && event.item() != null
				? state.item(event.item(), event.outputIndex()) : "";
	}

	private List<Media> itemMedia(ResponsesStreamEvent event, @Nullable ResponsesOutputState state) {
		if (!OUTPUT_ITEM_DONE.equals(event.type()) || event.item() == null
				|| !"image_generation_call".equals(event.item().type())) {
			return List.of();
		}
		return state != null ? state.image(event.item(), event.outputIndex())
				: GeneratedImageMapper.responsesMedia(List.of(event.item()));
	}

	private List<Media> terminalMedia(List<? extends @Nullable ResponsesOutputItem> output, ResponsesOutputState state) {
		List<Media> images = new ArrayList<>();
		for (int index = 0; index < output.size(); index++) {
			images.addAll(state.image(output.get(index), index));
		}
		return images;
	}

	private @Nullable StreamError eventError(ResponsesStreamEvent event) {
		StreamError nested = event.error();
		String code = nested != null && nested.code() != null ? nested.code() : event.code();
		String message = nested != null && nested.message() != null ? nested.message() : event.message();
		JsonNode metadata = mergeMetadata(event.metadata(), nested != null ? nested.metadata() : null);
		String errorType = nested != null ? nested.errorType() : null;
		return code != null || message != null || metadata != null || errorType != null
				? new StreamError(code, message, metadata, errorType) : null;
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

		private final List<ResponsesOutputItem> pending = new ArrayList<>();

		private final RefusalMetadata.Accumulator refusal = new RefusalMetadata.Accumulator();

		private final ResponsesOutputState output = new ResponsesOutputState();

		private synchronized ChatResponse map(ResponsesStreamEvent event) {
			return OpenRouterResponsesStreamingResponseMapper.this.map(event, this.reasoning, this.pending,
					this.refusal, this.output);
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
		}

	}

}
