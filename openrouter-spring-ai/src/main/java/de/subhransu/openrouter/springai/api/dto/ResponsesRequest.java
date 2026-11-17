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
import java.util.Objects;

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
		@Nullable Map<String, @Nullable Object> text, @JsonProperty("session_id") @Nullable String sessionId,
		@Nullable Map<String, @Nullable Object> trace, @JsonProperty("top_logprobs") @Nullable Integer topLogprobs,
		@JsonProperty("prompt_cache_key") @Nullable String promptCacheKey,
		@JsonProperty("prompt_cache_options") @Nullable PromptCacheOptions promptCacheOptions,
		@JsonAnyGetter @Nullable Map<String, @Nullable Object> extraBody) {

	public ResponsesRequest {
		extraBody = RequestExtensions.responsesRequest(extraBody,
				RequestExtensions.typed("top_logprobs", topLogprobs, "prompt_cache_key", promptCacheKey));
	}

	/**
	 * Returns a builder for this request. Prefer it over the canonical constructor, whose
	 * parameter list grows whenever OpenRouter adds a request field.
	 */
	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String model;

		private @Nullable List<String> models;

		private @Nullable Object input;

		private @Nullable String instructions;

		private @Nullable Integer maxOutputTokens;

		private @Nullable Boolean stream;

		private @Nullable Double temperature;

		private @Nullable Double topP;

		private @Nullable Integer topK;

		private @Nullable Double frequencyPenalty;

		private @Nullable Double presencePenalty;

		private @Nullable Map<String, @Nullable Object> metadata;

		private @Nullable ProviderPreferences provider;

		private @Nullable ReasoningOptions reasoning;

		private @Nullable String route;

		private @Nullable String serviceTier;

		private @Nullable String user;

		private @Nullable Boolean parallelToolCalls;

		private @Nullable Object toolChoice;

		private @Nullable List<ResponsesTool> tools;

		private @Nullable List<String> modalities;

		private @Nullable Map<String, @Nullable Object> imageConfig;

		private @Nullable Map<String, @Nullable Object> text;

		private @Nullable String sessionId;

		private @Nullable Map<String, @Nullable Object> trace;

		private @Nullable Integer topLogprobs;

		private @Nullable String promptCacheKey;

		private @Nullable PromptCacheOptions promptCacheOptions;

		private @Nullable Map<String, @Nullable Object> extraBody;

		private Builder() {
		}

		public Builder model(@Nullable String model) {
			this.model = model;
			return this;
		}

		public Builder models(@Nullable List<String> models) {
			this.models = models;
			return this;
		}

		/**
		 * Sets the Responses {@code input}: a string or a list of input items.
		 */
		public Builder input(Object input) {
			this.input = input;
			return this;
		}

		public Builder instructions(@Nullable String instructions) {
			this.instructions = instructions;
			return this;
		}

		public Builder maxOutputTokens(@Nullable Integer maxOutputTokens) {
			this.maxOutputTokens = maxOutputTokens;
			return this;
		}

		public Builder stream(@Nullable Boolean stream) {
			this.stream = stream;
			return this;
		}

		public Builder temperature(@Nullable Double temperature) {
			this.temperature = temperature;
			return this;
		}

		public Builder topP(@Nullable Double topP) {
			this.topP = topP;
			return this;
		}

		public Builder topK(@Nullable Integer topK) {
			this.topK = topK;
			return this;
		}

		public Builder frequencyPenalty(@Nullable Double frequencyPenalty) {
			this.frequencyPenalty = frequencyPenalty;
			return this;
		}

		public Builder presencePenalty(@Nullable Double presencePenalty) {
			this.presencePenalty = presencePenalty;
			return this;
		}

		public Builder metadata(@Nullable Map<String, @Nullable Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder provider(@Nullable ProviderPreferences provider) {
			this.provider = provider;
			return this;
		}

		public Builder reasoning(@Nullable ReasoningOptions reasoning) {
			this.reasoning = reasoning;
			return this;
		}

		public Builder route(@Nullable String route) {
			this.route = route;
			return this;
		}

		public Builder serviceTier(@Nullable String serviceTier) {
			this.serviceTier = serviceTier;
			return this;
		}

		public Builder user(@Nullable String user) {
			this.user = user;
			return this;
		}

		public Builder parallelToolCalls(@Nullable Boolean parallelToolCalls) {
			this.parallelToolCalls = parallelToolCalls;
			return this;
		}

		public Builder toolChoice(@Nullable Object toolChoice) {
			this.toolChoice = toolChoice;
			return this;
		}

		public Builder tools(@Nullable List<ResponsesTool> tools) {
			this.tools = tools;
			return this;
		}

		public Builder modalities(@Nullable List<String> modalities) {
			this.modalities = modalities;
			return this;
		}

		public Builder imageConfig(@Nullable Map<String, @Nullable Object> imageConfig) {
			this.imageConfig = imageConfig;
			return this;
		}

		public Builder text(@Nullable Map<String, @Nullable Object> text) {
			this.text = text;
			return this;
		}

		public Builder sessionId(@Nullable String sessionId) {
			this.sessionId = sessionId;
			return this;
		}

		public Builder trace(@Nullable Map<String, @Nullable Object> trace) {
			this.trace = trace;
			return this;
		}

		public Builder topLogprobs(@Nullable Integer topLogprobs) {
			this.topLogprobs = topLogprobs;
			return this;
		}

		public Builder promptCacheKey(@Nullable String promptCacheKey) {
			this.promptCacheKey = promptCacheKey;
			return this;
		}

		public Builder promptCacheOptions(@Nullable PromptCacheOptions promptCacheOptions) {
			this.promptCacheOptions = promptCacheOptions;
			return this;
		}

		public Builder extraBody(@Nullable Map<String, @Nullable Object> extraBody) {
			this.extraBody = extraBody;
			return this;
		}

		/**
		 * Builds the request.
		 * @throws NullPointerException if {@code input} was not set
		 */
		public ResponsesRequest build() {
			return new ResponsesRequest(this.model, this.models,
					Objects.requireNonNull(this.input, "input must be set"), this.instructions, this.maxOutputTokens,
					this.stream, this.temperature, this.topP, this.topK, this.frequencyPenalty, this.presencePenalty,
					this.metadata, this.provider, this.reasoning, this.route, this.serviceTier, this.user,
					this.parallelToolCalls, this.toolChoice, this.tools, this.modalities, this.imageConfig, this.text,
					this.sessionId, this.trace, this.topLogprobs, this.promptCacheKey, this.promptCacheOptions,
					this.extraBody);
		}

	}

}
