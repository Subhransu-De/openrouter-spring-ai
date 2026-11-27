package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.AudioConfig;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.FunctionDefinition;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.Prediction;
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
		var prediction = options.getPrediction();
		return ChatCompletionRequest.builder()
			.model(options.getModel())
			.models(options.getModels())
			.messages(mapMessages(messages))
			.temperature(options.getTemperature())
			.topP(options.getTopP())
			.topK(options.getTopK())
			.frequencyPenalty(options.getFrequencyPenalty())
			.presencePenalty(options.getPresencePenalty())
			.repetitionPenalty(options.getRepetitionPenalty())
			.minP(options.getMinP())
			.topA(options.getTopA())
			.maxTokens(options.getMaxTokens())
			.maxCompletionTokens(options.getMaxCompletionTokens())
			.stop(options.getStopSequences())
			.seed(options.getSeed())
			.user(options.getUser())
			.sessionId(options.getSessionId())
			.trace(TraceMapper.map(options.getTrace()))
			.stream(stream)
			.responseFormat(new OutputFormatMapper(this.objectMapper).map(options))
			.tools(tools)
			.toolChoice(ToolChoiceMapper.map(options.getToolChoice(), false, this.objectMapper, toolDefinitions))
			.parallelToolCalls(options.getParallelToolCalls())
			.provider(mapProvider(options.getProvider(), options.getProviderExtraBody()))
			.reasoning(mapReasoning(options.getReasoning()))
			.serviceTier(serviceTier != null ? serviceTier.value() : null)
			.metadata(options.getMetadata())
			.route(options.getRoute())
			.usage(options.getIncludeUsage() != null ? new UsageConfig(options.getIncludeUsage()) : null)
			.modalities(options.getModalities())
			.imageConfig(options.getImageConfig())
			.audio(audio != null ? new AudioConfig(audio.voice(), audio.format()) : null)
			.logprobs(options.getLogprobs())
			.topLogprobs(options.getTopLogprobs())
			.logitBias(options.getLogitBias())
			.verbosity(options.getVerbosity())
			.promptCacheKey(options.getPromptCacheKey())
			.promptCacheOptions(PromptCacheMapper.map(options.getPromptCacheOptions()))
			.prediction(prediction != null ? Prediction.content(prediction) : null)
			.extraBody(options.getExtraBody())
			.build();
	}

	private List<ChatMessage> mapMessages(List<Message> messages) {
		List<ChatMessage> mapped = new ArrayList<>(messages.size());
		for (Message message : messages) {
			if (message instanceof ToolResponseMessage toolResponseMessage) {
				for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
					mapped.add(new ChatMessage("tool", response.responseData(), response.name(),
							StringUtils.hasLength(response.id()) ? response.id() : null, null));
				}
				continue;
			}
			Reasoning reasoning = message instanceof AssistantMessage assistant ? reasoning(assistant)
					: new Reasoning(null, null);
			mapped.add(new ChatMessage(mapRole(message.getMessageType()), mapContent(message), null, null,
					mapAssistantToolCalls(message), mapAssistantImages(message), reasoning.text(), reasoning.details(),
					message instanceof AssistantMessage ? (String) message.getMetadata().get(RefusalMetadata.REFUSAL)
							: null));
		}
		return mapped;
	}

	/**
	 * The reasoning to send back with an assistant message: the text of its reasoning
	 * parts and the {@code reasoning_details} of their Chat Completions payloads. When
	 * another provider signed any of the parts, none is sent, as in Spring AI's
	 * {@code OpenAiChatModel}: signed reasoning is only valid with its payload, which
	 * this API cannot carry. A message without reasoning parts, such as one stored by
	 * 0.3.0, falls back to the legacy metadata keys.
	 */
	private Reasoning reasoning(AssistantMessage message) {
		List<ReasoningPart> parts = message.getReasoning()
			.stream()
			.filter(part -> !StreamingParts.isPartial(part))
			.toList();
		if (parts.isEmpty()) {
			Object text = message.getMetadata().get(ReasoningMetadata.REASONING);
			Object details = ReasoningMetadata.details(message.getMetadata());
			return new Reasoning(text instanceof String value ? value : null,
					details != null ? jsonList(details) : null);
		}
		if (parts.stream().anyMatch(part -> part.payload() != null && !MessageParts.isChatPayload(part.payload()))) {
			return new Reasoning(null, null);
		}
		StringBuilder text = new StringBuilder();
		List<@Nullable JsonNode> details = new ArrayList<>();
		for (ReasoningPart part : parts) {
			if (part.text() != null) {
				text.append(part.text());
			}
			OpaquePayload payload = part.payload();
			if (payload != null) {
				details.addAll(jsonList(this.objectMapper.readTree(payload.data())));
			}
		}
		return new Reasoning(text.isEmpty() ? null : text.toString(), details.isEmpty() ? null : details);
	}

	// Stored messages hold Jackson nodes, or plain maps and lists after a JSON round
	// trip.
	private List<@Nullable JsonNode> jsonList(Object value) {
		JsonNode node = this.objectMapper.valueToTree(value);
		List<@Nullable JsonNode> items = new ArrayList<>();
		if (node.isArray()) {
			node.forEach(items::add);
		}
		return items;
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
		// Text and media keep their order in the message.
		List<ContentPart> parts = new ArrayList<>();
		for (MessagePart part : userMessage.getParts()) {
			if (part instanceof TextPart text && StringUtils.hasText(text.text())) {
				parts.add(ContentPart.text(text.text()));
			}
			else if (part instanceof MediaPart media) {
				parts.add(MediaContentMapper.chat(media.media()));
			}
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
					new FunctionDefinition(toolDefinition.name(), toolDefinition.description(),
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

	private record Reasoning(@Nullable String text, @Nullable List<@Nullable JsonNode> details) {
	}

}
