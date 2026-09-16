package de.subhransu.openrouter.springai.chat.mapper;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
		List<Tool> tools = mapTools(toolDefinitions);
		return new ChatCompletionRequest(options.getModel(), options.getModels(), mapMessages(messages),
				options.getTemperature(), options.getTopP(), options.getTopK(), options.getFrequencyPenalty(),
				options.getPresencePenalty(), options.getRepetitionPenalty(), options.getMinP(), options.getTopA(),
				options.getMaxTokens(), options.getMaxCompletionTokens(), options.getStopSequences(), options.getSeed(),
				options.getUser(), stream, new OutputFormatMapper(this.objectMapper).map(options), tools,
				ToolChoiceMapper.map(options.getToolChoice(), false, this.objectMapper), options.getParallelToolCalls(),
				mapProvider(options.getProvider()), mapReasoning(options.getReasoning()),
				options.getServiceTier() != null ? options.getServiceTier().value() : null, options.getMetadata(),
				options.getRoute(),
				options.getIncludeUsage() != null ? new UsageConfig(options.getIncludeUsage()) : null,
				options.getModalities(), options.getImageConfig());
	}

	private List<ChatMessage> mapMessages(List<Message> messages) {
		List<ChatMessage> mapped = new ArrayList<>();
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage toolResponseMessage) {
				for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
					mapped.add(new ChatMessage("tool", response.responseData(), response.name(), response.id(), null));
				}
				continue;
			}
			mapped.add(new ChatMessage(mapRole(message.getMessageType()), mapContent(message), null, null,
					mapAssistantToolCalls(message), null,
					message instanceof AssistantMessage
							? (String) message.getMetadata().get(ReasoningMetadata.REASONING) : null,
					message instanceof AssistantMessage ? ReasoningMetadata.details(message.getMetadata()) : null,
					message instanceof AssistantMessage ? (String) message.getMetadata().get(RefusalMetadata.REFUSAL)
							: null));
		}
		return mapped;
	}

	// Text-only messages keep the plain-string content shape; media promotes the
	// content to the multimodal parts array per the OpenRouter image-inputs spec.
	private Object mapContent(Message message) {
		if (!(message instanceof UserMessage userMessage) || CollectionUtils.isEmpty(userMessage.getMedia())) {
			return message.getText();
		}
		List<ContentPart> parts = new ArrayList<>();
		if (StringUtils.hasText(userMessage.getText())) {
			parts.add(ContentPart.text(userMessage.getText()));
		}
		for (Media media : userMessage.getMedia()) {
			parts.add(ContentPart.image(MediaUrlMapper.imageUrl(media)));
		}
		return parts;
	}

	private List<ToolCall> mapAssistantToolCalls(Message message) {
		if (!(message instanceof AssistantMessage assistantMessage)
				|| CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
			return null;
		}
		return assistantMessage.getToolCalls()
			.stream()
			.map(toolCall -> new ToolCall(toolCall.id(), toolCall.type(),
					new FunctionCall(toolCall.name(), toolCall.arguments())))
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

	private List<Tool> mapTools(List<ToolDefinition> toolDefinitions) {
		if (CollectionUtils.isEmpty(toolDefinitions)) {
			return null;
		}
		return toolDefinitions.stream()
			.map(toolDefinition -> new Tool("function",
					new Function(toolDefinition.name(), toolDefinition.description(),
							readTree(toolDefinition.inputSchema()))))
			.toList();
	}

	private JsonNode readTree(String json) {
		try {
			return this.objectMapper.readTree(json);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("Invalid JSON schema", ex);
		}
	}

	private ProviderPreferences mapProvider(OpenRouterProviderPreferences provider) {
		if (provider == null) {
			return null;
		}
		return new ProviderPreferences(provider.allowFallbacks(), provider.requireParameters(),
				provider.dataCollection(), provider.order(), provider.ignore(), provider.quantizations(),
				provider.sort());
	}

	private ReasoningOptions mapReasoning(OpenRouterReasoningOptions reasoning) {
		if (reasoning == null) {
			return null;
		}
		return new ReasoningOptions(reasoning.effort(), reasoning.maxTokens(), reasoning.exclude(),
				reasoning.enabled());
	}

}
