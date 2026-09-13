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
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** JSON Schema and Spring output-schema variants deserialized into a typed inspection. */
@Component
public final class DigitalInspectionScene extends GarageSceneSupport {

  static final String SCHEMA =
      """
      {
        "type": "object",
        "additionalProperties": false,
        "properties": {
          "vehicle": {"type": "string"},
          "severity": {"type": "integer", "minimum": 1, "maximum": 5},
          "findings": {"type": "array", "items": {"type": "string"}, "minItems": 1},
          "safeToDrive": {"type": "boolean"},
          "nextStep": {"type": "string"}
        },
        "required": ["vehicle", "severity", "findings", "safeToDrive", "nextStep"]
      }
      """;

  public DigitalInspectionScene() {
    super(
        "digital-inspection",
        "Digital inspection",
        "Strict JSON Schema output parsed into a ServiceInspection record.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) throws Exception {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    GarageFeature feature = GarageFeature.STRUCTURED_OUTPUT;
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.CONFIGURED, "schema", SCHEMA);

    OpenRouterChatOptions responseFormatOptions =
        context.optionsFactory()
            .digitalInspection(
                operationId,
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic(),
                SCHEMA,
                false);
    OpenRouterChatOptions outputSchemaOptions =
        context.optionsFactory()
            .digitalInspection(
                operationId,
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic(),
                SCHEMA,
                true);

    if (context.requestMode() == OpenRouterRequestMode.OPENAI_RESPONSES) {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("status", "unsupported-in-mode");
      details.put(
          "unsupportedOptions", context.optionsFactory().unsupportedInMode(outputSchemaOptions));
      details.put("requestOptions", context.optionsFactory().snapshot(outputSchemaOptions));
      context.evidence().record(
          feature, operationId, mode, EvidenceLevel.OBSERVED, "modeDifference", details);
      context.evidence().record(
          feature,
          operationId,
          mode,
          EvidenceLevel.ASSERTED,
          "assertion",
          "unsupported options were reported explicitly");
      return SceneResult.passed(
          id(),
          operationId,
          context.requestMode(),
          Duration.between(started, Instant.now()),
          context.outputDirectory(),
          details);
    }

    List<InspectionCall> calls = new ArrayList<>();
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      calls.add(call(context, responseFormatOptions));
      calls.add(call(context, outputSchemaOptions));
    }
    List<ServiceInspection> inspections = calls.stream().map(InspectionCall::inspection).toList();
    List<String> failures = new ArrayList<>();
    inspections.forEach(
        inspection -> {
          if (!StringUtils.hasText(inspection.vehicle())
              || inspection.severity() < 1
              || inspection.severity() > 5
              || inspection.findings() == null
              || inspection.findings().isEmpty()
              || !StringUtils.hasText(inspection.nextStep())) {
            failures.add("structured inspection did not satisfy the required schema");
          }
        });
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("variants", List.of("responseFormat", "outputSchema"));
    details.put("typedInspections", inspections);
    details.put("observations", context.telemetry().observationsFor(operationId));
    details.put("transport", context.transportEvidence().forOperation(operationId));
    details.put("costUsd", calls.stream().mapToDouble(InspectionCall::costUsd).sum());
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.EXECUTED, "variants", details.get("variants"));
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.OBSERVED, "typedResults", inspections);
    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      context.evidence().error(feature, operationId, mode, failure);
      throw failure;
    }
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.ASSERTED, "schemaValidation", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }

  private InspectionCall call(SceneContext context, OpenRouterChatOptions options) throws Exception {
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage("Return only JSON matching the supplied service inspection schema."),
                new UserMessage("Inspect this vehicle report: " + context.command().topic())),
            options);
    ChatResponse response = context.chatModel().call(prompt);
    ServiceInspection inspection =
        context.objectMapper().readValue(GarageResponses.text(response), ServiceInspection.class);
    return new InspectionCall(inspection, GarageCosts.usage(response.getMetadata().getUsage()));
  }

  private record InspectionCall(ServiceInspection inspection, double costUsd) {}

  public record ServiceInspection(
      String vehicle,
      int severity,
      List<String> findings,
      boolean safeToDrive,
      String nextStep) {}
}
