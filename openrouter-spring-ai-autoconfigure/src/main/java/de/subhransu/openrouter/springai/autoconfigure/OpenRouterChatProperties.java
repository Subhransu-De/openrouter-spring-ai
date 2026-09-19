package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterReasoningOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(OpenRouterChatProperties.CONFIG_PREFIX)
public class OpenRouterChatProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.chat";

	private @Nullable String model;

	private @Nullable List<String> models;

	/**
	 * Wire protocol used for chat requests. Chat Completions is the supported default;
	 * Responses is an experimental, explicitly selected compatibility mode.
	 */
	private @Nullable OpenRouterRequestMode requestMode = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;

	private @Nullable Double temperature;

	private @Nullable Double topP;

	private @Nullable Integer topK;

	private @Nullable Integer maxTokens;

	private @Nullable Integer maxCompletionTokens;

	private @Nullable List<String> stop;

	private @Nullable Integer seed;

	private @Nullable Double presencePenalty;

	private @Nullable Double frequencyPenalty;

	private @Nullable String user;

	/**
	 * Explicit output format. Takes precedence over output-schema.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterResponseFormat responseFormat;

	/**
	 * Portable JSON schema document. Leaves strict unset and uses the name response.
	 */
	private @Nullable String outputSchema;

	private @Nullable Map<String, @Nullable Object> extraBody;

	private @Nullable Map<String, @Nullable Object> providerExtraBody;

	/** Strictness for all function tools; unset preserves the provider default. */
	private @Nullable Boolean toolStrict;

	private @Nullable Boolean parallelToolCalls;

	private @Nullable String toolChoice;

	private @Nullable Double repetitionPenalty;

	private @Nullable Double minP;

	private @Nullable Double topA;

	private @Nullable String route;

	private @Nullable Boolean includeUsage;

	private @Nullable List<String> modalities;

	private @Nullable Map<String, @Nullable Object> imageConfig;

	private @Nullable OpenRouterServiceTier serviceTier;

	private @Nullable Map<String, @Nullable Object> metadata;

	private @Nullable OpenRouterProviderPreferences provider;

	private @Nullable OpenRouterReasoningOptions reasoning;

	private ToolCallAggregation toolCallAggregation = new ToolCallAggregation();

	/**
	 * Allow a custom tool calling manager whose provider-visible failure policy cannot be
	 * verified. Enabling this transfers all failure-result redaction responsibility to
	 * the application.
	 */
	private boolean allowUnsafeToolFailureResults;

	public OpenRouterChatOptions toOptions() {
		return OpenRouterChatOptions.builder()
			.model(this.model)
			.models(this.models)
			.requestMode(this.requestMode)
			.temperature(this.temperature)
			.topP(this.topP)
			.topK(this.topK)
			.maxTokens(this.maxTokens)
			.maxCompletionTokens(this.maxCompletionTokens)
			.stopSequences(this.stop)
			.seed(this.seed)
			.presencePenalty(this.presencePenalty)
			.frequencyPenalty(this.frequencyPenalty)
			.user(this.user)
			.responseFormat(this.responseFormat)
			.outputSchema(this.outputSchema)
			.extraBody(this.extraBody)
			.providerExtraBody(this.providerExtraBody)
			.toolStrict(this.toolStrict)
			.parallelToolCalls(this.parallelToolCalls)
			.toolChoice(this.toolChoice)
			.repetitionPenalty(this.repetitionPenalty)
			.minP(this.minP)
			.topA(this.topA)
			.route(this.route)
			.includeUsage(this.includeUsage)
			.modalities(this.modalities)
			.imageConfig(this.imageConfig)
			.serviceTier(this.serviceTier)
			.metadata(this.metadata)
			.provider(this.provider)
			.reasoning(this.reasoning)
			.build();
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable List<String> getModels() {
		return this.models;
	}

	public void setModels(@Nullable List<String> models) {
		this.models = models;
	}

	public @Nullable OpenRouterRequestMode getRequestMode() {
		return this.requestMode;
	}

	public void setRequestMode(@Nullable OpenRouterRequestMode requestMode) {
		this.requestMode = requestMode;
	}

	public boolean isAllowUnsafeToolFailureResults() {
		return this.allowUnsafeToolFailureResults;
	}

	public void setAllowUnsafeToolFailureResults(boolean allowUnsafeToolFailureResults) {
		this.allowUnsafeToolFailureResults = allowUnsafeToolFailureResults;
	}

	public @Nullable Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(@Nullable Double temperature) {
		this.temperature = temperature;
	}

	public @Nullable Double getTopP() {
		return this.topP;
	}

	public void setTopP(@Nullable Double topP) {
		this.topP = topP;
	}

	public @Nullable Integer getTopK() {
		return this.topK;
	}

	public void setTopK(@Nullable Integer topK) {
		this.topK = topK;
	}

	public @Nullable Integer getMaxTokens() {
		return this.maxTokens;
	}

	public void setMaxTokens(@Nullable Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	public @Nullable Integer getMaxCompletionTokens() {
		return this.maxCompletionTokens;
	}

	public void setMaxCompletionTokens(@Nullable Integer maxCompletionTokens) {
		this.maxCompletionTokens = maxCompletionTokens;
	}

	public @Nullable List<String> getStop() {
		return this.stop;
	}

	public void setStop(@Nullable List<String> stop) {
		this.stop = stop;
	}

	public @Nullable Integer getSeed() {
		return this.seed;
	}

	public void setSeed(@Nullable Integer seed) {
		this.seed = seed;
	}

	public @Nullable Double getPresencePenalty() {
		return this.presencePenalty;
	}

	public void setPresencePenalty(@Nullable Double presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	public @Nullable Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	public void setFrequencyPenalty(@Nullable Double frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public void setUser(@Nullable String user) {
		this.user = user;
	}

	public @Nullable OpenRouterResponseFormat getResponseFormat() {
		return this.responseFormat;
	}

	public void setResponseFormat(@Nullable OpenRouterResponseFormat responseFormat) {
		this.responseFormat = responseFormat;
	}

	public @Nullable Map<String, @Nullable Object> getProviderExtraBody() {
		return this.providerExtraBody;
	}

	public void setProviderExtraBody(@Nullable Map<String, @Nullable Object> providerExtraBody) {
		this.providerExtraBody = providerExtraBody;
	}

	public @Nullable Map<String, @Nullable Object> getExtraBody() {
		return this.extraBody;
	}

	public void setExtraBody(@Nullable Map<String, @Nullable Object> extraBody) {
		this.extraBody = extraBody;
	}

	public @Nullable String getOutputSchema() {
		return this.outputSchema;
	}

	public void setOutputSchema(@Nullable String outputSchema) {
		this.outputSchema = outputSchema;
	}

	public @Nullable Boolean getToolStrict() {
		return this.toolStrict;
	}

	public void setToolStrict(@Nullable Boolean toolStrict) {
		this.toolStrict = toolStrict;
	}

	public @Nullable Boolean getParallelToolCalls() {
		return this.parallelToolCalls;
	}

	public void setParallelToolCalls(@Nullable Boolean parallelToolCalls) {
		this.parallelToolCalls = parallelToolCalls;
	}

	public @Nullable String getToolChoice() {
		return this.toolChoice;
	}

	public void setToolChoice(@Nullable String toolChoice) {
		this.toolChoice = toolChoice;
	}

	public @Nullable Double getRepetitionPenalty() {
		return this.repetitionPenalty;
	}

	public void setRepetitionPenalty(@Nullable Double repetitionPenalty) {
		this.repetitionPenalty = repetitionPenalty;
	}

	public @Nullable Double getMinP() {
		return this.minP;
	}

	public void setMinP(@Nullable Double minP) {
		this.minP = minP;
	}

	public @Nullable Double getTopA() {
		return this.topA;
	}

	public void setTopA(@Nullable Double topA) {
		this.topA = topA;
	}

	public @Nullable String getRoute() {
		return this.route;
	}

	public void setRoute(@Nullable String route) {
		this.route = route;
	}

	public @Nullable Boolean getIncludeUsage() {
		return this.includeUsage;
	}

	public void setIncludeUsage(@Nullable Boolean includeUsage) {
		this.includeUsage = includeUsage;
	}

	public @Nullable List<String> getModalities() {
		return this.modalities;
	}

	public void setModalities(@Nullable List<String> modalities) {
		this.modalities = modalities;
	}

	public @Nullable Map<String, @Nullable Object> getImageConfig() {
		return this.imageConfig;
	}

	public void setImageConfig(@Nullable Map<String, @Nullable Object> imageConfig) {
		this.imageConfig = imageConfig;
	}

	public @Nullable OpenRouterServiceTier getServiceTier() {
		return this.serviceTier;
	}

	public void setServiceTier(@Nullable OpenRouterServiceTier serviceTier) {
		this.serviceTier = serviceTier;
	}

	public @Nullable Map<String, @Nullable Object> getMetadata() {
		return this.metadata;
	}

	public void setMetadata(@Nullable Map<String, @Nullable Object> metadata) {
		this.metadata = metadata;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(@Nullable OpenRouterProviderPreferences provider) {
		this.provider = provider;
	}

	public @Nullable OpenRouterReasoningOptions getReasoning() {
		return this.reasoning;
	}

	public void setReasoning(@Nullable OpenRouterReasoningOptions reasoning) {
		this.reasoning = reasoning;
	}

	public ToolCallAggregation getToolCallAggregation() {
		return this.toolCallAggregation;
	}

	public void setToolCallAggregation(ToolCallAggregation toolCallAggregation) {
		this.toolCallAggregation = toolCallAggregation;
	}

	public static class ToolCallAggregation {

		private DataSize maxSize = DataSize.ofMegabytes(1);

		private int maxChunks = 1024;

		private Duration maxDuration = Duration.ofMinutes(2);

		public DataSize getMaxSize() {
			return this.maxSize;
		}

		public void setMaxSize(DataSize maxSize) {
			this.maxSize = maxSize;
		}

		public int getMaxChunks() {
			return this.maxChunks;
		}

		public void setMaxChunks(int maxChunks) {
			this.maxChunks = maxChunks;
		}

		public Duration getMaxDuration() {
			return this.maxDuration;
		}

		public void setMaxDuration(Duration maxDuration) {
			this.maxDuration = maxDuration;
		}

	}

}
