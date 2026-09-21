package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.CollectionUtils;

public final class OpenRouterResponsesResponseMapper {

	public ChatResponse map(@Nullable ResponsesResult response) {
		response = ResponseValues.required(response, "Responses result");
		if ("failed".equals(response.status())) {
			// Failed generations arrive with HTTP 200; mapping them to an empty message
			// would make a provider failure look like a valid empty answer.
			throw failure("OpenRouter responses request failed",
					response.error() != null ? response.error().toString() : response.status(), response.error(),
					response.errorType());
		}
		List<AssistantMessage.ToolCall> toolCalls = toolCalls(response.status(),
				response.incompleteDetails() != null ? response.incompleteDetails().reason() : null, response.output());
		Map<String, Object> properties = ReasoningMetadata.responses(response.output());
		RefusalMetadata.put(properties, RefusalMetadata.responses(response.output()));
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(text(response.output()))
			.properties(properties)
			.toolCalls(toolCalls)
			.media(GeneratedImageMapper.responsesMedia(response.output()))
			.build();
		String nativeReason = FinishReasonMapper.responses(response, response.status());
		String finishReason = toolCalls.isEmpty() ? FinishReasonMapper.map(nativeReason) : "TOOL_CALLS";
		ChatGenerationMetadata.Builder generationMetadataBuilder = ChatGenerationMetadata.builder();
		generationMetadataBuilder.finishReason(finishReason);
		ResponseValues.ifPresent(nativeReason,
				value -> generationMetadataBuilder.metadata("openrouter.native_finish_reason", value));
		ResponseValues.ifPresent(response.status(),
				value -> generationMetadataBuilder.metadata("openrouter.responses.status", value));
		ResponseValues.ifPresent(response.incompleteDetails(),
				value -> generationMetadataBuilder.metadata("openrouter.responses.incomplete_details", value));
		ResponseValues.ifPresent(properties.get(RefusalMetadata.REFUSAL),
				value -> generationMetadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ResponseValues.ifPresent(assistantMessage.getMetadata().get(ReasoningMetadata.REASONING),
				value -> generationMetadataBuilder.metadata(ReasoningMetadata.REASONING, value));
		ChatGenerationMetadata generationMetadata = generationMetadataBuilder.build();
		ChatResponseMetadata.Builder responseMetadataBuilder = ChatResponseMetadata.builder();
		ResponseValues.ifPresent(response.id(), responseMetadataBuilder::id);
		ResponseValues.ifPresent(response.model(), responseMetadataBuilder::model);
		ResponseValues.ifPresent(UsageMapper.map(response.usage()), responseMetadataBuilder::usage);
		responseMetadataBuilder.keyValue("openrouter.object", response.object());
		responseMetadataBuilder.keyValue("openrouter.created", response.createdAt());
		ChatResponseMetadata responseMetadata = responseMetadataBuilder.build();
		return new ChatResponse(List.of(new Generation(assistantMessage, generationMetadata)), responseMetadata);
	}

	static RuntimeException failure(String message, @Nullable String responseBody, @Nullable StreamError error,
			@Nullable String errorType) {
		OpenRouterApiException failure = OpenRouterApiExceptionFactory.create(message, responseBody, error, errorType);
		if (OpenRouterErrorClassifier.isTransient(failure.getCategory())) {
			return new OpenRouterTransientApiException(message, failure.getStatusCode(), failure.getResponseBody(),
					failure.getErrorDetails(), null, "/responses");
		}
		return new OpenRouterNonTransientApiException(message, failure.getStatusCode(), failure.getResponseBody(),
				failure.getErrorDetails(), null, "/responses");
	}

	// Accept absent status for compatibility; reject every explicit non-final status.
	static List<AssistantMessage.ToolCall> toolCalls(@Nullable String status, @Nullable String reason,
			@Nullable List<? extends @Nullable ResponsesOutputItem> output) {
		if (CollectionUtils.isEmpty(output)) {
			return List.of();
		}
		List<ResponsesOutputItem> calls = ResponseValues.<ResponsesOutputItem>items(output, "output item")
			.stream()
			.filter(item -> "function_call".equals(item.type()))
			.toList();
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
			.map(item -> new AssistantMessage.ToolCall(ResponseValues.required(item.callId(), "tool call id"),
					"function", ResponseValues.required(item.name(), "tool call name"),
					ResponseValues.required(item.arguments(), "tool call arguments")))
			.toList();
	}

	static String text(@Nullable List<? extends @Nullable ResponsesOutputItem> output) {
		if (CollectionUtils.isEmpty(output)) {
			return "";
		}
		return ResponseValues.<ResponsesOutputItem>items(output, "output item")
			.stream()
			.filter(item -> "message".equals(item.type()))
			.map(OpenRouterResponsesResponseMapper::text)
			.filter(Objects::nonNull)
			.reduce("", String::concat);
	}

	private static String text(ResponsesOutputItem item) {
		if (CollectionUtils.isEmpty(item.content())) {
			return "";
		}
		return ResponseValues.items(item.content(), "message content")
			.stream()
			.filter(content -> "output_text".equals(content.type()) || "text".equals(content.type()))
			.map(ResponsesContent::text)
			.filter(Objects::nonNull)
			.reduce("", String::concat);
	}

}
