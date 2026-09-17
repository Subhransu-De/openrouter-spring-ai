package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageResponses;
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
import java.util.function.Supplier;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.image.observation.ImageModelObservationContext;

/** Records completed Spring AI observations and their Micrometer timer measurements. */
public final class GarageTelemetry implements ObservationHandler<Observation.Context> {

  private static final String START_NANOS = GarageTelemetry.class.getName() + ".startNanos";
  private static final String START_INSTANT = GarageTelemetry.class.getName() + ".startInstant";
  private static final long OBSERVATION_POLL_INTERVAL_NANOS =
      Duration.ofMillis(5).toNanos();

  private static final String CORRELATION = GarageTelemetry.class.getName() + ".correlation";
  private final ThreadLocal<Map<String, Object>> currentOperation = new ThreadLocal<>();

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
    Map<String, Object> correlation = this.currentOperation.get();
    if (context instanceof ChatModelObservationContext chatContext
        && chatContext.getRequest().getOptions() instanceof OpenRouterChatOptions options
        && value(options.getMetadata(), "operationId") != null) {
      correlation = options.getMetadata();
    }
    if (correlation != null) {
      Map<String, Object> identity = new LinkedHashMap<>();
      identity.put("operationId", value(correlation, "operationId"));
      identity.put("sceneId", value(correlation, "sceneId"));
      context.put(CORRELATION, identity);
      String operationId = value(identity, "operationId");
      if (operationId != null) {
        this.startedObservations.merge(operationId, 1, Integer::sum);
      }
    }
  }

  /** Runs and subscribes model calls on the calling thread; completion may happen elsewhere. */
  public <T> T observeOperation(String operationId, String sceneId, Supplier<T> action) {
    Map<String, Object> previous = this.currentOperation.get();
    this.currentOperation.set(Map.of("operationId", operationId, "sceneId", sceneId));
    try {
      return action.get();
    } finally {
      if (previous == null) {
        this.currentOperation.remove();
      } else {
        this.currentOperation.set(previous);
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

    Map<String, Object> correlation = context.getOrDefault(CORRELATION, Map.of());
    String operationId = value(correlation, "operationId");
    String sceneId = value(correlation, "sceneId");
    observation.putAll(correlation);
    Usage usage = null;
    if (context instanceof ChatModelObservationContext chatContext) {
      observation.put("modality", "chat");
      observation.put("streaming", chatContext.isStreaming());
      if (chatContext.getRequest().getOptions() instanceof OpenRouterChatOptions options) {
        observation.put("openRouterOptions", optionEvidence(options));
      }
      if (chatContext.getResponse() != null) {
        usage = chatContext.getResponse().getMetadata().getUsage();
      }
    } else if (context instanceof EmbeddingModelObservationContext embeddingContext) {
      observation.put("modality", "embedding");
      observation.put("inputCount", embeddingContext.getRequest().getInstructions().size());
      if (embeddingContext.getRequest().getOptions() != null) {
        observation.put("dimensions", embeddingContext.getRequest().getOptions().getDimensions());
      }
      if (embeddingContext.getResponse() != null) {
        observation.put("count", embeddingContext.getResponse().getResults().size());
        usage = embeddingContext.getResponse().getMetadata().getUsage();
      }
    } else if (context instanceof ImageModelObservationContext imageContext) {
      observation.put("modality", "image");
      if (imageContext.getRequest().getOptions() != null) {
        observation.put("width", imageContext.getRequest().getOptions().getWidth());
        observation.put("height", imageContext.getRequest().getOptions().getHeight());
      }
      if (imageContext.getResponse() != null) {
        observation.put("count", imageContext.getResponse().getResults().size());
        Object imageUsage = imageContext.getResponse().getMetadata().get("openrouter.usage");
        if (imageUsage instanceof Usage recordedUsage) {
          usage = recordedUsage;
        }
      }
    }
    if (usage != null) {
      observation.put("usage", GarageResponses.usage(usage));
      double costUsd = GarageCosts.usage(usage);
      observation.put("costUsd", costUsd);
      this.evidence.recordCost(operationId, costUsd);
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
    return context instanceof ChatModelObservationContext
        || context instanceof EmbeddingModelObservationContext
        || context instanceof ImageModelObservationContext;
  }

  public List<Map<String, Object>> observationSnapshot() {
    return this.observations.stream().map(this::sanitize).toList();
  }

  public List<Map<String, Object>> observationsFor(String operationId) {
    return this.observations.stream()
        .filter(item -> operationId.equals(item.get("operationId")))
        .map(this::sanitize)
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
    return meters.stream().map(this::sanitize).toList();
  }

  public void reset() {
    this.observations.clear();
    this.startedObservations.clear();
    this.meterRegistry.clear();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> sanitize(Map<String, Object> value) {
    return (Map<String, Object>) this.evidence.sanitizeForEvidence(value);
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
