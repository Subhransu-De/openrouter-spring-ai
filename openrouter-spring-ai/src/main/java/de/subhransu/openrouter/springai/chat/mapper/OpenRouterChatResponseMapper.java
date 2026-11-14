package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterInBandErrorExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
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

	private static final String ENDPOINT = "/chat/completions";

	private final OpenRouterDiagnosticSanitizer sanitizer;

	private final OpenRouterChoiceErrorExceptionFactory choiceErrorExceptionFactory;

	public OpenRouterChatResponseMapper() {
		this(OpenRouterDiagnosticSanitizer.keyless());
	}

	public OpenRouterChatResponseMapper(OpenRouterDiagnosticSanitizer sanitizer) {
		this.sanitizer = sanitizer;
		this.choiceErrorExceptionFactory = new OpenRouterChoiceErrorExceptionFactory(sanitizer);
	}

	public ChatResponse map(ChatCompletionResponse response) {
		if (response == null) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.EMPTY_BODY, ENDPOINT,
					"Null OpenRouter chat completion response");
		}
		var error = response.error();
		if (error != null) {
			throw OpenRouterInBandErrorExceptionFactory.createRetryable("OpenRouter chat completion failed",
					error.toString(), error, null, ENDPOINT, this.sanitizer);
		}
		var choices = response.choices();
		if (CollectionUtils.isEmpty(choices) || choices.stream().anyMatch(Objects::isNull)) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.MISSING_FIELD, ENDPOINT,
					"OpenRouter chat completion requires non-null choices");
		}
		throwIfChoiceFailed(response);
		List<Generation> generations = ResponseValues.CHAT_COMPLETIONS.items(response.choices(), "choice")
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
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.MISSING_FIELD, ENDPOINT,
					"OpenRouter chat completion choice requires a message");
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
		ResponseValues.ifPresent(model, value -> metadataBuilder.metadata(OpenRouterMetadata.MODEL, value));
		ResponseValues.ifPresent(message.reasoning(),
				value -> metadataBuilder.metadata(OpenRouterMetadata.REASONING, value));
		ResponseValues.ifPresent(choice.nativeFinishReason(),
				value -> metadataBuilder.metadata(OpenRouterMetadata.NATIVE_FINISH_REASON, value));
		ResponseValues.ifPresent(properties.get(RefusalMetadata.REFUSAL),
				value -> metadataBuilder.metadata(RefusalMetadata.REFUSAL, value));
		ChatGenerationMetadata metadata = metadataBuilder.build();
		return new Generation(assistantMessage, metadata);
	}

	private List<AssistantMessage.ToolCall> mapToolCalls(@Nullable List<? extends @Nullable ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}
		return ResponseValues.CHAT_COMPLETIONS.items(toolCalls, "tool call")
			.stream()
			.map(toolCall -> new AssistantMessage.ToolCall(toolCall.id() != null ? toolCall.id() : "",
					ResponseValues.CHAT_COMPLETIONS.required(toolCall.type(), "tool call type"),
					ResponseValues.CHAT_COMPLETIONS.requiredText(
							ResponseValues.CHAT_COMPLETIONS.required(toolCall.function(), "tool call function").name(),
							"tool call name"),
					ResponseValues.CHAT_COMPLETIONS
						.required(ResponseValues.CHAT_COMPLETIONS.required(toolCall.function(), "tool call function")
							.arguments(), "tool call arguments")))
			.toList();
	}

	private ChatResponseMetadata mapMetadata(ChatCompletionResponse response) {
		ChatResponseMetadata.Builder builder = ChatResponseMetadata.builder();
		ResponseValues.ifPresent(response.id(), builder::id);
		ResponseValues.ifPresent(response.model(), builder::model);
		ResponseValues.ifPresent(UsageMapper.map(response.usage()), builder::usage);
		builder.keyValue(ExtensionMetadata.RESPONSE, response.extensions());
		builder.keyValue(OpenRouterMetadata.PROVIDER, response.provider());
		builder.keyValue(OpenRouterMetadata.OBJECT, response.object());
		builder.keyValue(OpenRouterMetadata.CREATED, response.created());
		return builder.build();
	}

}
