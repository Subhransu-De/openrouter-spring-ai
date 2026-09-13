package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** Verifies attribution header presence on both HTTP clients without retaining values. */
@Component
public final class AttributionScene extends GarageSceneSupport {

  public AttributionScene() {
    super(
        "attribution-check-in",
        "Attribution check-in",
        "Captures only referer, title, and category header presence for sync and stream.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    GarageFeature feature = GarageFeature.ATTRIBUTION_HEADERS;
    OpenRouterChatOptions options =
        context.optionsFactory()
            .plain(
                operationId,
                id(),
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic());
    context.evidence().record(
        feature,
        operationId,
        mode,
        EvidenceLevel.CONFIGURED,
        "requiredHeaders",
        List.of("HTTP-Referer", "X-OpenRouter-Title", "X-OpenRouter-Categories"));
    Prompt prompt =
        new Prompt(
            new UserMessage("Reply with exactly: attribution headers checked"), options);
    ChatResponse syncResponse;
    List<ChatResponse> streamResponses;
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      syncResponse = context.chatModel().call(prompt);
      streamResponses =
          context.chatModel().stream(prompt).collectList().block(Duration.ofMinutes(2));
    }
    context.telemetry().awaitObservationsFor(operationId, Duration.ofSeconds(1));
    if (streamResponses == null) {
      streamResponses = List.of();
    }
    List<Map<String, Object>> requests = context.transportEvidence().forOperation(operationId);
    List<String> failures = new ArrayList<>();
    for (String transport : List.of("sync", "stream")) {
      Map<String, Object> request =
          requests.stream()
              .filter(item -> transport.equals(item.get("transport")))
              .findFirst()
              .orElse(null);
      if (request == null || !allHeadersPresent(request)) {
        failures.add(transport + " attribution headers were not all observed");
      }
    }
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("requests", requests);
    details.put("valuesRetained", false);
    details.put(
        "costUsd",
        GarageCosts.usage(syncResponse.getMetadata().getUsage())
            + GarageCosts.stream(streamResponses));
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.EXECUTED, "transports", List.of("sync", "stream"));
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.OBSERVED, "headerPresence", details);
    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      context.evidence().error(feature, operationId, mode, failure);
      throw failure;
    }
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }

  @SuppressWarnings("unchecked")
  private boolean allHeadersPresent(Map<String, Object> request) {
    Map<String, Boolean> presence = (Map<String, Boolean>) request.get("headerPresence");
    return presence != null && presence.size() == 3 && presence.values().stream().allMatch(Boolean::booleanValue);
  }
}
