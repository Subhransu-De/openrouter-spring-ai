package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageResponses;
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

/** Forces an unavailable primary through the configured model fallback/routing lane. */
@Component
public final class RoutingLaneScene extends GarageSceneSupport {

  private static final String UNAVAILABLE_PRIMARY = "garage/primary-unavailable";

  public RoutingLaneScene() {
    super(
        "routing-lane",
        "Routing lane",
        "Forced primary fallback with complete provider preferences, route, and non-auto tier.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    List<String> routingModels = new ArrayList<>();
    routingModels.add(context.command().foremanModel());
    context.command().fallbackModels().stream()
        .filter(model -> !routingModels.contains(model))
        .forEach(routingModels::add);
    OpenRouterChatOptions options =
        context.optionsFactory()
            .routingLane(
                operationId,
                context.requestMode(),
                UNAVAILABLE_PRIMARY,
                routingModels,
                context.command().topic());
    Map<String, Object> requestEvidence = context.optionsFactory().snapshot(options);
    context.evidence().recordAll(
        features(),
        operationId,
        mode,
        EvidenceLevel.CONFIGURED,
        "requestOptions",
        requestEvidence);
    ChatResponse response;
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      response =
          context
              .chatModel()
              .call(
                  new Prompt(
                      new UserMessage(
                          "Reply with one sentence confirming the routing-lane inspection."),
                      options));
    }
    String servedModel = response.getMetadata().getModel();
    Object servedProvider = response.getMetadata().get("openrouter.provider");
    boolean fallbackActivated =
        servedModel != null && !UNAVAILABLE_PRIMARY.equals(servedModel);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("requestedPrimary", UNAVAILABLE_PRIMARY);
    details.put("requestedFallbacks", routingModels);
    details.put("servedModel", servedModel);
    details.put("servedProvider", servedProvider);
    details.put("fallbackActivated", fallbackActivated);
    details.put("finishReason", GarageResponses.finishReason(response));
    details.put("costUsd", GarageCosts.usage(response.getMetadata().getUsage()));
    details.put("requestOptions", requestEvidence);
    details.put("observations", context.telemetry().observationsFor(operationId));
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.EXECUTED, "request", details);
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.OBSERVED, "outcome", details);

    List<String> failures = new ArrayList<>();
    if (!fallbackActivated) {
      failures.add("served model did not prove fallback from the unavailable primary");
    }
    if (context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
        && servedProvider == null) {
      failures.add("served provider was missing");
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> provider = (Map<String, Object>) requestEvidence.get("provider");
    if (provider == null
        || provider.values().stream().anyMatch(value -> value == null)
        || options.getRoute() == null
        || options.getServiceTier() == null) {
      failures.add("provider preferences, route, or service tier were incomplete");
    }
    if (context.telemetry().observationsFor(operationId).isEmpty()) {
      failures.add("routing observation was not retained");
    }
    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      features().forEach(feature -> context.evidence().error(feature, operationId, mode, failure));
      throw failure;
    }
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }
}
