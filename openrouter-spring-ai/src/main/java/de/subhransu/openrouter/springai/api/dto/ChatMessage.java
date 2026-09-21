package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChatMessage(@Nullable String role, @Nullable Object content, @Nullable String name,
		@JsonProperty("tool_call_id") @Nullable String toolCallId,
		@JsonProperty("tool_calls") @Nullable List<@Nullable ToolCall> toolCalls,
		@Nullable List<@Nullable ContentPart> images, @Nullable String reasoning,
		@JsonProperty("reasoning_details") @Nullable List<@Nullable JsonNode> reasoningDetails,
		@Nullable String refusal, @JsonAnyGetter @JsonAnySetter @Nullable Map<String, @Nullable Object> extensions) {
	public Map<String, @Nullable Object> extensions() {
		return Objects.requireNonNull(this.extensions);
	}

	public ChatMessage {
		extensions = extensions == null ? Map.of() : OptionSnapshots.map(extensions);
	}

	public ChatMessage(@Nullable String role, @Nullable Object content, @Nullable String name,
			@Nullable String toolCallId, @Nullable List<@Nullable ToolCall> toolCalls,
			@Nullable List<@Nullable ContentPart> images, @Nullable String reasoning,
			@Nullable List<@Nullable JsonNode> reasoningDetails, @Nullable String refusal) {
		this(role, content, name, toolCallId, toolCalls, images, reasoning, reasoningDetails, refusal, null);
	}

	public ChatMessage(@Nullable String role, @Nullable Object content, @Nullable String name,
			@Nullable String toolCallId, @Nullable List<@Nullable ToolCall> toolCalls,
			@Nullable List<@Nullable ContentPart> images, @Nullable String reasoning,
			@Nullable List<@Nullable JsonNode> reasoningDetails) {
		this(role, content, name, toolCallId, toolCalls, images, reasoning, reasoningDetails, null);
	}

	public ChatMessage(@Nullable String role, @Nullable Object content, @Nullable String name,
			@Nullable String toolCallId, @Nullable List<@Nullable ToolCall> toolCalls,
			@Nullable List<@Nullable ContentPart> images) {
		this(role, content, name, toolCallId, toolCalls, images, null, null);
	}

	public ChatMessage(@Nullable String role, @Nullable Object content, @Nullable String name,
			@Nullable String toolCallId, @Nullable List<@Nullable ToolCall> toolCalls) {
		this(role, content, name, toolCallId, toolCalls, null);
	}
}
