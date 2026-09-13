package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterReasoningOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import de.subhransu.openrouter.springai.chat.OpenRouterServiceTier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Builds all Garage request profiles and produces evidence from the effective options. */
@Component
public final class GarageOptionsFactory {

  private final GarageProperties properties;

  public GarageOptionsFactory(GarageProperties properties) {
    this.properties = properties;
  }

  public OpenRouterChatOptions serviceStory(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      List<String> fallbackModels,
      String topic,
      List<ToolCallback> callbacks) {
    return common(operationId, "service-story", requestMode, model, fallbackModels, topic)
        .parallelToolCalls(false)
        .toolChoice("auto")
        .toolCallbacks(callbacks)
        .build();
  }

  public OpenRouterChatOptions plain(
      String operationId,
      String sceneId,
      OpenRouterRequestMode requestMode,
      String model,
      String topic) {
    return common(operationId, sceneId, requestMode, model, List.of(), topic).build();
  }

  public OpenRouterChatOptions streamingDispatch(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      String topic,
      ToolCallback callback) {
    return common(operationId, "streaming-dispatch", requestMode, model, List.of(), topic)
        .maxCompletionTokens(this.properties.getMaxCompletionTokens())
        .parallelToolCalls(false)
        .toolChoice("required")
        .toolCallbacks(callback)
        .build();
  }

  public OpenRouterChatOptions expressInvoice(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      String topic,
      ToolCallback callback) {
    return common(operationId, "express-invoice", requestMode, model, List.of(), topic)
        .toolChoice("required")
        .parallelToolCalls(false)
        .toolCallbacks(callback)
        .build();
  }

  public OpenRouterChatOptions digitalInspection(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      String topic,
      String schema,
      boolean outputSchemaVariant) {
    OpenRouterChatOptions.Builder builder =
        common(operationId, "digital-inspection", requestMode, model, List.of(), topic)
            .responseFormat(
                OpenRouterResponseFormat.jsonSchema("service_inspection", true, schema));
    if (outputSchemaVariant) {
      builder.outputSchema(schema);
    }
    return builder.build();
  }

  public OpenRouterChatOptions routingLane(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      List<String> fallbackModels,
      String topic) {
    return common(operationId, "routing-lane", requestMode, model, fallbackModels, topic)
        .provider(fullProviderPreferences())
        .route(this.properties.getRoute())
        .serviceTier(
            this.properties.getServiceTier() == OpenRouterServiceTier.AUTO
                ? OpenRouterServiceTier.DEFAULT
                : this.properties.getServiceTier())
        .build();
  }

  public OpenRouterChatOptions dynoTuning(
      String operationId,
      OpenRouterRequestMode requestMode,
      String model,
      String topic,
      List<ToolCallback> callbacks) {
    boolean responses = requestMode == OpenRouterRequestMode.OPENAI_RESPONSES;
    return common(operationId, "dyno-tuning", requestMode, model, List.of(), topic)
        .topP(this.properties.getTopP())
        .topK(this.properties.getTopK())
        .maxTokens(this.properties.getMaxTokens())
        .stopSequences(responses ? null : this.properties.getStop())
        .seed(responses ? null : this.properties.getSeed())
        .presencePenalty(this.properties.getPresencePenalty())
        .frequencyPenalty(this.properties.getFrequencyPenalty())
        .repetitionPenalty(responses ? null : this.properties.getRepetitionPenalty())
        .minP(responses ? null : this.properties.getMinP())
        .topA(responses ? null : this.properties.getTopA())
        .toolCallbacks(callbacks)
        .toolContext(
            Map.of(
                "garage.jobId", operationId,
                "garage.tenant", "sample-shop",
                "garage.topic", "[REDACTED]"))
        .build();
  }

  public Map<String, Object> snapshot(OpenRouterChatOptions options) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("model", options.getModel());
    values.put("models", options.getModels());
    values.put("requestMode", options.getRequestMode());
    values.put("temperature", options.getTemperature());
    values.put("topP", options.getTopP());
    values.put("topK", options.getTopK());
    values.put("maxTokens", options.getMaxTokens());
    values.put("maxCompletionTokens", options.getMaxCompletionTokens());
    values.put("stop", options.getStopSequences());
    values.put("seed", options.getSeed());
    values.put("presencePenalty", options.getPresencePenalty());
    values.put("frequencyPenalty", options.getFrequencyPenalty());
    values.put("repetitionPenalty", options.getRepetitionPenalty());
    values.put("minP", options.getMinP());
    values.put("topA", options.getTopA());
    values.put("user", options.getUser());
    values.put("parallelToolCalls", options.getParallelToolCalls());
    values.put("toolChoice", options.getToolChoice());
    values.put("provider", providerSnapshot(options.getProvider()));
    values.put("reasoning", reasoningSnapshot(options.getReasoning()));
    values.put("serviceTier", options.getServiceTier());
    values.put("metadata", options.getMetadata());
    values.put("route", options.getRoute());
    values.put("includeUsage", options.getIncludeUsage());
    values.put("responseFormat", options.getResponseFormat());
    values.put("outputSchema", options.getOutputSchema());
    values.put(
        "tools",
        options.getToolCallbacks() != null
            ? options.getToolCallbacks().stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList()
            : List.of());
    values.put("toolContext", options.getToolContext());
    values.put("unsupportedInMode", unsupportedInMode(options));
    return values;
  }

  public List<String> unsupportedInMode(OpenRouterChatOptions options) {
    if (options.getRequestMode() != OpenRouterRequestMode.OPENAI_RESPONSES) {
      return List.of();
    }
    List<String> unsupported = new ArrayList<>();
    addIfPresent(unsupported, "stop", options.getStopSequences());
    addIfPresent(unsupported, "seed", options.getSeed());
    addIfPresent(unsupported, "repetitionPenalty", options.getRepetitionPenalty());
    addIfPresent(unsupported, "minP", options.getMinP());
    addIfPresent(unsupported, "topA", options.getTopA());
    addIfPresent(unsupported, "includeUsage", options.getIncludeUsage());
    return List.copyOf(unsupported);
  }

  private OpenRouterChatOptions.Builder common(
      String operationId,
      String sceneId,
      OpenRouterRequestMode requestMode,
      String model,
      List<String> fallbackModels,
      String topic) {
    Objects.requireNonNull(topic, "topic");
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("application", "garage");
    metadata.put("sceneId", sceneId);
    metadata.put("operationId", operationId);
    metadata.put("requestMode", requestMode.name());
    metadata.put("topic", "[REDACTED]");
    OpenRouterChatOptions.Builder builder =
        OpenRouterChatOptions.builder()
            .model(model)
            .requestMode(requestMode)
            .temperature(this.properties.getTemperature())
            .maxCompletionTokens(this.properties.getMaxCompletionTokens())
            .includeUsage(requestMode == OpenRouterRequestMode.OPENAI_RESPONSES ? null : true)
            .reasoning(reasoningOptions())
            .provider(serviceProviderPreferences())
            .serviceTier(this.properties.getServiceTier())
            .metadata(metadata)
            .user(this.properties.getUser());
    if (fallbackModels != null && !fallbackModels.isEmpty()) {
      builder.models(fallbackModels);
    }
    return builder;
  }

  private OpenRouterProviderPreferences serviceProviderPreferences() {
    if (!this.properties.isProviderPreferencesEnabled()) {
      return null;
    }
    return new OpenRouterProviderPreferences(
        this.properties.getProviderAllowFallbacks(),
        this.properties.getProviderRequireParameters(),
        null,
        null,
        null,
        null,
        this.properties.getProviderSort());
  }

  private OpenRouterProviderPreferences fullProviderPreferences() {
    return new OpenRouterProviderPreferences(
        this.properties.getProviderAllowFallbacks(),
        this.properties.getProviderRequireParameters(),
        this.properties.getProviderDataCollection(),
        this.properties.getProviderOrder(),
        this.properties.getProviderIgnore(),
        this.properties.getProviderQuantizations(),
        this.properties.getProviderSort());
  }

  private OpenRouterReasoningOptions reasoningOptions() {
    if (!this.properties.isReasoningEnabled()) {
      return null;
    }
    Integer maxTokens = this.properties.getReasoningMaxTokens();
    return new OpenRouterReasoningOptions(
        maxTokens == null ? this.properties.getReasoningEffort() : null,
        maxTokens,
        this.properties.isReasoningExclude(),
        true);
  }

  private Map<String, Object> providerSnapshot(OpenRouterProviderPreferences provider) {
    if (provider == null) {
      return Map.of();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("allowFallbacks", provider.allowFallbacks());
    values.put("requireParameters", provider.requireParameters());
    values.put("dataCollection", provider.dataCollection());
    values.put("order", provider.order());
    values.put("ignore", provider.ignore());
    values.put("quantizations", provider.quantizations());
    values.put("sort", provider.sort());
    return values;
  }

  private Map<String, Object> reasoningSnapshot(OpenRouterReasoningOptions reasoning) {
    if (reasoning == null) {
      return Map.of();
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("effort", reasoning.effort());
    values.put("maxTokens", reasoning.maxTokens());
    values.put("exclude", reasoning.exclude());
    values.put("enabled", reasoning.enabled());
    return values;
  }

  private void addIfPresent(List<String> unsupported, String option, Object value) {
    if (value != null && (!(value instanceof List<?> list) || !list.isEmpty())) {
      unsupported.add(option);
    }
  }
}
