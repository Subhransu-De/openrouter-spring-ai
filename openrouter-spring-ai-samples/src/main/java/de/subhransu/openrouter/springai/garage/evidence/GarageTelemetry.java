package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import org.springframework.ai.chat.observation.ChatModelObservationContext;

/** Records completed Spring AI observations and their Micrometer timer measurements. */
public final class GarageTelemetry implements ObservationHandler<Observation.Context> {

  private static final String START_NANOS = GarageTelemetry.class.getName() + ".startNanos";
  private static final String START_INSTANT = GarageTelemetry.class.getName() + ".startInstant";
  private static final long OBSERVATION_POLL_INTERVAL_NANOS =
      Duration.ofMillis(5).toNanos();

  private final SimpleMeterRegistry meterRegistry;
  private final GarageEvidence evidence;
  private final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();
  private final Map<String, Integer> startedObservations = new ConcurrentHashMap<>();

  public GarageTelemetry(SimpleMeterRegistry meterRegistry, GarageEvidence evidence) {
    this.meterRegistry = meterRegistry;
    this.evidence = evidence;
  }

  @Override
  public void onStart(Observation.Context context) {
    context.put(START_NANOS, System.nanoTime());
    context.put(START_INSTANT, Instant.now().toString());
    if (context instanceof ChatModelObservationContext chatContext
        && chatContext.getRequest().getOptions() instanceof OpenRouterChatOptions options) {
      String operationId = value(options.getMetadata(), "operationId");
      if (operationId != null) {
        this.startedObservations.merge(operationId, 1, Integer::sum);
      }
    }
  }

  @Override
  public void onStop(Observation.Context context) {
    long started = context.getOrDefault(START_NANOS, System.nanoTime());
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("name", context.getName());
    observation.put("contextualName", context.getContextualName());
    observation.put("startedAt", context.getOrDefault(START_INSTANT, Instant.now().toString()));
    observation.put("endedAt", Instant.now().toString());
    observation.put("durationNanos", Math.max(0, System.nanoTime() - started));
    observation.put("error", error(context.getError()));
    observation.put("lowCardinality", keys(context.getLowCardinalityKeyValues()));
    observation.put("highCardinality", keys(context.getHighCardinalityKeyValues()));

    String operationId = null;
    String sceneId = null;
    if (context instanceof ChatModelObservationContext chatContext) {
      observation.put("streaming", chatContext.isStreaming());
      if (chatContext.getRequest().getOptions() instanceof OpenRouterChatOptions options) {
        Map<String, Object> metadata = options.getMetadata();
        operationId = value(metadata, "operationId");
        sceneId = value(metadata, "sceneId");
        observation.put("operationId", operationId);
        observation.put("sceneId", sceneId);
        observation.put("openRouterOptions", optionEvidence(options));
      }
      if (chatContext.getResponse() != null) {
        double costUsd = GarageCosts.usage(chatContext.getResponse().getMetadata().getUsage());
        observation.put("costUsd", costUsd);
        this.evidence.recordCost(operationId, costUsd);
      }
    }
    if (operationId != null) {
      this.evidence.event(
          operationId,
          sceneId != null ? sceneId : "unknown",
          "observation.stopped",
          observation);
    }
    // Publish completion only after both cost and event evidence have been recorded.
    this.observations.add(observation);
  }

  @Override
  public boolean supportsContext(Observation.Context context) {
    return context instanceof ChatModelObservationContext;
  }

  public List<Map<String, Object>> observationSnapshot() {
    return List.copyOf(this.observations);
  }

  public List<Map<String, Object>> observationsFor(String operationId) {
    return this.observations.stream()
        .filter(item -> operationId.equals(item.get("operationId")))
        .toList();
  }

  /**
   * After an operation's streams terminate, waits for every started model observation,
   * including tool-loop rounds, to publish its evidence. Incomplete evidence fails closed.
   */
  public List<Map<String, Object>> awaitObservationsFor(
      String operationId, Duration timeout) {
    if (timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must not be negative");
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    List<Map<String, Object>> matching = observationsFor(operationId);
    while (matching.size() < Math.max(1, this.startedObservations.getOrDefault(operationId, 0))) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) {
        throw new IllegalStateException("Timed out waiting for all observations for " + operationId);
      }
      if (Thread.currentThread().isInterrupted()) {
        throw new IllegalStateException("Interrupted while waiting for observations for " + operationId);
      }
      LockSupport.parkNanos(Math.min(remaining, OBSERVATION_POLL_INTERVAL_NANOS));
      matching = observationsFor(operationId);
    }
    return matching;
  }

  public List<Map<String, Object>> meterSnapshot() {
    List<Map<String, Object>> meters = new ArrayList<>();
    for (Meter meter : this.meterRegistry.getMeters()) {
      if (!"gen_ai.client.operation".equals(meter.getId().getName())) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", meter.getId().getName());
      item.put("type", meter.getId().getType().name());
      item.put(
          "tags",
          meter.getId().getTags().stream()
              .collect(
                  LinkedHashMap::new,
                  (map, tag) -> map.put(tag.getKey(), tag.getValue()),
                  LinkedHashMap::putAll));
      List<Map<String, Object>> measurements = new ArrayList<>();
      meter.measure()
          .forEach(
              measurement ->
                  measurements.add(
                      Map.of(
                          "statistic", measurement.getStatistic().name(),
                          "value", measurement.getValue())));
      item.put("measurements", measurements);
      meters.add(item);
    }
    return meters;
  }

  public void reset() {
    this.observations.clear();
    this.startedObservations.clear();
    this.meterRegistry.clear();
  }

  private Map<String, String> keys(Iterable<KeyValue> keyValues) {
    Map<String, String> result = new LinkedHashMap<>();
    keyValues.forEach(keyValue -> result.put(keyValue.getKey(), keyValue.getValue()));
    return result;
  }

  private Map<String, Object> error(Throwable failure) {
    if (failure == null) {
      return Map.of();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", failure.getClass().getName());
    result.put("message", failure.getMessage());
    return result;
  }

  private Map<String, Object> optionEvidence(OpenRouterChatOptions options) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("requestMode", options.getRequestMode());
    values.put("model", options.getModel());
    values.put("models", options.getModels());
    values.put("route", options.getRoute());
    values.put("serviceTier", options.getServiceTier());
    values.put("provider", options.getProvider());
    values.put("reasoning", options.getReasoning());
    values.put("user", options.getUser());
    return values;
  }

  private String value(Map<String, Object> values, String key) {
    Object value = values != null ? values.get(key) : null;
    return value != null ? value.toString() : null;
  }
}
