package de.subhransu.openrouter.springai.garage.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/** Safely records header presence for both blocking and streaming transports. */
@Component
public final class GarageTransportEvidence {

  private static final List<String> SAFE_HEADERS =
      List.of("HTTP-Referer", "X-OpenRouter-Title", "X-OpenRouter-Categories");

  private final GarageEvidence evidence;
  private final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
  private final AtomicReference<@Nullable Operation> activeOperation = new AtomicReference<>();

  public GarageTransportEvidence(GarageEvidence evidence) {
    this.evidence = evidence;
  }

  public Scope activate(String operationId, String sceneId) {
    Operation previous = this.activeOperation.getAndSet(new Operation(operationId, sceneId));
    return () -> this.activeOperation.set(previous);
  }

  public ClientHttpRequestInterceptor restInterceptor() {
    return (request, body, execution) -> {
      try {
        return execution.execute(request, body);
      } finally {
        capture("sync", request.getMethod().name(), request.getURI().getPath(), request.getHeaders());
      }
    };
  }

  public ExchangeFilterFunction webFilter() {
    return (request, next) -> {
      capture("stream", request.method().name(), request.url().getPath(), request.headers());
      return next.exchange(request);
    };
  }

  public List<Map<String, Object>> snapshot() {
    return List.copyOf(this.requests);
  }

  public List<Map<String, Object>> forOperation(String operationId) {
    return this.requests.stream()
        .filter(item -> operationId.equals(item.get("operationId")))
        .toList();
  }

  public void reset() {
    this.requests.clear();
    this.activeOperation.set(null);
  }

  private void capture(String transport, String method, String path, HttpHeaders headers) {
    Operation operation = this.activeOperation.get();
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("at", Instant.now().toString());
    record.put("operationId", operation != null ? operation.operationId() : null);
    record.put("sceneId", operation != null ? operation.sceneId() : null);
    record.put("transport", transport);
    record.put("method", method);
    record.put("path", path);
    Map<String, Boolean> presence = new LinkedHashMap<>();
    SAFE_HEADERS.forEach(name -> presence.put(name, headers.containsHeader(name)));
    record.put("headerPresence", presence);
    this.requests.add(record);
    if (operation != null) {
      this.evidence.event(
          operation.operationId(), operation.sceneId(), "transport.request", record);
    }
  }

  private record Operation(String operationId, String sceneId) {}

  @FunctionalInterface
  public interface Scope extends AutoCloseable {
    @Override
    void close();
  }
}
