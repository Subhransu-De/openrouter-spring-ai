package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("garage")
public class GarageProperties {

  private String foremanModel = "openai/gpt-oss-120b";
  private String specialistModel = "openai/gpt-oss-20b";
  private String embeddingModel = "openai/text-embedding-3-small";
  private String visionModel = "google/gemini-2.5-flash";
  private String imageModel = "google/gemini-2.5-flash-image";
  private List<String> fallbackModels = new ArrayList<>(List.of("openai/gpt-oss-20b"));
  private String topic = "a 1972 pickup with overheating, rough idle, and brake vibration";
  private Path outputDir = Path.of("outputs");
  private boolean stream;
  private boolean full;
  private List<OpenRouterRequestMode> requestModes =
      new ArrayList<>(List.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS));
  private Integer maxCompletionTokens = 900;
  private Integer specialistMaxCompletionTokens = 500;
  private Double temperature = 0.2;
  private Double topP = 0.85;
  private Integer topK = 40;
  private Integer maxTokens = 700;
  private List<String> stop = new ArrayList<>(List.of("GARAGE_END"));
  private Integer seed = 1972;
  private Double presencePenalty = 0.1;
  private Double frequencyPenalty = 0.1;
  private Double repetitionPenalty = 1.05;
  private Double minP = 0.05;
  private Double topA = 0.1;
  private String user = "garage-demo";
  private String route = "fallback";
  private boolean reasoningEnabled = true;
  private String reasoningEffort = "medium";
  private Integer reasoningMaxTokens;
  private boolean reasoningExclude;
  private boolean providerPreferencesEnabled = true;
  private Boolean providerAllowFallbacks = true;
  private Boolean providerRequireParameters = false;
  private String providerSort = "throughput";
  private String providerDataCollection = "allow";
  private List<String> providerOrder = new ArrayList<>(List.of("OpenAI"));
  private List<String> providerIgnore = new ArrayList<>(List.of("Fireworks"));
  private List<String> providerQuantizations =
      new ArrayList<>(List.of("bf16", "fp16", "fp8"));
  private OpenRouterServiceTier serviceTier = OpenRouterServiceTier.AUTO;

  public String getForemanModel() {
    return this.foremanModel;
  }

  public void setForemanModel(String foremanModel) {
    this.foremanModel = foremanModel;
  }

  public String getSpecialistModel() {
    return this.specialistModel;
  }

  public void setSpecialistModel(String specialistModel) {
    this.specialistModel = specialistModel;
  }

  public List<String> getFallbackModels() {
    return this.fallbackModels;
  }

  public void setFallbackModels(List<String> fallbackModels) {
    this.fallbackModels = fallbackModels;
  }

  public String getEmbeddingModel() {
    return this.embeddingModel;
  }

  public void setEmbeddingModel(String embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public String getVisionModel() {
    return this.visionModel;
  }

  public void setVisionModel(String visionModel) {
    this.visionModel = visionModel;
  }

  public String getImageModel() {
    return this.imageModel;
  }

  public void setImageModel(String imageModel) {
    this.imageModel = imageModel;
  }

  public String getTopic() {
    return this.topic;
  }

  public void setTopic(String topic) {
    this.topic = topic;
  }

  public Path getOutputDir() {
    return this.outputDir;
  }

  public void setOutputDir(Path outputDir) {
    this.outputDir = outputDir;
  }

  public boolean isStream() {
    return this.stream;
  }

  public void setStream(boolean stream) {
    this.stream = stream;
  }

  public boolean isFull() {
    return this.full;
  }

  public void setFull(boolean full) {
    this.full = full;
  }

  public List<OpenRouterRequestMode> getRequestModes() {
    return this.requestModes;
  }

  public void setRequestModes(List<OpenRouterRequestMode> requestModes) {
    this.requestModes = requestModes;
  }

  public Integer getMaxCompletionTokens() {
    return this.maxCompletionTokens;
  }

  public void setMaxCompletionTokens(Integer maxCompletionTokens) {
    this.maxCompletionTokens = maxCompletionTokens;
  }

  public Integer getSpecialistMaxCompletionTokens() {
    return this.specialistMaxCompletionTokens;
  }

  public void setSpecialistMaxCompletionTokens(Integer specialistMaxCompletionTokens) {
    this.specialistMaxCompletionTokens = specialistMaxCompletionTokens;
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

  public String getUser() {
    return this.user;
  }

  public void setUser(String user) {
    this.user = user;
  }

  public String getRoute() {
    return this.route;
  }

  public void setRoute(String route) {
    this.route = route;
  }

  public boolean isReasoningEnabled() {
    return this.reasoningEnabled;
  }

  public void setReasoningEnabled(boolean reasoningEnabled) {
    this.reasoningEnabled = reasoningEnabled;
  }

  public String getReasoningEffort() {
    return this.reasoningEffort;
  }

  public void setReasoningEffort(String reasoningEffort) {
    this.reasoningEffort = reasoningEffort;
  }

  public Integer getReasoningMaxTokens() {
    return this.reasoningMaxTokens;
  }

  public void setReasoningMaxTokens(Integer reasoningMaxTokens) {
    this.reasoningMaxTokens = reasoningMaxTokens;
  }

  public boolean isReasoningExclude() {
    return this.reasoningExclude;
  }

  public void setReasoningExclude(boolean reasoningExclude) {
    this.reasoningExclude = reasoningExclude;
  }

  public boolean isProviderPreferencesEnabled() {
    return this.providerPreferencesEnabled;
  }

  public void setProviderPreferencesEnabled(boolean providerPreferencesEnabled) {
    this.providerPreferencesEnabled = providerPreferencesEnabled;
  }

  public Boolean getProviderAllowFallbacks() {
    return this.providerAllowFallbacks;
  }

  public void setProviderAllowFallbacks(Boolean providerAllowFallbacks) {
    this.providerAllowFallbacks = providerAllowFallbacks;
  }

  public Boolean getProviderRequireParameters() {
    return this.providerRequireParameters;
  }

  public void setProviderRequireParameters(Boolean providerRequireParameters) {
    this.providerRequireParameters = providerRequireParameters;
  }

  public String getProviderSort() {
    return this.providerSort;
  }

  public void setProviderSort(String providerSort) {
    this.providerSort = providerSort;
  }

  public String getProviderDataCollection() {
    return this.providerDataCollection;
  }

  public void setProviderDataCollection(String providerDataCollection) {
    this.providerDataCollection = providerDataCollection;
  }

  public List<String> getProviderOrder() {
    return this.providerOrder;
  }

  public void setProviderOrder(List<String> providerOrder) {
    this.providerOrder = providerOrder;
  }

  public List<String> getProviderIgnore() {
    return this.providerIgnore;
  }

  public void setProviderIgnore(List<String> providerIgnore) {
    this.providerIgnore = providerIgnore;
  }

  public List<String> getProviderQuantizations() {
    return this.providerQuantizations;
  }

  public void setProviderQuantizations(List<String> providerQuantizations) {
    this.providerQuantizations = providerQuantizations;
  }

  public OpenRouterServiceTier getServiceTier() {
    return this.serviceTier;
  }

  public void setServiceTier(OpenRouterServiceTier serviceTier) {
    this.serviceTier = serviceTier;
  }
}
