package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.AudioConfig;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.Function;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.api.dto.ReasoningOptions;
import de.subhransu.openrouter.springai.api.dto.Tool;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.UsageConfig;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterReasoningOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public final class OpenRouterChatRequestMapper {

	private final ObjectMapper objectMapper;

	public OpenRouterChatRequestMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ChatCompletionRequest map(List<Message> messages, OpenRouterChatOptions options, boolean stream,
			List<ToolDefinition> toolDefinitions) {
		AudioOutputMapper.validateRequest(messages, options, stream, false);
		List<Tool> tools = mapTools(toolDefinitions, options.getToolStrict());
		CacheBreakpointMapper.validate(messages);
		var serviceTier = options.getServiceTier();
		var audio = options.getAudio();
		return new ChatCompletionRequest(options.getModel(), options.getModels(), mapMessages(messages),
				options.getTemperature(), options.getTopP(), options.getTopK(), options.getFrequencyPenalty(),
				options.getPresencePenalty(), options.getRepetitionPenalty(), options.getMinP(), options.getTopA(),
				options.getMaxTokens(), options.getMaxCompletionTokens(), options.getStopSequences(), options.getSeed(),
				options.getUser(), stream, new OutputFormatMapper(this.objectMapper).map(options), tools,
				ToolChoiceMapper.map(options.getToolChoice(), false, this.objectMapper), options.getParallelToolCalls(),
				mapProvider(options.getProvider(), options.getProviderExtraBody()),
				mapReasoning(options.getReasoning()), serviceTier != null ? serviceTier.value() : null,
				options.getMetadata(), options.getRoute(),
				options.getIncludeUsage() != null ? new UsageConfig(options.getIncludeUsage()) : null,
				options.getModalities(), options.getImageConfig(),
				audio != null ? new AudioConfig(audio.voice(), audio.format()) : null, options.getExtraBody());
	}

	private List<ChatMessage> mapMessages(List<Message> messages) {
		List<ChatMessage> mapped = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage toolResponseMessage) {
				for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
					mapped.add(new ChatMessage("tool", response.responseData(), response.name(),
							StringUtils.hasLength(response.id()) ? response.id() : null, null));
				}
				continue;
			}
			mapped.add(new ChatMessage(mapRole(message.getMessageType()), mapContent(message), null, null,
					mapAssistantToolCalls(message), mapAssistantImages(message),
					message instanceof AssistantMessage
							? (String) message.getMetadata().get(ReasoningMetadata.REASONING) : null,
					message instanceof AssistantMessage ? ReasoningMetadata.details(message.getMetadata()) : null,
					message instanceof AssistantMessage ? (String) message.getMetadata().get(RefusalMetadata.REFUSAL)
							: null));
		}
		return mapped;
	}

	// Unmarked text-only messages keep the plain-string content shape. Cache
	// boundaries and media require the content parts array.
	private @Nullable Object mapContent(Message message) {
		if (!CacheBreakpointMapper.breakpoints(message).isEmpty()) {
			List<ContentPart> parts = CacheBreakpointMapper.textParts(message);
			if (message instanceof UserMessage userMessage) {
				for (Media media : userMessage.getMedia()) {
					parts.add(MediaContentMapper.chat(media));
				}
			}
			return parts;
		}
		if (!(message instanceof UserMessage userMessage) || CollectionUtils.isEmpty(userMessage.getMedia())) {
			return message.getText();
		}
		List<ContentPart> parts = new ArrayList<>();
		if (StringUtils.hasText(userMessage.getText())) {
			parts.add(ContentPart.text(userMessage.getText()));
		}
		for (Media media : userMessage.getMedia()) {
			parts.add(MediaContentMapper.chat(media));
		}
		return parts;
	}

	private @Nullable List<@Nullable ToolCall> mapAssistantToolCalls(Message message) {
		if (!(message instanceof AssistantMessage assistantMessage)
				|| CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
			return null;
		}
		return assistantMessage.getToolCalls()
			.stream()
			.<@Nullable ToolCall>map(
					toolCall -> new ToolCall(StringUtils.hasLength(toolCall.id()) ? toolCall.id() : null,
							toolCall.type(), new FunctionCall(toolCall.name(), toolCall.arguments())))
			.toList();
	}

	private @Nullable List<@Nullable ContentPart> mapAssistantImages(Message message) {
		if (!(message instanceof AssistantMessage assistant) || CollectionUtils.isEmpty(assistant.getMedia())) {
			return null;
		}
		return assistant.getMedia()
			.stream()
			.<@Nullable ContentPart>map(media -> ContentPart.image(MediaUrlMapper.imageUrl(media)))
			.toList();
	}

	private String mapRole(MessageType messageType) {
		return switch (messageType) {
			case SYSTEM -> "system";
			case USER -> "user";
			case ASSISTANT -> "assistant";
			case TOOL -> "tool";
		};
	}

	private @Nullable List<Tool> mapTools(List<ToolDefinition> toolDefinitions, @Nullable Boolean strict) {
		if (CollectionUtils.isEmpty(toolDefinitions)) {
			return null;
		}
		return toolDefinitions.stream()
			.map(toolDefinition -> new Tool("function",
					new Function(toolDefinition.name(), toolDefinition.description(),
							ToolSchemaValidator.read(this.objectMapper, toolDefinition, strict), strict)))
			.toList();
	}

	private @Nullable ProviderPreferences mapProvider(@Nullable OpenRouterProviderPreferences provider,
			@Nullable Map<String, @Nullable Object> extraBody) {
		if (provider == null) {
			return extraBody == null || extraBody.isEmpty() ? null
					: new ProviderPreferences(null, null, null, null, null, null, null, extraBody);
		}
		return new ProviderPreferences(provider.allowFallbacks(), provider.requireParameters(),
				provider.dataCollection(), provider.order(), provider.ignore(), provider.quantizations(),
				provider.sort(), extraBody);
	}

	private @Nullable ReasoningOptions mapReasoning(@Nullable OpenRouterReasoningOptions reasoning) {
		if (reasoning == null) {
			return null;
		}
		return new ReasoningOptions(reasoning.effort(), reasoning.maxTokens(), reasoning.exclude(),
				reasoning.enabled());
	}

}
