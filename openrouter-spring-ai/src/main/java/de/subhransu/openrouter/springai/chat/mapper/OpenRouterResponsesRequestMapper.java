package de.subhransu.openrouter.springai.chat.mapper;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.api.dto.ReasoningOptions;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesInputMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesTool;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterReasoningOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

public final class OpenRouterResponsesRequestMapper {

	private static final String MESSAGE_TYPE = "message";

	private final ObjectMapper objectMapper;

	public OpenRouterResponsesRequestMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ResponsesRequest map(List<Message> messages, OpenRouterChatOptions options, boolean stream,
			List<ToolDefinition> toolDefinitions) {
		AudioOutputMapper.validateRequest(messages, options, stream, true);
		for (Message message : messages) {
			if (message.getMetadata().containsKey(OpenRouterCacheBreakpoint.METADATA_KEY)) {
				throw new IllegalArgumentException("OPENAI_RESPONSES does not support cache_control breakpoints; "
						+ "use OPENAI_CHAT_COMPLETIONS");
			}
		}
		rejectUnsupported("stopSequences", options.getStopSequences());
		rejectUnsupported("seed", options.getSeed());
		rejectUnsupported("repetitionPenalty", options.getRepetitionPenalty());
		rejectUnsupported("minP", options.getMinP());
		rejectUnsupported("topA", options.getTopA());
		rejectUnsupported("includeUsage", options.getIncludeUsage());
		return new ResponsesRequest(options.getModel(), options.getModels(), mapInput(messages),
				mapInstructions(messages),
				options.getMaxCompletionTokens() != null ? options.getMaxCompletionTokens() : options.getMaxTokens(),
				stream, options.getTemperature(), options.getTopP(), options.getTopK(), options.getFrequencyPenalty(),
				options.getPresencePenalty(), options.getMetadata(),
				mapProvider(options.getProvider(), options.getProviderExtraBody()),
				mapReasoning(options.getReasoning()), options.getRoute(),
				options.getServiceTier() != null ? options.getServiceTier().value() : null, options.getUser(),
				options.getParallelToolCalls(), ToolChoiceMapper.map(options.getToolChoice(), true, this.objectMapper),
				mapTools(toolDefinitions, options.getToolStrict()), options.getModalities(), options.getImageConfig(),
				mapText(options), options.getExtraBody());
	}

	private static void rejectUnsupported(String name, Object value) {
		if (value != null) {
			throw new IllegalArgumentException(
					"OPENAI_RESPONSES does not support " + name + "; unset it or use OPENAI_CHAT_COMPLETIONS");
		}
	}

	private Map<String, Object> mapText(OpenRouterChatOptions options) {
		ObjectNode format = new OutputFormatMapper(this.objectMapper).map(options);
		if (format == null) {
			return null;
		}
		if (format.has("json_schema")) {
			ObjectNode schema = (ObjectNode) format.remove("json_schema");
			schema.put("type", "json_schema");
			format = schema;
		}
		return Map.of("format", format);
	}

	private List<ResponsesTool> mapTools(List<ToolDefinition> toolDefinitions, Boolean strict) {
		if (CollectionUtils.isEmpty(toolDefinitions)) {
			return null;
		}
		return toolDefinitions.stream()
			.map(toolDefinition -> new ResponsesTool("function", toolDefinition.name(), toolDefinition.description(),
					ToolSchemaValidator.read(this.objectMapper, toolDefinition, strict), strict))
			.toList();
	}

	private String mapInstructions(List<Message> messages) {
		List<String> systemMessages = messages.stream()
			.filter(message -> message.getMessageType() == MessageType.SYSTEM)
			.map(Message::getText)
			.filter(StringUtils::hasText)
			.toList();
		return systemMessages.isEmpty() ? null : String.join("\n", systemMessages);
	}

	private List<Object> mapInput(List<Message> messages) {
		List<Object> mapped = new ArrayList<>();
		for (Message message : messages) {
			if (message.getMessageType() == MessageType.SYSTEM) {
				continue;
			}
			if (message instanceof ToolResponseMessage toolResponseMessage) {
				for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
					// OpenRouter requires an id on function_call_output items in
					// conversation history;
					// it is caller-generated, so derive a stable one from the call id.
					mapped.add(new ResponsesFunctionCallOutput("fc_output_" + response.id(), "function_call_output",
							response.id(), response.responseData()));
				}
				continue;
			}
			mapped.addAll(mapMessage(message));
		}
		return mapped;
	}

	private List<Object> mapMessage(Message message) {
		if (message instanceof AssistantMessage assistant && !CollectionUtils.isEmpty(assistant.getMedia())) {
			throw new IllegalArgumentException("OPENAI_RESPONSES does not support assistant media history; "
					+ "attach the media to a UserMessage or use OPENAI_CHAT_COMPLETIONS");
		}
		if (message.getMessageType() == MessageType.ASSISTANT) {
			List<Object> items = new ArrayList<>();
			Object reasoning = message.getMetadata().get(ReasoningMetadata.RESPONSES_ITEMS);
			if (reasoning instanceof List<?> reasoningItems && !reasoningItems.isEmpty()) {
				Object output = message.getMetadata().get(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS);
				if (output instanceof List<?> outputItems && !outputItems.isEmpty()) {
					validateSnapshot(message, outputItems);
					// Reasoning must retain its position relative to messages and calls.
					// Rebuilding these separately changes the provider's continuation.
					return new ArrayList<>(outputItems);
				}
				throw new IllegalArgumentException(
						"OPENAI_RESPONSES cannot replay reasoning without an output snapshot; "
								+ "retain the original assistant message or start a new conversation without its reasoning state");
			}
			List<ResponsesContent> content = new ArrayList<>();
			if (StringUtils.hasLength(message.getText())) {
				content.add(new ResponsesContent("output_text", message.getText()));
			}
			if (message.getMetadata().get(RefusalMetadata.REFUSAL) instanceof String refusal) {
				content.add(new ResponsesContent("refusal", null, null, refusal));
			}
			if (!content.isEmpty()) {
				items.add(new ResponsesOutputItem(null, MESSAGE_TYPE, "completed", "assistant", content));
			}
			if (message instanceof AssistantMessage assistantMessage) {
				for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
					items.add(new ResponsesFunctionCall("function_call", toolCall.id(), toolCall.name(),
							toolCall.arguments()));
				}
			}
			return items;
		}
		return List.of(inputMessage(mapRole(message.getMessageType()), message));
	}

	private void validateSnapshot(Message message, List<?> outputItems) {
		// Validate the serialized shape, including raw items and deserialized metadata.
		List<ResponsesOutputItem> snapshot = Arrays
			.asList(this.objectMapper.convertValue(outputItems, ResponsesOutputItem[].class));
		if (!GeneratedImageMapper.responsesMedia(snapshot).isEmpty()) {
			throw new IllegalArgumentException(
					"OPENAI_RESPONSES does not support assistant media history in output snapshots; "
							+ "start a new conversation without its reasoning state");
		}
		List<AssistantMessage.ToolCall> calls = message instanceof AssistantMessage assistant ? assistant.getToolCalls()
				: List.of();
		if (!Objects.equals(OpenRouterResponsesResponseMapper.text(snapshot),
				message.getText() != null ? message.getText() : "")
				|| !Objects.equals(OpenRouterResponsesResponseMapper.toolCalls(null, null, snapshot), calls) || !Objects
					.equals(RefusalMetadata.responses(snapshot), message.getMetadata().get(RefusalMetadata.REFUSAL))) {
			throw new IllegalArgumentException("OPENAI_RESPONSES cannot replay a stale assistant output snapshot: "
					+ "text, tool calls, or refusal metadata changed; retain the original assistant message "
					+ "or start a new conversation without its reasoning state");
		}
	}

	private ResponsesInputMessage inputMessage(String role, Message message) {
		if (!(message instanceof UserMessage userMessage) || CollectionUtils.isEmpty(userMessage.getMedia())) {
			return new ResponsesInputMessage(MESSAGE_TYPE, role,
					List.of(new ResponsesContent("input_text", message.getText())));
		}
		List<ResponsesContent> content = new ArrayList<>();
		if (StringUtils.hasText(userMessage.getText())) {
			content.add(new ResponsesContent("input_text", userMessage.getText()));
		}
		for (Media media : userMessage.getMedia()) {
			content.add(MediaContentMapper.responses(media));
		}
		return new ResponsesInputMessage(MESSAGE_TYPE, role, content);
	}

	private String mapRole(MessageType messageType) {
		return switch (messageType) {
			case ASSISTANT -> "assistant";
			case USER, TOOL -> "user";
			case SYSTEM -> throw new IllegalArgumentException("System messages map to instructions");
		};
	}

	private ProviderPreferences mapProvider(OpenRouterProviderPreferences provider, Map<String, Object> extraBody) {
		if (provider == null) {
			return extraBody == null || extraBody.isEmpty() ? null
					: new ProviderPreferences(null, null, null, null, null, null, null, extraBody);
		}
		return new ProviderPreferences(provider.allowFallbacks(), provider.requireParameters(),
				provider.dataCollection(), provider.order(), provider.ignore(), provider.quantizations(),
				provider.sort(), extraBody);
	}

	private ReasoningOptions mapReasoning(OpenRouterReasoningOptions reasoning) {
		if (reasoning == null) {
			return null;
		}
		return new ReasoningOptions(reasoning.effort(), reasoning.maxTokens(), reasoning.exclude(),
				reasoning.enabled());
	}

}
