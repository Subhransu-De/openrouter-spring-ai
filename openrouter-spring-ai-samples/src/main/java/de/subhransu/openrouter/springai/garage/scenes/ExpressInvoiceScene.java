package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageResponses;
import de.subhransu.openrouter.springai.garage.GarageTools;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Demonstrates returnDirect by returning an invoice without a follow-up model call. */
@Component
public final class ExpressInvoiceScene extends GarageSceneSupport {

  public ExpressInvoiceScene() {
    super(
        "express-invoice",
        "Express invoice",
        "A returnDirect invoice tool whose output is the final ChatClient output.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    GarageFeature feature = GarageFeature.RETURN_DIRECT;
    GarageTools tools =
        new GarageTools(
            context.chatModel(),
            context.properties(),
            context.outputDirectory(),
            context.requestMode(),
            operationId,
            id());
    ToolCallback invoice =
        Arrays.stream(ToolCallbacks.from(tools))
            .filter(
                callback ->
                    "generate_express_invoice".equals(callback.getToolDefinition().name()))
            .findFirst()
            .map(
                callback ->
                    GarageToolCallback.wrap(
                        callback,
                        context.evidence(),
                        operationId,
                        id(),
                        mode,
                        feature))
            .orElseThrow();
    OpenRouterChatOptions options =
        context.optionsFactory()
            .expressInvoice(
                operationId,
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic(),
                invoice);
    context.evidence().record(
        feature,
        operationId,
        mode,
        EvidenceLevel.CONFIGURED,
        "requestOptions",
        context.optionsFactory().snapshot(options));
    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(
                    "Call generate_express_invoice exactly once for a diagnostic inspection at"
                        + " 89 dollars. Do not write any other output."),
                new UserMessage("Create the express invoice.")),
            options);
    ChatResponse response;
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      response = context.chatClient().prompt(prompt).call().chatResponse();
    }
    String finalOutput = GarageResponses.text(response);
    String decodedFinalOutput = decodeToolOutput(context, finalOutput);
    String invoiceOutput = invoiceOutput(tools.invocations());
    List<Map<String, Object>> observations = context.telemetry().observationsFor(operationId);
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("toolOutput", invoiceOutput);
    details.put("finalOutput", finalOutput);
    details.put("decodedFinalOutput", decodedFinalOutput);
    details.put("modelObservationCount", observations.size());
    details.put("observations", observations);
    details.put("costUsd", GarageCosts.usage(response.getMetadata().getUsage()));
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.EXECUTED, "tool", "generate_express_invoice");
    context.evidence().record(
        feature, operationId, mode, EvidenceLevel.OBSERVED, "outcome", details);
    if (invoiceOutput == null
        || !invoiceOutput.equals(decodedFinalOutput)
        || observations.size() != 1) {
      IllegalStateException failure =
          new IllegalStateException(
              "returnDirect contract mismatch: toolOutput='"
                  + invoiceOutput
                  + "', finalOutput='"
                  + finalOutput
                  + "', modelObservations="
                  + observations.size());
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

  private String decodeToolOutput(SceneContext context, String value) {
    if (value == null || !value.startsWith("\"") || !value.endsWith("\"")) {
      return value;
    }
    try {
      return context.objectMapper().readValue(value, String.class);
    } catch (Exception ignored) {
      return value;
    }
  }

  @SuppressWarnings("unchecked")
  private String invoiceOutput(List<Map<String, Object>> invocations) {
    return invocations.stream()
        .filter(item -> "generate_express_invoice".equals(item.get("tool")))
        .map(item -> (Map<String, Object>) item.get("result"))
        .map(result -> result.get("invoice"))
        .map(Object::toString)
        .findFirst()
        .orElse(null);
  }
}
