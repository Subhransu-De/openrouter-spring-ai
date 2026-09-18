package de.subhransu.openrouter.springai.chat.mapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
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

	public Flux<ChatResponse> map(Flux<ResponsesStreamEvent> events) {
		return Flux.defer(() -> {
			ReasoningMetadata.Accumulator reasoning = new ReasoningMetadata.Accumulator();
			List<ResponsesOutputItem> pending = new ArrayList<>();
			RefusalMetadata.Accumulator refusal = new RefusalMetadata.Accumulator();
			TextState text = new TextState();
			return events.map(event -> map(event, reasoning, pending, refusal, text))
				.concatWith(Mono
					.defer(() -> pending.isEmpty() ? Mono.empty() : Mono.error(new OpenRouterTruncatedResponseException(
							"Responses stream ended before tool round completion"))));
		});
	}

	public ChatResponse map(ResponsesStreamEvent event) {
		return map(event, new ReasoningMetadata.Accumulator(), new ArrayList<>(), new RefusalMetadata.Accumulator(),
				new TextState());
	}

	private ChatResponse map(ResponsesStreamEvent event, ReasoningMetadata.Accumulator accumulator,
			List<ResponsesOutputItem> pending, RefusalMetadata.Accumulator refusal, TextState textState) {
		String type = event.type();
		boolean incomplete = "response.incomplete".equals(type);
		if ("error".equals(type) || type != null && type.endsWith(".error")) {
			StreamError error = eventError(event);
			throw OpenRouterResponsesResponseMapper.failure("OpenRouter responses stream failed", String.valueOf(event),
					error, event.errorType());
		}

		// Prefer deltas; recover terminal-only text at completion without repeating
		// content already emitted by the stream.
		String text = "";
		String reasoning = null;
		String finishReason = null;
		ResponsesResult result = null;
		List<AssistantMessage.ToolCall> toolCalls = List.of();
		List<Media> media = List.of();
		if ("response.output_text.delta".equals(type)) {
			text = event.delta() != null ? event.delta() : "";
			textState.hasText |= !text.isEmpty();
		}
		else if ("response.reasoning_text.delta".equals(type) || "response.reasoning_summary_text.delta".equals(type)) {
			reasoning = event.delta();
		}
		else if ("response.output_item.done".equals(type) && event.item() != null
				&& "function_call".equals(event.item().type())) {
			// Wait for the whole round: a later incomplete item or response must prevent
			// every callback, including calls whose own item already completed.
			pending.add(event.item());
		}
		else if ("response.output_item.done".equals(type) && event.item() != null
				&& "image_generation_call".equals(event.item().type())) {
			// Image bytes are not streamed as text deltas, so the completed item is the
			// single source for the generated image; emitting it here does not duplicate
			// output.
			media = GeneratedImageMapper.responsesMedia(List.of(event.item()));
		}
		else if ("response.completed".equals(type)) {
			result = event.response();
			if (result == null && !pending.isEmpty()) {
				throw new OpenRouterTruncatedResponseException(
						"Responses tool round completed without a response snapshot");
			}
			finishReason = FinishReasonMapper.responses(result, "completed");
		}
		else if (incomplete) {
			result = event.response();
			finishReason = FinishReasonMapper.responses(result, "incomplete");
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
				: incomplete ? "incomplete" : "response.completed".equals(type) ? "completed" : null;
		if ("response.completed".equals(type) || incomplete) {
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
		if ("response.output_item.done".equals(type) && event.item() != null) {
			reasoningMetadata.putAll(ReasoningMetadata.responses(List.of(event.item())));
			reasoningMetadata.remove(ReasoningMetadata.REASONING);
		}
		Map<String, Object> snapshot = accumulator.append(reasoningMetadata);
		if (result != null && result.output() != null) {
			snapshot = accumulator.replace(ReasoningMetadata.responses(result.output()));
		}
		RefusalMetadata.put(snapshot, refusal.update(event));
		if (("response.completed".equals(type) || incomplete) && !textState.hasText
				&& snapshot.get(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS) instanceof List<?> output) {
			text = OpenRouterResponsesResponseMapper
				.text(output.stream().map(ResponsesOutputItem.class::cast).toList());
			textState.hasText = !text.isEmpty();
		}
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.properties(snapshot)
			.content(text)
			.toolCalls(toolCalls)
			.media(media)
			.build();
		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(FinishReasonMapper.map(finishReason))
			.metadata("openrouter.native_finish_reason", nativeFinishReason)
			.metadata("openrouter.responses.status", status)
			.metadata("openrouter.responses.incomplete_details", result != null ? result.incompleteDetails() : null)
			.metadata(RefusalMetadata.REFUSAL, snapshot.get(RefusalMetadata.REFUSAL))
			.metadata("openrouter.reasoning", reasoning)
			.build();
		ChatResponseMetadata.Builder responseMetadata = ChatResponseMetadata.builder()
			.keyValue("openrouter.object", type);
		if (result != null) {
			responseMetadata.id(result.id()).model(result.model()).usage(UsageMapper.map(result.usage()));
		}
		return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)),
				responseMetadata.build());
	}

	private StreamError eventError(ResponsesStreamEvent event) {
		StreamError nested = event.error();
		String code = nested != null && nested.code() != null ? nested.code() : event.code();
		String message = nested != null && nested.message() != null ? nested.message() : event.message();
		JsonNode metadata = mergeMetadata(event.metadata(), nested != null ? nested.metadata() : null);
		String errorType = nested != null ? nested.errorType() : null;
		return code != null || message != null || metadata != null || errorType != null
				? new StreamError(code, message, metadata, errorType) : null;
	}

	private JsonNode mergeMetadata(JsonNode root, JsonNode nested) {
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

	private static final class TextState {

		private boolean hasText;

	}

}
