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
public record Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
		@JsonProperty("tool_calls") @Nullable List<@Nullable ToolCall> toolCalls,
		@Nullable List<@Nullable ContentPart> images,
		@JsonProperty("reasoning_details") @Nullable List<@Nullable JsonNode> reasoningDetails,
		@Nullable String refusal, @Nullable AudioOutput audio,
		@JsonAnyGetter @JsonAnySetter @Nullable Map<String, @Nullable Object> extensions) {
	public Map<String, @Nullable Object> extensions() {
		return Objects.requireNonNull(this.extensions);
	}

	public Delta {
		extensions = extensions == null ? Map.of() : OptionSnapshots.map(extensions);
	}

	public Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
			@Nullable List<@Nullable ToolCall> toolCalls, @Nullable List<@Nullable ContentPart> images,
			@Nullable List<@Nullable JsonNode> reasoningDetails, @Nullable String refusal,
			@Nullable Map<String, @Nullable Object> extensions) {
		this(role, content, reasoning, toolCalls, images, reasoningDetails, refusal, null, extensions);
	}

	public Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
			@Nullable List<@Nullable ToolCall> toolCalls, @Nullable List<@Nullable ContentPart> images,
			@Nullable List<@Nullable JsonNode> reasoningDetails, @Nullable String refusal) {
		this(role, content, reasoning, toolCalls, images, reasoningDetails, refusal, null);
	}

	public Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
			@Nullable List<@Nullable ToolCall> toolCalls, @Nullable List<@Nullable ContentPart> images,
			@Nullable List<@Nullable JsonNode> reasoningDetails) {
		this(role, content, reasoning, toolCalls, images, reasoningDetails, null);
	}

	public Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
			@Nullable List<@Nullable ToolCall> toolCalls, @Nullable List<@Nullable ContentPart> images) {
		this(role, content, reasoning, toolCalls, images, null);
	}

	public Delta(@Nullable String role, @Nullable String content, @Nullable String reasoning,
			@Nullable List<@Nullable ToolCall> toolCalls) {
		this(role, content, reasoning, toolCalls, null);
	}
}
