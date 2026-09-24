package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.util.CollectionUtils;

public final class OpenRouterChatResponseMapper {

	private final OpenRouterChoiceErrorExceptionFactory choiceErrorExceptionFactory = new OpenRouterChoiceErrorExceptionFactory();

	public ChatResponse map(ChatCompletionResponse response) {
		if (response == null) {
			throw new OpenRouterProtocolException("Null OpenRouter chat completion response");
		}
		var error = response.error();
		if (error != null) {
			throw OpenRouterApiExceptionFactory.create("OpenRouter chat completion failed", error.toString(),
					response.error(), null);
		}
		var choices = response.choices();
		if (CollectionUtils.isEmpty(choices) || choices.stream().anyMatch(Objects::isNull)) {
			throw new OpenRouterProtocolException("OpenRouter chat completion requires non-null choices");
		}
		throwIfChoiceFailed(response);
		List<Generation> generations = ResponseValues.items(response.choices(), "choice")
			.stream()
			.map(choice -> mapGeneration(choice, response.model()))
			.toList();
		return new ChatResponse(generations, mapMetadata(response));
	}

	private void throwIfChoiceFailed(ChatCompletionResponse response) {
		var choices = response.choices();
		if (CollectionUtils.isEmpty(choices)) {
			return;
		}
		for (Choice choice : choices) {
			if (choice != null && OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
				throw this.choiceErrorExceptionFactory.create(response, choice);
			}
		}
	}

	private Generation mapGeneration(Choice choice, @Nullable String model) {
		var message = choice.message();
		if (message == null) {
			throw new OpenRouterProtocolException("OpenRouter chat completion choice requires a message");
		}
		if (!CollectionUtils.isEmpty(message.toolCalls())
				&& !FinishReasonMapper.isToolCallCompletion(choice.finishReason())) {
			throw new OpenRouterTruncatedResponseException(
					"Tool call choice ended without a tool-call completion reason");
		}

		AssistantContentMapper.MappedContent content = AssistantContentMapper.map(message.content());
		List<Media> media = new ArrayList<>(content.media());
		media.addAll(GeneratedImageMapper.media(message.images()));
		Map<String, Object> properties = ReasoningMetadata.chat(message.reasoning(), message.reasoningDetails());
		RefusalMetadata.put(properties, message.refusal());
		ExtensionMetadata.put(properties, message.extensions(), choice.extensions(), message.toolCalls());
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content(content.text())
			.properties(properties)
			.toolCalls(mapToolCalls(message.toolCalls()))
			.media(media)
			.build();

		ChatGenerationMetadata.Builder metadataBuilder = ChatGenerationMetadata.builder();
		metadataBuilder.finishReason(FinishReasonMapper.map(choice.finishReason()));
		ResponseValues.ifPresent(model, value -> metadataBuilder.metadata("openrouter.model", value));
		ResponseValues.ifPresent(message.reasoning(), value -> metadataBuilder.metadata("openrouter.reasoning", value));
		ResponseValues.ifPresent(choice.nativeFinishReason(),
				value -> metadataBuilder.metadata("openrouter.native_finish_reason", value));
		ResponseValues.ifPresent(properties.get(RefusalMetadata.REFUSAL),
				value -> metadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ChatGenerationMetadata metadata = metadataBuilder.build();
		return new Generation(assistantMessage, metadata);
	}

	private List<AssistantMessage.ToolCall> mapToolCalls(@Nullable List<? extends @Nullable ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}
		return ResponseValues.items(toolCalls, "tool call")
			.stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id() != null ? toolCall.id() : "",
					ResponseValues.required(toolCall.type(), "tool call type"),
					ResponseValues.required(ResponseValues.required(toolCall.function(), "tool call function").name(),
							"tool call name"),
					ResponseValues.required(
							ResponseValues.required(toolCall.function(), "tool call function").arguments(),
							"tool call arguments")))
			.toList();
	}

	private ChatResponseMetadata mapMetadata(ChatCompletionResponse response) {
		ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder();
		ResponseValues.ifPresent(response.id(), builder::id);
		ResponseValues.ifPresent(response.model(), builder::model);
		ResponseValues.ifPresent(UsageMapper.map(response.usage()), builder::usage);
		builder.keyValue(ExtensionMetadata.RESPONSE, response.extensions());
		builder.keyValue("openrouter.provider", response.provider());
		builder.keyValue("openrouter.object", response.object());
		builder.keyValue("openrouter.created", response.created());
		return builder.build();
	}

}
