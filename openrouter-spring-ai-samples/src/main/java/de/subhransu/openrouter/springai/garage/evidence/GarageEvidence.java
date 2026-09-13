package de.subhransu.openrouter.springai.garage.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Runtime evidence store shared by scenes, tool wrappers, transport capture, and reports. */
@Component
public final class GarageEvidence {

  private static final Pattern BEARER =
      Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");
  private static final Pattern API_KEY =
      Pattern.compile("(?i)(api[-_ ]?key[\\\"'=:\\s]+)[^,;\\s\\\"]+");

  private final Map<String, FeatureEvidence> featureEvidence = new ConcurrentHashMap<>();
  private final Map<String, Double> operationCosts = new ConcurrentHashMap<>();
  private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();

  public String newOperation(String sceneId, String requestMode) {
    String operationId = sceneId + "-" + UUID.randomUUID();
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
  }

  public Object sanitizeForEvidence(Object value) {
    return sanitize(value);
  }

  private FeatureEvidence evidence(
      GarageFeature feature, String operationId, String requestMode) {
    String key = operationId + "|" + feature.id();
    return this.featureEvidence.computeIfAbsent(
        key,
        ignored -> new FeatureEvidence(feature, operationId, feature.sceneId(), requestMode));
  }

  private Map<String, Object> errorMap(Throwable failure) {
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("type", failure.getClass().getName());
    error.put("message", redact(failure.getMessage()));
    Throwable cause = failure.getCause();
    if (cause != null && cause != failure) {
      error.put("cause", cause.getClass().getName());
      error.put("causeMessage", redact(cause.getMessage()));
    }
    return error;
  }

  private Object sanitize(Object value) {
    if (value instanceof String string) {
      return redact(string);
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sanitized = new LinkedHashMap<>();
      map.forEach(
          (key, item) -> {
            String name = String.valueOf(key);
            sanitized.put(
                name,
                isSensitiveName(name) ? "[REDACTED]" : sanitize(item));
          });
      return sanitized;
    }
    if (value instanceof Iterable<?> iterable) {
      List<Object> sanitized = new ArrayList<>();
      iterable.forEach(item -> sanitized.add(sanitize(item)));
      return sanitized;
    }
    return value;
  }

  private boolean isSensitiveName(String name) {
    String normalized = name.toLowerCase();
    return normalized.contains("authorization")
        || normalized.contains("api-key")
        || normalized.contains("apikey")
        || normalized.equals("prompt")
        || normalized.equals("contents")
        || normalized.equals("topic")
        || normalized.endsWith(".topic");
  }

  private String redact(String value) {
    if (value == null) {
      return null;
    }
    String redacted = BEARER.matcher(value).replaceAll("Bearer [REDACTED]");
    return API_KEY.matcher(redacted).replaceAll("$1[REDACTED]");
  }
}
