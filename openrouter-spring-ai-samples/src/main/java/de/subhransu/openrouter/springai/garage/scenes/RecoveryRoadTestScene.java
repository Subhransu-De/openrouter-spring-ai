package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.garage.GarageResponses;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageObservationConvention;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/** Deterministic local retry, timeout, malformed stream, terminal error, and cancellation lane. */
@Component
public final class RecoveryRoadTestScene extends GarageSceneSupport {

  // Must leave generous headroom above a loaded machine's loopback latency: the
  // fast-path steps (retry success, HTTP error bodies) have to finish inside this
  // budget, while the server's deliberate delays sit far above it so the timeout
  // scenarios still fire first. 250ms proved flaky under parallel test load.
  private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(1);

  // Outer safety net for blocking on streams; the client timeout above is what is
  // actually expected to fire, so this only needs to be comfortably larger.
  private static final Duration BLOCK_CAP = Duration.ofSeconds(5);

  public RecoveryRoadTestScene() {
    super(
        "recovery-road-test",
        "Recovery road test",
        "Local HTTP/SSE faults prove retry, timeouts, errors, base URL, builders, and telemetry.",
        true);
  }

  @Override
  public SceneResult execute(SceneContext context) throws Exception {
    Instant started = Instant.now();
    String mode = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS.name();
    String operationId = context.evidence().newOperation(id(), mode);
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.CONFIGURED, "timeout", CLIENT_TIMEOUT.toString());
    Map<String, Object> details = new LinkedHashMap<>();
    List<Map<String, Object>> expectedErrors = new ArrayList<>();
    try (GarageRoadTestServer server = new GarageRoadTestServer()) {
      OpenRouterApi api = api(server);
      OpenRouterChatModel retryModel = model(context, api, RetryPolicy.withMaxRetries(1));
      OpenRouterChatModel noRetryModel = model(context, api, RetryPolicy.withMaxRetries(0));
      OpenRouterChatOptions options =
          context.optionsFactory()
              .plain(
                  operationId,
                  id(),
                  OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
                  "garage/offline-primary",
                  "offline road test");
      Prompt prompt = new Prompt(new UserMessage("run the road test"), options);

      server.mode(GarageRoadTestServer.Mode.RETRY);
      String retryResult = GarageResponses.text(retryModel.call(prompt));
      int retryAttempts = server.attempts();

      server.mode(GarageRoadTestServer.Mode.SYNC_TIMEOUT);
      expectedErrors.add(expectedFailure("sync-timeout", () -> noRetryModel.call(prompt)));

      server.mode(GarageRoadTestServer.Mode.HTTP_ERROR);
      expectedErrors.add(expectedFailure("http-422", () -> noRetryModel.call(prompt)));

      server.mode(GarageRoadTestServer.Mode.STREAM_TIMEOUT);
      expectedErrors.add(
          expectedFailure(
              "stream-timeout",
              () -> noRetryModel.stream(prompt).collectList().block(BLOCK_CAP)));

      server.mode(GarageRoadTestServer.Mode.MALFORMED_STREAM);
      expectedErrors.add(
          expectedFailure(
              "malformed-stream",
              () -> noRetryModel.stream(prompt).collectList().block(BLOCK_CAP)));

      server.mode(GarageRoadTestServer.Mode.TERMINAL_STREAM_ERROR);
      expectedErrors.add(
          expectedFailure(
              "terminal-stream-error",
              () -> noRetryModel.stream(prompt).collectList().block(BLOCK_CAP)));

      server.mode(GarageRoadTestServer.Mode.CANCELLATION_STREAM);
      AtomicBoolean cancelled = new AtomicBoolean();
      noRetryModel
          .stream(prompt)
          .doOnCancel(() -> cancelled.set(true))
          .take(1)
          .collectList()
          .block(BLOCK_CAP);
      context.evidence().event(
          operationId, id(), "stream.cancelled", Map.of("cancelled", cancelled.get()));

      List<Map<String, Object>> observations = context.telemetry().observationsFor(operationId);
      long observationErrors =
          observations.stream()
              .map(item -> item.get("error"))
              .filter(Map.class::isInstance)
              .map(Map.class::cast)
              .filter(error -> !error.isEmpty())
              .count();
      boolean attributionPresent =
          server.requests().stream().allMatch(this::allHeadersPresent);
      details.put("baseUrl", server.baseUrl());
      details.put("customBuilders", List.of("RestClient.Builder", "WebClient.Builder"));
      details.put("customRetryTemplate", true);
      details.put("customObservationConvention", GarageObservationConvention.class.getName());
      details.put("retryAttempts", retryAttempts);
      details.put("retryResult", retryResult);
      details.put("expectedErrors", expectedErrors);
      details.put("streamCancelled", cancelled.get());
      details.put("observations", observations);
      details.put("observationErrors", observationErrors);
      details.put("meters", context.telemetry().meterSnapshot());
      details.put("serverRequests", server.requests());
      details.put("attributionHeadersPresent", attributionPresent);

      List<String> failures = new ArrayList<>();
      if (retryAttempts != 2 || !"road test passed".equals(retryResult)) {
        failures.add("fail-once retry did not succeed on the second attempt");
      }
      if (expectedErrors.stream().anyMatch(error -> Boolean.FALSE.equals(error.get("failed")))) {
        failures.add("one or more fault scenarios unexpectedly succeeded");
      }
      if (!cancelled.get()) {
        failures.add("stream cancellation was not retained");
      }
      if (observationErrors < 5) {
        failures.add("expected error observations were not all retained");
      }
      if (!attributionPresent) {
        failures.add("custom local transport did not receive attribution headers");
      }
      if (context.telemetry().meterSnapshot().isEmpty()) {
        failures.add("gen_ai.client.operation meter was not retained");
      }

      context.evidence().recordAll(
          features(), operationId, mode, EvidenceLevel.EXECUTED, "faultScenarios", details);
      context.evidence().recordAll(
          features(), operationId, mode, EvidenceLevel.OBSERVED, "outcomes", details);
      if (!failures.isEmpty()) {
        IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
        features().forEach(feature -> context.evidence().error(feature, operationId, mode, failure));
        throw failure;
      }
    }
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }

  private OpenRouterApi api(GarageRoadTestServer server) {
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
    requestFactory.setReadTimeout(CLIENT_TIMEOUT);
    return OpenRouterApi.builder()
        .baseUrl(server.baseUrl())
        .apiKey("garage-offline-key")
        .httpReferer("https://garage.invalid")
        .applicationTitle("Garage Road Test")
        .applicationCategories("samples,offline")
        .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
        .webClientBuilder(WebClient.builder())
        .objectMapper(new tools.jackson.databind.ObjectMapper())
        .timeout(CLIENT_TIMEOUT)
        .build();
  }

  private OpenRouterChatModel model(
      SceneContext context, OpenRouterApi api, RetryPolicy retryPolicy) {
    OpenRouterChatModel model =
        OpenRouterChatModel.builder()
            .openRouterApi(api)
            .retryTemplate(new RetryTemplate(retryPolicy))
            .observationRegistry(context.observationRegistry())
            .objectMapper(context.objectMapper())
            .build();
    model.setObservationConvention(new GarageObservationConvention());
    return model;
  }

  private Map<String, Object> expectedFailure(String scenario, Runnable action) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("scenario", scenario);
    try {
      action.run();
      result.put("failed", false);
    } catch (RuntimeException failure) {
      result.put("failed", true);
      result.put("type", failure.getClass().getName());
      result.put("message", failure.getMessage());
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private boolean allHeadersPresent(Map<String, Object> request) {
    Map<String, Boolean> headers = (Map<String, Boolean>) request.get("headerPresence");
    return headers != null && headers.values().stream().allMatch(Boolean::booleanValue);
  }
}
