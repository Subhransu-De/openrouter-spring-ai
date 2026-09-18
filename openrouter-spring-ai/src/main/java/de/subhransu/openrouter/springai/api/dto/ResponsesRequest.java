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
public record ResponsesRequest(@Nullable String model, @Nullable List<String> models, Object input,
		@Nullable String instructions, @JsonProperty("max_output_tokens") @Nullable Integer maxOutputTokens,
		@Nullable Boolean stream, @Nullable Double temperature, @JsonProperty("top_p") @Nullable Double topP,
		@JsonProperty("top_k") @Nullable Integer topK,
		@JsonProperty("frequency_penalty") @Nullable Double frequencyPenalty,
		@JsonProperty("presence_penalty") @Nullable Double presencePenalty,
		@Nullable Map<String, @Nullable Object> metadata, @Nullable ProviderPreferences provider,
		@Nullable ReasoningOptions reasoning, @Nullable String route,
		@JsonProperty("service_tier") @Nullable String serviceTier, @Nullable String user,
		@JsonProperty("parallel_tool_calls") @Nullable Boolean parallelToolCalls,
		@JsonProperty("tool_choice") @Nullable Object toolChoice, @Nullable List<ResponsesTool> tools,
		@Nullable List<String> modalities,
		@JsonProperty("image_config") @Nullable Map<String, @Nullable Object> imageConfig,
		@Nullable Map<String, @Nullable Object> text) {
	public ResponsesRequest(@Nullable String model, @Nullable List<String> models, Object input,
			@Nullable String instructions, @Nullable Integer maxOutputTokens, @Nullable Boolean stream,
			@Nullable Double temperature, @Nullable Double topP, @Nullable Integer topK,
			@Nullable Double frequencyPenalty, @Nullable Double presencePenalty,
			@Nullable Map<String, @Nullable Object> metadata, @Nullable ProviderPreferences provider,
			@Nullable ReasoningOptions reasoning, @Nullable String route, @Nullable String serviceTier,
			@Nullable String user, @Nullable Boolean parallelToolCalls, @Nullable Object toolChoice,
			@Nullable List<ResponsesTool> tools, @Nullable List<String> modalities,
			@Nullable Map<String, @Nullable Object> imageConfig) {
		this(model, models, input, instructions, maxOutputTokens, stream, temperature, topP, topK, frequencyPenalty,
				presencePenalty, metadata, provider, reasoning, route, serviceTier, user, parallelToolCalls, toolChoice,
				tools, modalities, imageConfig, null);
	}
}
