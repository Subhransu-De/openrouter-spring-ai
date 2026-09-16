package de.subhransu.openrouter.springai.autoconfigure;

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

	private String model;

	private List<String> models;

	/**
	 * Wire protocol used for chat requests. Chat Completions is the supported default;
	 * Responses is an experimental, explicitly selected compatibility mode.
	 */
	private OpenRouterRequestMode requestMode = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;

	private Double temperature;

	private Double topP;

	private Integer topK;

	private Integer maxTokens;

	private Integer maxCompletionTokens;

	private List<String> stop;

	private Integer seed;

	private Double presencePenalty;

	private Double frequencyPenalty;

	private String user;

	/**
	 * Explicit output format. Takes precedence over output-schema.
	 */
	@NestedConfigurationProperty
	private OpenRouterResponseFormat responseFormat;

	/**
	 * Portable JSON schema document. Leaves strict unset and uses the name response.
	 */
	private String outputSchema;

	private Boolean parallelToolCalls;

	private String toolChoice;

	private Double repetitionPenalty;

	private Double minP;

	private Double topA;

	private String route;

	private Boolean includeUsage;

	private List<String> modalities;

	private Map<String, Object> imageConfig;

	private OpenRouterServiceTier serviceTier;

	private Map<String, Object> metadata;

	private OpenRouterProviderPreferences provider;

	private OpenRouterReasoningOptions reasoning;

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

	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public List<String> getModels() {
		return this.models;
	}

	public void setModels(List<String> models) {
		this.models = models;
	}

	public OpenRouterRequestMode getRequestMode() {
		return this.requestMode;
	}

	public void setRequestMode(OpenRouterRequestMode requestMode) {
		this.requestMode = requestMode;
	}

	public boolean isAllowUnsafeToolFailureResults() {
		return this.allowUnsafeToolFailureResults;
	}

	public void setAllowUnsafeToolFailureResults(boolean allowUnsafeToolFailureResults) {
		this.allowUnsafeToolFailureResults = allowUnsafeToolFailureResults;
	}

	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	public Double getTopP() {
		return this.topP;
	}

	public void setTopP(Double topP) {
		this.topP = topP;
	}

	public Integer getTopK() {
		return this.topK;
	}

	public void setTopK(Integer topK) {
		this.topK = topK;
	}

	public Integer getMaxTokens() {
		return this.maxTokens;
	}

	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	public Integer getMaxCompletionTokens() {
		return this.maxCompletionTokens;
	}

	public void setMaxCompletionTokens(Integer maxCompletionTokens) {
		this.maxCompletionTokens = maxCompletionTokens;
	}

	public List<String> getStop() {
		return this.stop;
	}

	public void setStop(List<String> stop) {
		this.stop = stop;
	}

	public Integer getSeed() {
		return this.seed;
	}

	public void setSeed(Integer seed) {
		this.seed = seed;
	}

	public Double getPresencePenalty() {
		return this.presencePenalty;
	}

	public void setPresencePenalty(Double presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	public Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	public void setFrequencyPenalty(Double frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	public String getUser() {
		return this.user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public OpenRouterResponseFormat getResponseFormat() {
		return this.responseFormat;
	}

	public void setResponseFormat(OpenRouterResponseFormat responseFormat) {
		this.responseFormat = responseFormat;
	}

	public String getOutputSchema() {
		return this.outputSchema;
	}

	public void setOutputSchema(String outputSchema) {
		this.outputSchema = outputSchema;
	}

	public Boolean getParallelToolCalls() {
		return this.parallelToolCalls;
	}

	public void setParallelToolCalls(Boolean parallelToolCalls) {
		this.parallelToolCalls = parallelToolCalls;
	}

	public String getToolChoice() {
		return this.toolChoice;
	}

	public void setToolChoice(String toolChoice) {
		this.toolChoice = toolChoice;
	}

	public Double getRepetitionPenalty() {
		return this.repetitionPenalty;
	}

	public void setRepetitionPenalty(Double repetitionPenalty) {
		this.repetitionPenalty = repetitionPenalty;
	}

	public Double getMinP() {
		return this.minP;
	}

	public void setMinP(Double minP) {
		this.minP = minP;
	}

	public Double getTopA() {
		return this.topA;
	}

	public void setTopA(Double topA) {
		this.topA = topA;
	}

	public String getRoute() {
		return this.route;
	}

	public void setRoute(String route) {
		this.route = route;
	}

	public Boolean getIncludeUsage() {
		return this.includeUsage;
	}

	public void setIncludeUsage(Boolean includeUsage) {
		this.includeUsage = includeUsage;
	}

	public List<String> getModalities() {
		return this.modalities;
	}

	public void setModalities(List<String> modalities) {
		this.modalities = modalities;
	}

	public Map<String, Object> getImageConfig() {
		return this.imageConfig;
	}

	public void setImageConfig(Map<String, Object> imageConfig) {
		this.imageConfig = imageConfig;
	}

	public OpenRouterServiceTier getServiceTier() {
		return this.serviceTier;
	}

	public void setServiceTier(OpenRouterServiceTier serviceTier) {
		this.serviceTier = serviceTier;
	}

	public Map<String, Object> getMetadata() {
		return this.metadata;
	}

	public void setMetadata(Map<String, Object> metadata) {
		this.metadata = metadata;
	}

	public OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(OpenRouterProviderPreferences provider) {
		this.provider = provider;
	}

	public OpenRouterReasoningOptions getReasoning() {
		return this.reasoning;
	}

	public void setReasoning(OpenRouterReasoningOptions reasoning) {
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
