package de.subhransu.openrouter.springai.api.dto;

import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record Delta(String role, String content, String reasoning, @JsonProperty("tool_calls") List<ToolCall> toolCalls,
		List<ContentPart> images, @JsonProperty("reasoning_details") List<JsonNode> reasoningDetails, String refusal) {

	public Delta(String role, String content, String reasoning, List<ToolCall> toolCalls, List<ContentPart> images,
			List<JsonNode> reasoningDetails) {
		this(role, content, reasoning, toolCalls, images, reasoningDetails, null);
	}

	public Delta(String role, String content, String reasoning, List<ToolCall> toolCalls, List<ContentPart> images) {
		this(role, content, reasoning, toolCalls, images, null);
	}

	public Delta(String role, String content, String reasoning, List<ToolCall> toolCalls) {
		this(role, content, reasoning, toolCalls, null);
	}
}
