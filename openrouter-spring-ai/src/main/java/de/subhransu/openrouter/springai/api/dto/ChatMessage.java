package de.subhransu.openrouter.springai.api.dto;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChatMessage(String role, Object content, String name, @JsonProperty("tool_call_id") String toolCallId,
		@JsonProperty("tool_calls") List<ToolCall> toolCalls, List<ContentPart> images, String reasoning,
		@JsonProperty("reasoning_details") List<JsonNode> reasoningDetails, String refusal) {

	public ChatMessage(String role, Object content, String name, String toolCallId, List<ToolCall> toolCalls,
			List<ContentPart> images, String reasoning, List<JsonNode> reasoningDetails) {
		this(role, content, name, toolCallId, toolCalls, images, reasoning, reasoningDetails, null);
	}

	public ChatMessage(String role, Object content, String name, String toolCallId, List<ToolCall> toolCalls,
			List<ContentPart> images) {
		this(role, content, name, toolCallId, toolCalls, images, null, null);
	}

	public ChatMessage(String role, Object content, String name, String toolCallId, List<ToolCall> toolCalls) {
		this(role, content, name, toolCallId, toolCalls, null);
	}
}
