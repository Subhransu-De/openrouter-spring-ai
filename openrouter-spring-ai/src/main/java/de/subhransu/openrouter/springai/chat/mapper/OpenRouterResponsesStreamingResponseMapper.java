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
		String type = event.type();
		boolean incomplete = "response.incomplete".equals(type);
		boolean completed = "response.completed".equals(type);
		boolean itemDone = "response.output_item.done".equals(type);
		if (event.error() != null || "error".equals(type) || type != null && type.endsWith(".error")) {
			StreamError error = eventError(event);
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					error, event.errorType());
		}

		String text = "";
		String reasoning = null;
		String finishReason = null;
		ResponsesResult result = null;
		List<AssistantMessage.ToolCall> toolCalls = List.of();
		List<Media> media = List.of();
		if ("response.output_text.delta".equals(type)) {
			text = event.delta() != null ? event.delta() : "";
			if (outputState != null) {
				text = outputState.delta(event);
			}
		}
		else if ("response.output_text.done".equals(type) && outputState != null) {
			text = outputState.done(event);
		}
		else if ("response.reasoning_text.delta".equals(type) || "response.reasoning_summary_text.delta".equals(type)) {
			reasoning = event.delta();
		}
		else if (itemDone && event.item() != null && "function_call".equals(event.item().type())) {
			// Wait for the whole round: a later incomplete item or response must prevent
			// every callback, including calls whose own item already completed.
			pending.add(event.item());
		}
		else if (itemDone && event.item() != null && "image_generation_call".equals(event.item().type())) {
			media = outputState != null ? outputState.image(event.item(), event.outputIndex())
					: GeneratedImageMapper.responsesMedia(List.of(event.item()));
		}
		else if (completed || incomplete) {
			result = event.response();
			if (result == null) {
				throw new OpenRouterTruncatedResponseException(
						"Responses stream terminated without a response snapshot");
			}
			String terminalStatus = completed ? "completed" : "incomplete";
			OpenRouterResponsesResponseMapper.validateTerminal(result, terminalStatus);
			finishReason = FinishReasonMapper.responses(result, terminalStatus);
		}
		else if ("response.failed".equals(type)) {
			// A failed generation ends the stream over HTTP 200; converting it into an
			// empty finish chunk would hide the provider error from consumers.
			ResponsesResult failed = event.response();
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					failed != null ? failed.error() : null, failed != null ? failed.errorType() : null);
		}

		String nativeFinishReason = finishReason;
		String status = result != null && result.status() != null ? result.status()
				: incomplete ? "incomplete" : completed ? "completed" : null;
		if (completed || incomplete) {
			String toolStatus = incomplete ? "incomplete" : status;
			String reason = result != null && result.incompleteDetails() != null ? result.incompleteDetails().reason()
					: null;
			toolCalls = OpenRouterResponsesResponseMapper.toolCalls(toolStatus, reason, pending);
			if (result != null && result.output() != null) {
				// Validate both representations so a terminal snapshot cannot erase an
				// explicitly non-final output_item.done status.
				List<AssistantMessage.ToolCall> terminalCalls = OpenRouterResponsesResponseMapper.toolCalls(toolStatus,
						reason, result.output());
				if (!terminalCalls.isEmpty()) {
					toolCalls = terminalCalls;
				}
			}
			pending.clear();
			if (!toolCalls.isEmpty()) {
				finishReason = "tool_calls";
			}
		}

		Map<String, Object> reasoningMetadata = ReasoningMetadata.chat(reasoning, null);
		if (itemDone && event.item() != null) {
			reasoningMetadata.putAll(ReasoningMetadata.responses(List.of(event.item())));
			reasoningMetadata.remove(ReasoningMetadata.REASONING);
		}
		Map<String, Object> snapshot = accumulator.append(reasoningMetadata);
		if (result != null && result.output() != null) {
			snapshot = accumulator.replace(ReasoningMetadata.responses(result.output()));
		}
		RefusalMetadata.put(snapshot, refusal.update(event));
		if (itemDone && event.item() != null && outputState != null) {
			text = outputState.item(event.item(), event.outputIndex());
		}
		if (result != null && result.output() != null) {
			text = outputState != null ? outputState.terminal(result.output())
					: OpenRouterResponsesResponseMapper.text(result.output());
			if (outputState != null) {
				List<Media> images = new ArrayList<>();
				for (int index = 0; index < result.output().size(); index++) {
					images.addAll(outputState.image(result.output().get(index), index));
				}
				media = images;
			}
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
