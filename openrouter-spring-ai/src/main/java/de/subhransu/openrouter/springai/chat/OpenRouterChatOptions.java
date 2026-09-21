package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.RequestExtensions;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.lang.Contract;
import org.springframework.util.Assert;

public class OpenRouterChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	private @Nullable String model;

	private @Nullable List<String> models;

	private @Nullable OpenRouterRequestMode requestMode;

	private @Nullable Double frequencyPenalty;

	private @Nullable Integer maxTokens;

	private @Nullable Integer maxCompletionTokens;

	private @Nullable Double presencePenalty;

	private @Nullable List<String> stopSequences;

	private @Nullable Double temperature;

	private @Nullable Integer topK;

	private @Nullable Double topP;

	private @Nullable Double repetitionPenalty;

	private @Nullable Double minP;

	private @Nullable Double topA;

	private @Nullable Integer seed;

	private @Nullable String user;

	private @Nullable OpenRouterResponseFormat responseFormat;

	private @Nullable Boolean parallelToolCalls;

	private @Nullable Boolean toolStrict;

	private @Nullable Object toolChoice;

	private @Nullable OpenRouterProviderPreferences provider;

	private @Nullable OpenRouterReasoningOptions reasoning;

	private @Nullable OpenRouterServiceTier serviceTier;

	private @Nullable Map<String, @Nullable Object> metadata;

	private @Nullable String route;

	private @Nullable Boolean includeUsage;

	private @Nullable List<String> modalities;

	private @Nullable OpenRouterAudioOptions audio;

	private @Nullable Map<String, @Nullable Object> imageConfig;

	private @Nullable Map<String, @Nullable Object> extraBody;

	private @Nullable Map<String, @Nullable Object> providerExtraBody;

	private @Nullable String outputSchema;

	private @Nullable List<ToolCallback> toolCallbacks = new ArrayList<>();

	private @Nullable Map<String, Object> toolContext;

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Builder mutate() {
		return new Builder(this);
	}

	@Contract("!null -> !null")
	public static @Nullable OpenRouterChatOptions fromOptions(@Nullable ChatOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterChatOptions openRouterOptions) {
			return openRouterOptions.copy();
		}
		Builder builder = OpenRouterChatOptions.builder()
			.requestMode(null)
			.model(options.getModel())
			.frequencyPenalty(options.getFrequencyPenalty())
			.maxTokens(options.getMaxTokens())
			.presencePenalty(options.getPresencePenalty())
			.stopSequences(options.getStopSequences())
			.temperature(options.getTemperature())
			.topK(options.getTopK())
			.topP(options.getTopP());
		if (options instanceof ToolCallingChatOptions toolCallingOptions) {
			builder.toolCallbacks(toolCallingOptions.getToolCallbacks())
				.toolContext(toolCallingOptions.getToolContext());
		}
		if (options instanceof StructuredOutputChatOptions structuredOutputOptions) {
			builder.outputSchema(structuredOutputOptions.getOutputSchema());
		}
		return builder.build();
	}

	public OpenRouterChatOptions merge(@Nullable OpenRouterChatOptions runtimeOptions) {
		if (runtimeOptions == null) {
			return this.copy();
		}
		Builder builder = this.mutate();
		builder.model(value(runtimeOptions.model, this.model));
		builder.models(value(runtimeOptions.models, this.models));
		builder.requestMode(value(runtimeOptions.requestMode, this.requestMode));
		builder.frequencyPenalty(value(runtimeOptions.frequencyPenalty, this.frequencyPenalty));
		builder.maxTokens(value(runtimeOptions.maxTokens, this.maxTokens));
		builder.maxCompletionTokens(value(runtimeOptions.maxCompletionTokens, this.maxCompletionTokens));
		builder.presencePenalty(value(runtimeOptions.presencePenalty, this.presencePenalty));
		builder.stopSequences(value(runtimeOptions.stopSequences, this.stopSequences));
		builder.temperature(value(runtimeOptions.temperature, this.temperature));
		builder.topK(value(runtimeOptions.topK, this.topK));
		builder.topP(value(runtimeOptions.topP, this.topP));
		builder.repetitionPenalty(value(runtimeOptions.repetitionPenalty, this.repetitionPenalty));
		builder.minP(value(runtimeOptions.minP, this.minP));
		builder.topA(value(runtimeOptions.topA, this.topA));
		builder.seed(value(runtimeOptions.seed, this.seed));
		builder.user(value(runtimeOptions.user, this.user));
		builder.responseFormat(value(runtimeOptions.responseFormat, this.responseFormat));
		builder.parallelToolCalls(value(runtimeOptions.parallelToolCalls, this.parallelToolCalls));
		builder.toolStrict(value(runtimeOptions.toolStrict, this.toolStrict));
		builder.toolChoice(value(runtimeOptions.toolChoice, this.toolChoice));
		builder.provider(value(runtimeOptions.provider, this.provider));
		builder.reasoning(value(runtimeOptions.reasoning, this.reasoning));
		builder.serviceTier(value(runtimeOptions.serviceTier, this.serviceTier));
		builder.metadata(mergeMaps(this.metadata, runtimeOptions.metadata));
		builder.route(value(runtimeOptions.route, this.route));
		builder.includeUsage(value(runtimeOptions.includeUsage, this.includeUsage));
		builder.modalities(value(runtimeOptions.modalities, this.modalities));
		builder.audio(value(runtimeOptions.audio, this.audio));
		builder.imageConfig(value(runtimeOptions.imageConfig, this.imageConfig));
		builder.extraBody(mergeMaps(this.extraBody, runtimeOptions.extraBody));
		builder.providerExtraBody(mergeMaps(this.providerExtraBody, runtimeOptions.providerExtraBody));
		builder.outputSchema(value(runtimeOptions.outputSchema, this.outputSchema));
		// Framework merge semantics (ToolCallingChatOptions): runtime tool callbacks
		// replace the defaults wholesale rather than accumulating, so the executing
		// advisor sees exactly the tools that were advertised for this request.
		builder
			.toolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.toolCallbacks, this.toolCallbacks));
		builder.toolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.toolContext, this.toolContext));
		return builder.build();
	}

	private static <T> @Nullable T value(@Nullable T runtime, @Nullable T defaults) {
		return runtime != null ? runtime : defaults;
	}

	@SuppressWarnings("unchecked")
	public <T extends ChatOptions> T copy() {
		return (T) this.mutate().build();
	}

	@Override
	public @Nullable String getModel() {
		return this.model;
	}

	public @Nullable List<String> getModels() {
		return readOnlyList(this.models);
	}

	public @Nullable OpenRouterRequestMode getRequestMode() {
		return this.requestMode;
	}

	@Override
	public @Nullable Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	@Override
	public @Nullable Integer getMaxTokens() {
		return this.maxTokens;
	}

	public @Nullable Integer getMaxCompletionTokens() {
		return this.maxCompletionTokens;
	}

	@Override
	public @Nullable Double getPresencePenalty() {
		return this.presencePenalty;
	}

	@Override
	public @Nullable List<String> getStopSequences() {
		return readOnlyList(this.stopSequences);
	}

	@Override
	public @Nullable Double getTemperature() {
		return this.temperature;
	}

	@Override
	public @Nullable Integer getTopK() {
		return this.topK;
	}

	@Override
	public @Nullable Double getTopP() {
		return this.topP;
	}

	public @Nullable Double getRepetitionPenalty() {
		return this.repetitionPenalty;
	}

	public @Nullable Double getMinP() {
		return this.minP;
	}

	public @Nullable Double getTopA() {
		return this.topA;
	}

	public @Nullable Integer getSeed() {
		return this.seed;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public @Nullable OpenRouterResponseFormat getResponseFormat() {
		return this.responseFormat;
	}

	public @Nullable Boolean getParallelToolCalls() {
		return this.parallelToolCalls;
	}

	public @Nullable Boolean getToolStrict() {
		return this.toolStrict;
	}

	public @Nullable Object getToolChoice() {
		return this.toolChoice;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public @Nullable OpenRouterReasoningOptions getReasoning() {
		return this.reasoning;
	}

	public @Nullable OpenRouterServiceTier getServiceTier() {
		return this.serviceTier;
	}

	public @Nullable Map<String, @Nullable Object> getMetadata() {
		return readOnlyMap(this.metadata);
	}

	public @Nullable String getRoute() {
		return this.route;
	}

	public @Nullable Boolean getIncludeUsage() {
		return this.includeUsage;
	}

	public @Nullable OpenRouterAudioOptions getAudio() {
		return this.audio;
	}

	public @Nullable List<String> getModalities() {
		return readOnlyList(this.modalities);
	}

	public @Nullable Map<String, @Nullable Object> getImageConfig() {
		return readOnlyMap(this.imageConfig);
	}

	public @Nullable Map<String, @Nullable Object> getProviderExtraBody() {
		return readOnlyMap(this.providerExtraBody);
	}

	public @Nullable Map<String, @Nullable Object> getExtraBody() {
		return readOnlyMap(this.extraBody);
	}

	@Override
	public @Nullable String getOutputSchema() {
		return this.outputSchema;
	}

	public void setOutputSchema(@Nullable String outputSchema) {
		this.outputSchema = outputSchema;
	}

	@Override
	public @Nullable List<ToolCallback> getToolCallbacks() {
		return readOnlyList(this.toolCallbacks);
	}

	public void setToolCallbacks(@Nullable List<ToolCallback> toolCallbacks) {
		this.toolCallbacks = copyList(toolCallbacks);
	}

	@Override
	public @Nullable Map<String, Object> getToolContext() {
		return readOnlyMap(this.toolContext);
	}

	public void setToolContext(@Nullable Map<String, Object> toolContext) {
		this.toolContext = copyToolContext(toolContext);
	}

	private static <T> @Nullable List<T> copyList(@Nullable List<T> values) {
		return values == null ? null : new ArrayList<>(values);
	}

	private static <T> @Nullable List<T> combineLists(@Nullable List<T> defaults, @Nullable List<T> additions) {
		if (defaults == null) {
			return copyList(additions);
		}
		List<T> combined = new ArrayList<>(defaults);
		if (additions != null) {
			combined.addAll(additions);
		}
		return combined;
	}

	private static <T> @Nullable List<T> readOnlyList(@Nullable List<T> values) {
		return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
	}

	@Contract("!null -> !null")
	private static @Nullable Map<String, @Nullable Object> copyMap(@Nullable Map<String, @Nullable Object> values) {
		return values == null ? null : new LinkedHashMap<>(OptionSnapshots.map(values));
	}

	private static <V extends @Nullable Object> @Nullable Map<String, V> readOnlyMap(@Nullable Map<String, V> values) {
		return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static @Nullable Map<String, @Nullable Object> mergeMaps(@Nullable Map<String, @Nullable Object> defaults,
			@Nullable Map<String, @Nullable Object> runtime) {
		if (defaults == null) {
			return copyMap(runtime);
		}
		Map<String, @Nullable Object> merged = copyMap(defaults);
		if (runtime != null) {
			merged.putAll(runtime);
		}
		return merged;
	}

	private static @Nullable Map<String, Object> copyToolContext(@Nullable Map<String, Object> values) {
		if (values == null) {
			return null;
		}
		Map<String, Object> copy = new LinkedHashMap<>();
		values.forEach((key, value) -> copy.put(key, OptionSnapshots.value(value)));
		return copy;
	}

	public static final class Builder
			implements ToolCallingChatOptions.Builder<Builder>, StructuredOutputChatOptions.Builder<Builder> {

		private OpenRouterChatOptions options;

		private Builder() {
			this.options = new OpenRouterChatOptions();
		}

		private Builder(OpenRouterChatOptions source) {
			this.options = new OpenRouterChatOptions();
			this.options.model = source.model;
			this.options.models = copyList(source.models);
			this.options.requestMode = source.requestMode;
			this.options.frequencyPenalty = source.frequencyPenalty;
			this.options.maxTokens = source.maxTokens;
			this.options.maxCompletionTokens = source.maxCompletionTokens;
			this.options.presencePenalty = source.presencePenalty;
			this.options.stopSequences = copyList(source.stopSequences);
			this.options.temperature = source.temperature;
			this.options.topK = source.topK;
			this.options.topP = source.topP;
			this.options.repetitionPenalty = source.repetitionPenalty;
			this.options.minP = source.minP;
			this.options.topA = source.topA;
			this.options.seed = source.seed;
			this.options.user = source.user;
			this.options.responseFormat = source.responseFormat;
			this.options.parallelToolCalls = source.parallelToolCalls;
			this.options.toolStrict = source.toolStrict;
			this.options.toolChoice = OptionSnapshots.value(source.toolChoice);
			this.options.provider = source.provider;
			this.options.reasoning = source.reasoning;
			this.options.serviceTier = source.serviceTier;
			this.options.metadata = copyMap(source.metadata);
			this.options.route = source.route;
			this.options.includeUsage = source.includeUsage;
			this.options.modalities = copyList(source.modalities);
			this.options.audio = source.audio;
			this.options.imageConfig = copyMap(source.imageConfig);
			this.options.extraBody = copyMap(source.extraBody);
			this.options.providerExtraBody = copyMap(source.providerExtraBody);
			this.options.outputSchema = source.outputSchema;
			this.options.toolCallbacks = copyList(source.toolCallbacks);
			this.options.toolContext = copyToolContext(source.toolContext);
		}

		@Override
		@SuppressWarnings({ "java:S1182", "java:S2975" }) // Spring AI's builder contract
															// requires clone().
		public Builder clone() {
			return new Builder(this.options);
		}

		@Override
		public Builder combineWith(ChatOptions.@Nullable Builder<?> builder) {
			if (builder == null) {
				return this;
			}
			OpenRouterChatOptions builderOptions = OpenRouterChatOptions.fromOptions(builder.build());
			if (builderOptions != null) {
				OpenRouterChatOptions combined = this.options.merge(builderOptions);
				// Builder composition appends lists; model/runtime merge replaces them.
				combined.toolCallbacks = combineLists(this.options.toolCallbacks, builderOptions.toolCallbacks);
				combined.stopSequences = combineLists(this.options.stopSequences, builderOptions.stopSequences);
				this.options = combined;
			}
			return this;
		}

		@Override
		public Builder model(@Nullable String model) {
			this.options.model = model;
			return this;
		}

		public Builder models(@Nullable List<String> models) {
			this.options.models = copyList(models);
			return this;
		}

		public Builder requestMode(@Nullable OpenRouterRequestMode requestMode) {
			this.options.requestMode = requestMode;
			return this;
		}

		@Override
		public Builder frequencyPenalty(@Nullable Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		@Override
		public Builder maxTokens(@Nullable Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		public Builder maxCompletionTokens(@Nullable Integer maxCompletionTokens) {
			this.options.maxCompletionTokens = maxCompletionTokens;
			return this;
		}

		@Override
		public Builder presencePenalty(@Nullable Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		@Override
		public Builder stopSequences(@Nullable List<String> stopSequences) {
			this.options.stopSequences = copyList(stopSequences);
			return this;
		}

		@Override
		public Builder temperature(@Nullable Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		@Override
		public Builder topK(@Nullable Integer topK) {
			this.options.topK = topK;
			return this;
		}

		@Override
		public Builder topP(@Nullable Double topP) {
			this.options.topP = topP;
			return this;
		}

		public Builder repetitionPenalty(@Nullable Double repetitionPenalty) {
			this.options.repetitionPenalty = repetitionPenalty;
			return this;
		}

		public Builder minP(@Nullable Double minP) {
			this.options.minP = minP;
			return this;
		}

		public Builder topA(@Nullable Double topA) {
			this.options.topA = topA;
			return this;
		}

		public Builder seed(@Nullable Integer seed) {
			this.options.seed = seed;
			return this;
		}

		public Builder user(@Nullable String user) {
			this.options.user = user;
			return this;
		}

		public Builder responseFormat(@Nullable OpenRouterResponseFormat responseFormat) {
			this.options.responseFormat = responseFormat;
			return this;
		}

		/**
		 * Strictness for every function tool: null omits the flag, false disables it,
		 * true validates the supplied schema without rewriting it. Provider support is
		 * required. Independent of response-format strictness.
		 */
		public Builder toolStrict(@Nullable Boolean toolStrict) {
			this.options.toolStrict = toolStrict;
			return this;
		}

		public Builder parallelToolCalls(@Nullable Boolean parallelToolCalls) {
			this.options.parallelToolCalls = parallelToolCalls;
			return this;
		}

		/**
		 * Accepts auto, none, required, or a named function in either endpoint's shape.
		 * Legacy objects containing only a type of auto, none, or required are also
		 * accepted. The mapper converts named choices to the selected mode and rejects
		 * other shapes.
		 */
		public Builder toolChoice(@Nullable Object toolChoice) {
			this.options.toolChoice = OptionSnapshots.value(toolChoice);
			return this;
		}

		public Builder provider(@Nullable OpenRouterProviderPreferences provider) {
			this.options.provider = provider;
			return this;
		}

		public Builder reasoning(@Nullable OpenRouterReasoningOptions reasoning) {
			this.options.reasoning = reasoning;
			return this;
		}

		public Builder serviceTier(@Nullable OpenRouterServiceTier serviceTier) {
			this.options.serviceTier = serviceTier;
			return this;
		}

		public Builder metadata(@Nullable Map<String, @Nullable Object> metadata) {
			this.options.metadata = copyMap(metadata);
			return this;
		}

		public Builder route(@Nullable String route) {
			this.options.route = route;
			return this;
		}

		public Builder includeUsage(@Nullable Boolean includeUsage) {
			this.options.includeUsage = includeUsage;
			return this;
		}

		/**
		 * Voice and encoding for audio output; requires streaming and the audio modality.
		 */
		public Builder audio(@Nullable OpenRouterAudioOptions audio) {
			this.options.audio = audio;
			return this;
		}

		/**
		 * Output modalities to request, e.g. {@code ["image", "text"]} for
		 * image-generating chat models.
		 */
		public Builder modalities(@Nullable List<String> modalities) {
			this.options.modalities = copyList(modalities);
			return this;
		}

		/**
		 * Model-specific image generation configuration forwarded as
		 * {@code image_config}, e.g. {@code {"aspect_ratio": "16:9"}}.
		 */
		public Builder imageConfig(@Nullable Map<String, @Nullable Object> imageConfig) {
			this.options.imageConfig = copyMap(imageConfig);
			return this;
		}

		public Builder providerExtraBody(@Nullable Map<String, @Nullable Object> providerExtraBody) {
			this.options.providerExtraBody = RequestExtensions.provider(providerExtraBody, null);
			return this;
		}

		public Builder extraBody(@Nullable Map<String, @Nullable Object> extraBody) {
			this.options.extraBody = RequestExtensions.chat(extraBody, false);
			return this;
		}

		@Override
		public Builder outputSchema(@Nullable String outputSchema) {
			this.options.outputSchema = outputSchema;
			return this;
		}

		@Override
		public Builder toolCallbacks(@Nullable List<ToolCallback> toolCallbacks) {
			this.options.toolCallbacks = copyList(toolCallbacks);
			return this;
		}

		@Override
		public Builder toolCallbacks(ToolCallback... toolCallbacks) {
			Assert.notNull(toolCallbacks, "toolCallbacks cannot be null");
			this.options.toolCallbacks = combineLists(this.options.toolCallbacks, List.of(toolCallbacks));
			return this;
		}

		@Override
		public Builder toolContext(@Nullable Map<String, Object> toolContext) {
			this.options.toolContext = toolContext == null ? null
					: copyToolContext(ToolCallingChatOptions.mergeToolContext(toolContext, this.options.toolContext));
			return this;
		}

		@Override
		public Builder toolContext(String key, Object value) {
			if (this.options.toolContext == null) {
				this.options.toolContext = new LinkedHashMap<>();
			}
			this.options.toolContext.put(key, OptionSnapshots.value(value));
			return this;
		}

		@Override
		public OpenRouterChatOptions build() {
			// Detached snapshot: mutating this builder after build() must not leak into
			// the returned options. The copy constructor also deep-copies collections.
			return new Builder(this.options).options;
		}

	}

}
