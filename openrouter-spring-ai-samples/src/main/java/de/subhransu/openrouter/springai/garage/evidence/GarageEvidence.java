package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** Runtime evidence store shared by scenes, tool wrappers, transport capture, and reports. */
@Component
public final class GarageEvidence {

  private static final String REDACTED = "[REDACTED]";

  // Only explicitly named diagnostic fields cross the persistence boundary. In particular,
  // arbitrary map keys, payload strings, records and other objects are not evidence schemas.
  private static final Set<String> FIELDS = Set.of(
      "application", "status", "recordedCostUsd",
      "incompleteFeatures", "costsByOperation", "command", "scenes", "featureRegistry",
      "featureEvidence", "events", "observations", "meters", "transport", "results", "sweep",
      "passed", "failed", "id", "title", "featureId", "feature", "kind", "sceneId",
      "operationId", "requestMode", "requestModes", "sceneIds", "levels", "complete",
      "details", "errors", "error", "type", "cause", "evidenceOperations", "durationMillis",
      "durationNanos", "streaming", "returnDirect", "resultCharacters", "costUsd",
      "dimensions", "similarCosine", "unrelatedCosine", "imageBytes", "usage",
      "promptTokens", "completionTokens", "totalTokens", "count", "value", "measurements",
      "cachedTokens", "reasoningTokens", "reasoningObserved", "cost", "full", "offlineContracts", "capabilities",
      "imageSurface", "imageQuality", "modeStatuses", "modality", "inputCount", "width", "height",
      "name", "lowCardinality", "highCardinality", "tags", "statistic",
      "gen_ai.system", "gen_ai.operation.name", "gen_ai.request.model", "gen_ai.response.model",
      "error.type");

  private static final Set<String> LABELS = labels();
  private final Set<String> operationIds = ConcurrentHashMap.newKeySet();

  private static Set<String> labels() {
    Set<String> labels = new HashSet<>(Set.of(
        REDACTED, "garage", "passed", "failed", "PASSED", "FAILED", "success", "error",
        "covered", "not-executed", "incomplete", "unsupported-in-mode", "partial",
        "OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES", "embedding-models", "image-models",
        "operation.started", "feature.error", "transport.request", "observation.stopped",
        "tool.schema", "tool.attempted", "tool.succeeded", "tool.failed",
        "java.lang.IllegalStateException", "java.lang.IllegalArgumentException",
        "java.lang.RuntimeException", "java.io.IOException",
        "gen_ai.client.operation", "openrouter", "embeddings", "image_generation",
        "TIMER", "COUNT", "TOTAL_TIME", "MAX", "chat", "NONE", "SYNC", "STREAMING", "CHAT",
        "ALL", "text", "embedding", "vision", "image"));
    for (GarageFeature feature : GarageFeature.values()) {
      labels.addAll(List.of(feature.id(), feature.title(), feature.sceneId(), feature.kind().name()));
    }
    for (EvidenceLevel level : EvidenceLevel.values()) {
      labels.add(level.name());
    }
    return Set.copyOf(labels);
  }

  private final Map<String, FeatureEvidence> featureEvidence = new ConcurrentHashMap<>();
  private final Map<String, Double> operationCosts = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

  public String newOperation(String sceneId, String requestMode) {
    String operationId = (LABELS.contains(sceneId) ? sceneId : "operation") + "-" + UUID.randomUUID();
    this.operationIds.add(operationId);
    event(operationId, sceneId, "operation.started", Map.of("requestMode", requestMode));
    return operationId;
  }

  public void record(
      GarageFeature feature,
      String operationId,
      String requestMode,
      EvidenceLevel level,
      String key,
      Object value) {
    evidence(feature, operationId, requestMode).record(level, key, sanitize(value));
  }

  public void recordAll(
      List<GarageFeature> features,
      String operationId,
      String requestMode,
      EvidenceLevel level,
      String key,
      Object value) {
    features.forEach(
        feature -> record(feature, operationId, requestMode, level, key, value));
  }

  public void error(
      GarageFeature feature,
      String operationId,
      String requestMode,
      Throwable failure) {
    Map<String, Object> error = errorMap(failure);
    evidence(feature, operationId, requestMode).error(error);
    event(operationId, feature.sceneId(), "feature.error", error);
  }

  public void event(
      String operationId, String sceneId, String type, Map<String, ?> details) {
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("at", Instant.now().toString());
    event.put("operationId", operationId);
    event.put("sceneId", sceneId);
    event.put("type", type);
    event.put("details", sanitize(details));
    this.events.add(event);
  }

  public List<Map<String, Object>> featureSnapshot() {
    return this.featureEvidence.values().stream()
        .sorted(
            Comparator.comparing(
                    (FeatureEvidence item) ->
                        item.asMap().get("featureId").toString())
                .thenComparing(item -> item.asMap().get("operationId").toString()))
        .map(FeatureEvidence::asMap)
        .toList();
  }

  public List<Map<String, Object>> eventSnapshot() {
    return List.copyOf(this.events);
  }

  public String coverageStatus(GarageFeature feature, OpenRouterRequestMode mode) {
    List<Map<String, Object>> matching = featureSnapshot().stream()
        .filter(item -> feature.id().equals(item.get("featureId")))
        .filter(item -> mode.name().equals(item.get("requestMode")))
        .toList();
    if (matching.stream().anyMatch(item -> !((List<?>) item.get("errors")).isEmpty())) {
      return "failed";
    }
    if (!feature.supports(mode)) {
      return "unsupported-in-mode";
    }
    if (matching.isEmpty()) {
      return "not-executed";
    }
    return matching.stream().allMatch(item -> Boolean.TRUE.equals(item.get("complete")))
        ? "covered" : "incomplete";
  }

  public boolean operationPassed(String operationId) {
    List<FeatureEvidence> matching =
        this.featureEvidence.entrySet().stream()
            .filter(entry -> entry.getKey().startsWith(operationId + "|"))
            .map(Map.Entry::getValue)
            .toList();
    return !matching.isEmpty() && matching.stream().allMatch(FeatureEvidence::complete);
  }

  public void recordCost(String operationId, double costUsd) {
    if (operationId != null && Double.isFinite(costUsd) && costUsd > 0.0) {
      this.operationCosts.merge(operationId, costUsd, Double::sum);
    }
  }

  public double costFor(String operationId) {
    return this.operationCosts.getOrDefault(operationId, 0.0);
  }

  public double recordedCostUsd() {
    return this.operationCosts.values().stream().mapToDouble(Double::doubleValue).sum();
  }

  public Map<String, Double> costSnapshot() {
    return this.operationCosts.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .collect(
            LinkedHashMap::new,
            (values, entry) -> values.put(entry.getKey(), entry.getValue()),
            LinkedHashMap::putAll);
  }

  public void reset() {
    this.featureEvidence.clear();
    this.operationCosts.clear();
    this.events.clear();
    this.operationIds.clear();
  }

  public Object sanitizeForEvidence(Object value) {
    return sanitize(value);
  }

  private FeatureEvidence evidence(
      GarageFeature feature, String operationId, String requestMode) {
    String key = operationId + "|" + feature.id() + "|" + requestMode;
    return this.featureEvidence.computeIfAbsent(
        key,
        ignored -> new FeatureEvidence(feature, operationId, feature.sceneId(), requestMode));
  }

  private Map<String, Object> errorMap(Throwable failure) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("type", failure.getClass().getName());
    Throwable cause = failure.getCause();
    if (cause != null && cause != failure) {
      error.put("cause", cause.getClass().getName());
    }
    return error;
  }

  private Object sanitize(Object value) {
    if (value == null || value instanceof Boolean || value instanceof Integer
        || value instanceof Long || value instanceof Double || value instanceof Float
        || value instanceof Short || value instanceof Byte) {
      return value;
    }
    if (value instanceof String string) {
      return LABELS.contains(string) || this.operationIds.contains(string) ? string : REDACTED;
    }
    if (value instanceof Enum<?> enumeration) {
      return sanitize(enumeration.name());
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sanitized = new LinkedHashMap<>();
      map.forEach((key, item) -> {
        if (key instanceof String name
            && (FIELDS.contains(name) || this.operationIds.contains(name))) {
          sanitized.put(name, sanitize(item));
        }
      });
      return sanitized;
    }
    if (value instanceof Collection<?> collection) {
      List<Object> sanitized = new ArrayList<>();
      collection.forEach(item -> sanitized.add(sanitize(item)));
      return sanitized;
    }
    if (value instanceof Object[] array) {
      List<Object> sanitized = new ArrayList<>();
      for (Object item : array) {
        sanitized.add(sanitize(item));
      }
      return sanitized;
    }
    // Do not invoke serializers or toString() on unknown objects (including records).
    return REDACTED;
  }
}
