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

		private @Nullable List<ChatMessage> messages;

		private @Nullable Double temperature;

		private @Nullable Double topP;

		private @Nullable Integer topK;

		private @Nullable Double frequencyPenalty;

		private @Nullable Double presencePenalty;

		private @Nullable Double repetitionPenalty;

		private @Nullable Double minP;

		private @Nullable Double topA;

		private @Nullable Integer maxTokens;

		private @Nullable Integer maxCompletionTokens;

		private @Nullable List<String> stop;

		private @Nullable Integer seed;

		private @Nullable String user;

		private @Nullable Boolean stream;

		private @Nullable Object responseFormat;

		private @Nullable List<Tool> tools;

		private @Nullable Object toolChoice;

		private @Nullable Boolean parallelToolCalls;

		private @Nullable ProviderPreferences provider;

		private @Nullable ReasoningOptions reasoning;

		private @Nullable String serviceTier;

		private @Nullable Map<String, @Nullable Object> metadata;

		private @Nullable String route;

		private @Nullable UsageConfig usage;

		private @Nullable List<String> modalities;

		private @Nullable Map<String, @Nullable Object> imageConfig;

		private @Nullable AudioConfig audio;

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

		public Builder messages(List<ChatMessage> messages) {
			this.messages = messages;
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

		public Builder repetitionPenalty(@Nullable Double repetitionPenalty) {
			this.repetitionPenalty = repetitionPenalty;
			return this;
		}

		public Builder minP(@Nullable Double minP) {
			this.minP = minP;
			return this;
		}

		public Builder topA(@Nullable Double topA) {
			this.topA = topA;
			return this;
		}

		public Builder maxTokens(@Nullable Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder maxCompletionTokens(@Nullable Integer maxCompletionTokens) {
			this.maxCompletionTokens = maxCompletionTokens;
			return this;
		}

		public Builder stop(@Nullable List<String> stop) {
			this.stop = stop;
			return this;
		}

		public Builder seed(@Nullable Integer seed) {
			this.seed = seed;
			return this;
		}

		public Builder user(@Nullable String user) {
			this.user = user;
			return this;
		}

		public Builder stream(@Nullable Boolean stream) {
			this.stream = stream;
			return this;
		}

		public Builder responseFormat(@Nullable Object responseFormat) {
			this.responseFormat = responseFormat;
			return this;
		}

		public Builder tools(@Nullable List<Tool> tools) {
			this.tools = tools;
			return this;
		}

		public Builder toolChoice(@Nullable Object toolChoice) {
			this.toolChoice = toolChoice;
			return this;
		}

		public Builder parallelToolCalls(@Nullable Boolean parallelToolCalls) {
			this.parallelToolCalls = parallelToolCalls;
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

		public Builder serviceTier(@Nullable String serviceTier) {
			this.serviceTier = serviceTier;
			return this;
		}

		public Builder metadata(@Nullable Map<String, @Nullable Object> metadata) {
			this.metadata = metadata;
			return this;
		}

		public Builder route(@Nullable String route) {
			this.route = route;
			return this;
		}

		public Builder usage(@Nullable UsageConfig usage) {
			this.usage = usage;
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

		public Builder audio(@Nullable AudioConfig audio) {
			this.audio = audio;
			return this;
		}

		public Builder extraBody(@Nullable Map<String, @Nullable Object> extraBody) {
			this.extraBody = extraBody;
			return this;
		}

		/**
		 * Builds the request.
		 * @throws NullPointerException if {@code messages} was not set
		 */
		public ChatCompletionRequest build() {
			return new ChatCompletionRequest(this.model, this.models,
					Objects.requireNonNull(this.messages, "messages must be set"), this.temperature, this.topP,
					this.topK, this.frequencyPenalty, this.presencePenalty, this.repetitionPenalty, this.minP,
					this.topA, this.maxTokens, this.maxCompletionTokens, this.stop, this.seed, this.user, this.stream,
					this.responseFormat, this.tools, this.toolChoice, this.parallelToolCalls, this.provider,
					this.reasoning, this.serviceTier, this.metadata, this.route, this.usage, this.modalities,
					this.imageConfig, this.audio, this.extraBody);
		}

	}

}
