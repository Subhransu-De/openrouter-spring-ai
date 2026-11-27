package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.content.Media;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Builds the ordered parts of OpenRouter assistant messages, and reads back the provider
 * payloads they carry.
 * <p>
 * Chat Completions has one field per kind of content, so a reply becomes a reasoning
 * part, a text part, the tool calls and the media, in that order. The reasoning part
 * carries the {@code reasoning_details} as one {@link OpaquePayload}. Responses returns
 * an ordered list of output items, so each item becomes one part, and the item itself is
 * the payload of that part.
 *
 * @author Subhransu De
 */
final class MessageParts {

	private static final ObjectMapper JSON = new ObjectMapper();

	private MessageParts() {
	}

	/**
	 * The parts of an assistant reply in the order Chat Completions has no way to vary:
	 * reasoning, text, tool calls, media. An empty text has no part, unless nothing else
	 * does, so that {@code getText()} never returns {@code null}.
	 */
	static List<MessagePart> assistant(@Nullable ReasoningPart reasoning, @Nullable String text,
			List<AssistantMessage.ToolCall> toolCalls, List<Media> media) {
		List<MessagePart> parts = new ArrayList<>();
		if (reasoning != null) {
			parts.add(reasoning);
		}
		if (StringUtils.hasLength(text)) {
			parts.add(TextPart.of(text));
		}
		toolCalls.forEach(toolCall -> parts.add(ToolCallPart.of(toolCall)));
		media.forEach(medium -> parts.add(MediaPart.of(medium)));
		return orEmptyText(parts);
	}

	static List<MessagePart> orEmptyText(List<MessagePart> parts) {
		if (parts.isEmpty()) {
			parts.add(TextPart.of(""));
		}
		return parts;
	}

	/**
	 * The Chat Completions reasoning of a reply, or {@code null} when it has neither text
	 * nor details. Encrypted-only reasoning has details and no text.
	 */
	static @Nullable ReasoningPart chatReasoning(@Nullable String text,
			@Nullable List<? extends @Nullable JsonNode> details) {
		List<JsonNode> entries = ResponseValues.CHAT_COMPLETIONS.items(details, "reasoning detail");
		if (!StringUtils.hasLength(text) && entries.isEmpty()) {
			return null;
		}
		return new ReasoningPart(StringUtils.hasLength(text) ? text : null, null,
				entries.isEmpty() ? null : chatPayload(entries), Map.of());
	}

	static OpaquePayload chatPayload(List<? extends JsonNode> details) {
		ArrayNode array = JSON.createArrayNode();
		details.forEach(array::add);
		return new OpaquePayload(OpenRouterMetadata.CHAT_COMPLETIONS_PROVIDER,
				OpenRouterMetadata.REASONING_DETAILS_KIND, array.toString());
	}

	static boolean isChatPayload(@Nullable OpaquePayload payload) {
		return payload != null && OpenRouterMetadata.CHAT_COMPLETIONS_PROVIDER.equals(payload.provider())
				&& OpenRouterMetadata.REASONING_DETAILS_KIND.equals(payload.kind());
	}

	/**
	 * The part of one Responses output item: reasoning, message text and function calls
	 * carry the item as their payload, generated images become media, and any other item
	 * is kept verbatim as an {@link UnknownPart}. Returns {@code null} for an image item
	 * without a result.
	 */
	static @Nullable MessagePart responses(ResponsesOutputItem item) {
		String type = item.type();
		if ("reasoning".equals(type)) {
			return new ReasoningPart(reasoningText(item, "content"), reasoningText(item, "summary"),
					responsesPayload(item), Map.of());
		}
		if ("message".equals(type)) {
			return new TextPart(OpenRouterResponsesResponseMapper.text(List.of(item)), responsesPayload(item),
					Map.of());
		}
		if ("function_call".equals(type)) {
			return new ToolCallPart(OpenRouterResponsesResponseMapper.toolCall(item), responsesPayload(item), Map.of());
		}
		if ("image_generation_call".equals(type)) {
			List<Media> media = GeneratedImageMapper.responsesMedia(List.of(item));
			return media.isEmpty() ? null : MediaPart.of(media.get(0));
		}
		return new UnknownPart(OpenRouterMetadata.RESPONSES_PROVIDER, StringUtils.hasText(type) ? type : "unknown",
				json(item), null, Map.of());
	}

	static OpaquePayload responsesPayload(ResponsesOutputItem item) {
		return new OpaquePayload(OpenRouterMetadata.RESPONSES_PROVIDER, OpenRouterMetadata.RESPONSES_OUTPUT_ITEM_KIND,
				json(item));
	}

	/**
	 * The output item a part replays verbatim in Responses mode, or {@code null} when it
	 * was not produced by that mode.
	 */
	static @Nullable String responsesItem(MessagePart part) {
		if (part instanceof UnknownPart unknown) {
			return OpenRouterMetadata.RESPONSES_PROVIDER.equals(unknown.provider()) ? unknown.rawJson() : null;
		}
		OpaquePayload payload = part.payload();
		return payload != null && OpenRouterMetadata.RESPONSES_PROVIDER.equals(payload.provider())
				&& OpenRouterMetadata.RESPONSES_OUTPUT_ITEM_KIND.equals(payload.kind()) ? payload.data() : null;
	}

	// Reasoning items keep their text in content and their summary in summary.
	private static @Nullable String reasoningText(ResponsesOutputItem item, String field) {
		JsonNode raw = item.rawItem();
		JsonNode values = raw != null ? raw.get(field) : null;
		if (values == null || !values.isArray() || values.isEmpty()) {
			return null;
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode value : values) {
			if (value.hasNonNull("text")) {
				text.append(value.get("text").asString());
			}
		}
		return text.toString();
	}

	private static String json(ResponsesOutputItem item) {
		JsonNode raw = item.rawItem();
		return raw != null ? raw.toString() : JSON.writeValueAsString(item);
	}

}
