package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;

public class OpenRouterChatOptions implements ToolCallingChatOptions, StructuredOutputChatOptions {

	private String model;

	private List<String> models;

	private OpenRouterRequestMode requestMode;

	private Double frequencyPenalty;

	private Integer maxTokens;

	private Integer maxCompletionTokens;

	private Double presencePenalty;

	private List<String> stopSequences;

	private Double temperature;

	private Integer topK;

	private Double topP;

	private Double repetitionPenalty;

	private Double minP;

	private Double topA;

	private Integer seed;

	private String user;

	private OpenRouterResponseFormat responseFormat;

	private Boolean parallelToolCalls;

	private Object toolChoice;

	private OpenRouterProviderPreferences provider;

	private OpenRouterReasoningOptions reasoning;

	private OpenRouterServiceTier serviceTier;

	private Map<String, Object> metadata;

	private String route;

	private Boolean includeUsage;

	private List<String> modalities;

	private Map<String, Object> imageConfig;

	private String outputSchema;

	private List<ToolCallback> toolCallbacks = new ArrayList<>();

	private Map<String, Object> toolContext;

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Builder mutate() {
		return new Builder(this);
	}

	public static OpenRouterChatOptions fromOptions(ChatOptions options) {
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

	public OpenRouterChatOptions merge(OpenRouterChatOptions runtimeOptions) {
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
		builder.toolChoice(value(runtimeOptions.toolChoice, this.toolChoice));
		builder.provider(value(runtimeOptions.provider, this.provider));
		builder.reasoning(value(runtimeOptions.reasoning, this.reasoning));
		builder.serviceTier(value(runtimeOptions.serviceTier, this.serviceTier));
		builder.metadata(mergeMaps(this.metadata, runtimeOptions.metadata));
		builder.route(value(runtimeOptions.route, this.route));
		builder.includeUsage(value(runtimeOptions.includeUsage, this.includeUsage));
		builder.modalities(value(runtimeOptions.modalities, this.modalities));
		builder.imageConfig(value(runtimeOptions.imageConfig, this.imageConfig));
		builder.outputSchema(value(runtimeOptions.outputSchema, this.outputSchema));
		// Framework merge semantics (ToolCallingChatOptions): runtime tool callbacks
		// replace the defaults wholesale rather than accumulating, so the executing
		// advisor sees exactly the tools that were advertised for this request.
		builder
			.toolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.toolCallbacks, this.toolCallbacks));
		builder.toolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.toolContext, this.toolContext));
		return builder.build();
	}

	private static <T> T value(T runtime, T defaults) {
		return runtime != null ? runtime : defaults;
	}

	@SuppressWarnings("unchecked")
	public <T extends ChatOptions> T copy() {
		return (T) this.mutate().build();
	}

	@Override
	public String getModel() {
		return this.model;
	}

	public List<String> getModels() {
		return readOnlyList(this.models);
	}

	public OpenRouterRequestMode getRequestMode() {
		return this.requestMode;
	}

	@Override
	public Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	@Override
	public Integer getMaxTokens() {
		return this.maxTokens;
	}

	public Integer getMaxCompletionTokens() {
		return this.maxCompletionTokens;
	}

	@Override
	public Double getPresencePenalty() {
		return this.presencePenalty;
	}

	@Override
	public List<String> getStopSequences() {
		return readOnlyList(this.stopSequences);
	}

	@Override
	public Double getTemperature() {
		return this.temperature;
	}

	@Override
	public Integer getTopK() {
		return this.topK;
	}

	@Override
	public Double getTopP() {
		return this.topP;
	}

	public Double getRepetitionPenalty() {
		return this.repetitionPenalty;
	}

	public Double getMinP() {
		return this.minP;
	}

	public Double getTopA() {
		return this.topA;
	}

	public Integer getSeed() {
		return this.seed;
	}

	public String getUser() {
		return this.user;
	}

	public OpenRouterResponseFormat getResponseFormat() {
		return this.responseFormat;
	}

	public Boolean getParallelToolCalls() {
		return this.parallelToolCalls;
	}

	public Object getToolChoice() {
		return this.toolChoice;
	}

	public OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public OpenRouterReasoningOptions getReasoning() {
		return this.reasoning;
	}

	public OpenRouterServiceTier getServiceTier() {
		return this.serviceTier;
	}

	public Map<String, Object> getMetadata() {
		return readOnlyMap(this.metadata);
	}

	public String getRoute() {
		return this.route;
	}

	public Boolean getIncludeUsage() {
		return this.includeUsage;
	}

	public List<String> getModalities() {
		return readOnlyList(this.modalities);
	}

	public Map<String, Object> getImageConfig() {
		return readOnlyMap(this.imageConfig);
	}

	@Override
	public String getOutputSchema() {
		return this.outputSchema;
	}

	public void setOutputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
	}

	@Override
	public List<ToolCallback> getToolCallbacks() {
		return readOnlyList(this.toolCallbacks);
	}

	public void setToolCallbacks(List<ToolCallback> toolCallbacks) {
		this.toolCallbacks = copyList(toolCallbacks);
	}

	@Override
	public Map<String, Object> getToolContext() {
		return readOnlyMap(this.toolContext);
	}

	public void setToolContext(Map<String, Object> toolContext) {
		this.toolContext = copyMap(toolContext);
	}

	private static <T> List<T> copyList(List<T> values) {
		return values == null ? null : new ArrayList<>(values);
	}

	private static <T> List<T> combineLists(List<T> defaults, List<T> additions) {
		if (defaults == null) {
			return copyList(additions);
		}
		List<T> combined = copyList(defaults);
		if (additions != null) {
			combined.addAll(additions);
		}
		return combined;
	}

	private static <T> List<T> readOnlyList(List<T> values) {
		return values == null ? null : Collections.unmodifiableList(new ArrayList<>(values));
	}

	private static Map<String, Object> copyMap(Map<String, Object> values) {
		return values == null ? null : new LinkedHashMap<>(OptionSnapshots.map(values));
	}

	private static Map<String, Object> readOnlyMap(Map<String, Object> values) {
		return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}

	private static Map<String, Object> mergeMaps(Map<String, Object> defaults, Map<String, Object> runtime) {
		if (defaults == null) {
			return copyMap(runtime);
		}
		Map<String, Object> merged = copyMap(defaults);
		if (runtime != null) {
			merged.putAll(runtime);
		}
		return merged;
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
			this.options.toolChoice = OptionSnapshots.value(source.toolChoice);
			this.options.provider = source.provider;
			this.options.reasoning = source.reasoning;
			this.options.serviceTier = source.serviceTier;
			this.options.metadata = copyMap(source.metadata);
			this.options.route = source.route;
			this.options.includeUsage = source.includeUsage;
			this.options.modalities = copyList(source.modalities);
			this.options.imageConfig = copyMap(source.imageConfig);
			this.options.outputSchema = source.outputSchema;
			this.options.toolCallbacks = copyList(source.toolCallbacks);
			this.options.toolContext = copyMap(source.toolContext);
		}

		@Override
		@SuppressWarnings({ "java:S1182", "java:S2975" }) // Spring AI's builder contract
															// requires clone().
		public Builder clone() {
			return new Builder(this.options);
		}

		@Override
		public Builder combineWith(ChatOptions.Builder<?> builder) {
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
		public Builder model(String model) {
			this.options.model = model;
			return this;
		}

		public Builder models(List<String> models) {
			this.options.models = copyList(models);
			return this;
		}

		public Builder requestMode(OpenRouterRequestMode requestMode) {
			this.options.requestMode = requestMode;
			return this;
		}

		@Override
		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		@Override
		public Builder maxTokens(Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		public Builder maxCompletionTokens(Integer maxCompletionTokens) {
			this.options.maxCompletionTokens = maxCompletionTokens;
			return this;
		}

		@Override
		public Builder presencePenalty(Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		@Override
		public Builder stopSequences(List<String> stopSequences) {
			this.options.stopSequences = copyList(stopSequences);
			return this;
		}

		@Override
		public Builder temperature(Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		@Override
		public Builder topK(Integer topK) {
			this.options.topK = topK;
			return this;
		}

		@Override
		public Builder topP(Double topP) {
			this.options.topP = topP;
			return this;
		}

		public Builder repetitionPenalty(Double repetitionPenalty) {
			this.options.repetitionPenalty = repetitionPenalty;
			return this;
		}

		public Builder minP(Double minP) {
			this.options.minP = minP;
			return this;
		}

		public Builder topA(Double topA) {
			this.options.topA = topA;
			return this;
		}

		public Builder seed(Integer seed) {
			this.options.seed = seed;
			return this;
		}

		public Builder user(String user) {
			this.options.user = user;
			return this;
		}

		public Builder responseFormat(OpenRouterResponseFormat responseFormat) {
			this.options.responseFormat = responseFormat;
			return this;
		}

		public Builder parallelToolCalls(Boolean parallelToolCalls) {
			this.options.parallelToolCalls = parallelToolCalls;
			return this;
		}

		/**
		 * Accepts auto, none, required, or a named function in either endpoint's shape.
		 * Legacy objects containing only a type of auto, none, or required are also
		 * accepted. The mapper converts named choices to the selected mode and rejects
		 * other shapes.
		 */
		public Builder toolChoice(Object toolChoice) {
			this.options.toolChoice = OptionSnapshots.value(toolChoice);
			return this;
		}

		public Builder provider(OpenRouterProviderPreferences provider) {
			this.options.provider = provider;
			return this;
		}

		public Builder reasoning(OpenRouterReasoningOptions reasoning) {
			this.options.reasoning = reasoning;
			return this;
		}

		public Builder serviceTier(OpenRouterServiceTier serviceTier) {
			this.options.serviceTier = serviceTier;
			return this;
		}

		public Builder metadata(Map<String, Object> metadata) {
			this.options.metadata = copyMap(metadata);
			return this;
		}

		public Builder route(String route) {
			this.options.route = route;
			return this;
		}

		public Builder includeUsage(Boolean includeUsage) {
			this.options.includeUsage = includeUsage;
			return this;
		}

		/**
		 * Output modalities to request, e.g. {@code ["image", "text"]} for
		 * image-generating chat models.
		 */
		public Builder modalities(List<String> modalities) {
			this.options.modalities = copyList(modalities);
			return this;
		}

		/**
		 * Model-specific image generation configuration forwarded as
		 * {@code image_config}, e.g. {@code {"aspect_ratio": "16:9"}}.
		 */
		public Builder imageConfig(Map<String, Object> imageConfig) {
			this.options.imageConfig = copyMap(imageConfig);
			return this;
		}

		@Override
		public Builder outputSchema(String outputSchema) {
			this.options.outputSchema = outputSchema;
			return this;
		}

		@Override
		public Builder toolCallbacks(List<ToolCallback> toolCallbacks) {
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
		public Builder toolContext(Map<String, Object> toolContext) {
			this.options.toolContext = toolContext == null ? null : mergeMaps(this.options.toolContext, toolContext);
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
