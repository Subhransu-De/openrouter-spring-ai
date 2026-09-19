package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.RequestExtensions;
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
		@JsonProperty("image_config") @Nullable Map<String, @Nullable Object> imageConfig, @Nullable AudioConfig audio,
		@JsonAnyGetter @Nullable Map<String, @Nullable Object> extraBody) {
	public ChatCompletionRequest {
		extraBody = RequestExtensions.chatRequest(extraBody);
	}

	public ChatCompletionRequest(@Nullable String model, @Nullable List<String> models, List<ChatMessage> messages,
			@Nullable Double temperature, @Nullable Double topP, @Nullable Integer topK,
			@Nullable Double frequencyPenalty, @Nullable Double presencePenalty, @Nullable Double repetitionPenalty,
			@Nullable Double minP, @Nullable Double topA, @Nullable Integer maxTokens,
			@Nullable Integer maxCompletionTokens, @Nullable List<String> stop, @Nullable Integer seed,
			@Nullable String user, @Nullable Boolean stream, @Nullable Object responseFormat,
			@Nullable List<Tool> tools, @Nullable Object toolChoice, @Nullable Boolean parallelToolCalls,
			@Nullable ProviderPreferences provider, @Nullable ReasoningOptions reasoning, @Nullable String serviceTier,
			@Nullable Map<String, @Nullable Object> metadata, @Nullable String route, @Nullable UsageConfig usage,
			@Nullable List<String> modalities, @Nullable Map<String, @Nullable Object> imageConfig,
			@Nullable Map<String, @Nullable Object> extraBody) {
		this(model, models, messages, temperature, topP, topK, frequencyPenalty, presencePenalty, repetitionPenalty,
				minP, topA, maxTokens, maxCompletionTokens, stop, seed, user, stream, responseFormat, tools, toolChoice,
				parallelToolCalls, provider, reasoning, serviceTier, metadata, route, usage, modalities, imageConfig,
				null, extraBody);
	}

	public ChatCompletionRequest(@Nullable String model, @Nullable List<String> models, List<ChatMessage> messages,
			@Nullable Double temperature, @Nullable Double topP, @Nullable Integer topK,
			@Nullable Double frequencyPenalty, @Nullable Double presencePenalty, @Nullable Double repetitionPenalty,
			@Nullable Double minP, @Nullable Double topA, @Nullable Integer maxTokens,
			@Nullable Integer maxCompletionTokens, @Nullable List<String> stop, @Nullable Integer seed,
			@Nullable String user, @Nullable Boolean stream, @Nullable Object responseFormat,
			@Nullable List<Tool> tools, @Nullable Object toolChoice, @Nullable Boolean parallelToolCalls,
			@Nullable ProviderPreferences provider, @Nullable ReasoningOptions reasoning, @Nullable String serviceTier,
			@Nullable Map<String, @Nullable Object> metadata, @Nullable String route, @Nullable UsageConfig usage,
			@Nullable List<String> modalities, @Nullable Map<String, @Nullable Object> imageConfig) {
		this(model, models, messages, temperature, topP, topK, frequencyPenalty, presencePenalty, repetitionPenalty,
				minP, topA, maxTokens, maxCompletionTokens, stop, seed, user, stream, responseFormat, tools, toolChoice,
				parallelToolCalls, provider, reasoning, serviceTier, metadata, route, usage, modalities, imageConfig,
				null);
	}

}
