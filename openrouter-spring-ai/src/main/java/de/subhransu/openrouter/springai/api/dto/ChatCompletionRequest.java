package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_EMPTY)
public record ChatCompletionRequest(@Nullable String model, @Nullable List<String> models, List<ChatMessage> messages,
		@Nullable Double temperature, @JsonProperty("top_p") @Nullable Double topP,
		@JsonProperty("top_k") @Nullable Integer topK,
		@JsonProperty("frequency_penalty") @Nullable Double frequencyPenalty,
		@JsonProperty("presence_penalty") @Nullable Double presencePenalty,
		@JsonProperty("repetition_penalty") @Nullable Double repetitionPenalty,
		@JsonProperty("min_p") @Nullable Double minP, @JsonProperty("top_a") @Nullable Double topA,
		@JsonProperty("max_tokens") @Nullable Integer maxTokens,
		@JsonProperty("max_completion_tokens") @Nullable Integer maxCompletionTokens, @Nullable List<String> stop,
		@Nullable Integer seed, @Nullable String user, @Nullable Boolean stream,
		@JsonProperty("response_format") @Nullable Object responseFormat, @Nullable List<Tool> tools,
		@JsonProperty("tool_choice") @Nullable Object toolChoice,
		@JsonProperty("parallel_tool_calls") @Nullable Boolean parallelToolCalls,
		@Nullable ProviderPreferences provider, @Nullable ReasoningOptions reasoning,
		@JsonProperty("service_tier") @Nullable String serviceTier, @Nullable Map<String, @Nullable Object> metadata,
		@Nullable String route, @Nullable UsageConfig usage, @Nullable List<String> modalities,
		@JsonProperty("image_config") @Nullable Map<String, @Nullable Object> imageConfig) {
}
