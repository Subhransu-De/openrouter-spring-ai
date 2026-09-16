package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;

public final class OpenRouterResponsesResponseMapper {

	public ChatResponse map(ResponsesResult response) {
		if ("failed".equals(response.status())) {
			// Failed generations arrive with HTTP 200; mapping them to an empty message
			// would make a provider failure look like a valid empty answer.
			throw OpenRouterApiExceptionFactory.create("OpenRouter responses request failed",
					response.error() != null ? response.error().toString() : response.status(), response.error(),
					response.errorType());
		}
		List<AssistantMessage.ToolCall> toolCalls = toolCalls(response.status(),
				response.incompleteDetails() != null ? response.incompleteDetails().reason() : null, response.output());
		Map<String, Object> properties = ReasoningMetadata.responses(response.output());
		RefusalMetadata.put(properties, RefusalMetadata.responses(response.output()));
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(text(response))
			.properties(properties)
			.toolCalls(toolCalls)
			.media(GeneratedImageMapper.responsesMedia(response.output()))
			.build();
		String nativeReason = FinishReasonMapper.responses(response, response.status());
		String finishReason = toolCalls.isEmpty() ? FinishReasonMapper.map(nativeReason) : "TOOL_CALLS";
		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(finishReason)
			.metadata("openrouter.native_finish_reason", nativeReason)
			.metadata("openrouter.responses.status", response.status())
			.metadata("openrouter.responses.incomplete_details", response.incompleteDetails())
			.metadata(RefusalMetadata.REFUSAL, properties.get(RefusalMetadata.REFUSAL))
			.metadata(ReasoningMetadata.REASONING, assistantMessage.getMetadata().get(ReasoningMetadata.REASONING))
			.build();
		ChatResponseMetadata responseMetadata = ChatResponseMetadata.builder()
			.id(response.id())
			.model(response.model())
			.usage(UsageMapper.map(response.usage()))
			.keyValue("openrouter.object", response.object())
			.keyValue("openrouter.created", response.createdAt())
			.build();
		return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)), responseMetadata);
	}

	// Accept absent status for compatibility; reject every explicit non-final status.
	static List<AssistantMessage.ToolCall> toolCalls(String status, String reason, List<ResponsesOutputItem> output) {
		if (CollectionUtils.isEmpty(output)) {
			return List.of();
		}
		List<ResponsesOutputItem> calls = output.stream().filter(item -> "function_call".equals(item.type())).toList();
		for (ResponsesOutputItem item : calls) {
			if (status != null && !"completed".equals(status)
					|| item.status() != null && !"completed".equals(item.status())) {
				throw new OpenRouterTruncatedResponseException("Responses tool round is not complete: response status="
						+ OpenRouterExceptionMessage.sanitize(status) + ", item status="
						+ OpenRouterExceptionMessage.sanitize(item.status()) + ", incomplete reason="
						+ OpenRouterExceptionMessage.sanitize(reason));
			}
		}
		return calls.stream()
			.map(item -> new AssistantMessage.ToolCall(item.callId(), "function", item.name(), item.arguments()))
			.toList();
	}

	private String text(ResponsesResult response) {
		if (CollectionUtils.isEmpty(response.output())) {
			return "";
		}
		return response.output()
			.stream()
			.filter(item -> "message".equals(item.type()))
			.map(this::text)
			.filter(Objects::nonNull)
			.reduce("", String::concat);
	}

	private String text(ResponsesOutputItem item) {
		if (CollectionUtils.isEmpty(item.content())) {
			return "";
		}
		return item.content()
			.stream()
			.filter(content -> "output_text".equals(content.type()) || "text".equals(content.type()))
			.map(ResponsesContent::text)
			.filter(Objects::nonNull)
			.reduce("", String::concat);
	}

}
