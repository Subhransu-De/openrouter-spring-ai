package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageResponses;
import de.subhransu.openrouter.springai.garage.GarageTools;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Stabilized service story with deterministic tool inputs and a single advisor-driven writer. */
@Component
public final class ServiceStoryScene extends GarageSceneSupport {

  public ServiceStoryScene() {
    super(
        "service-story",
        "Service story",
        "Deterministic inspection, specialist delegation, priority score, and service record.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) throws Exception {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    List<GarageFeature> applicable = applicable(context.requestMode());
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.CONFIGURED, "scene", id());

    Files.createDirectories(context.outputDirectory());
    GarageTools tools =
        new GarageTools(
            context.chatModel(),
            context.properties(),
            context.outputDirectory(),
            context.requestMode(),
            operationId,
            id(),
            context.command().specialistModel());
    List<ToolCallback> callbacks =
        Arrays.stream(ToolCallbacks.from(tools))
            .map(
                callback ->
                    GarageToolCallback.wrap(
                        callback,
                        context.evidence(),
                        operationId,
                        id(),
                        mode,
                        GarageFeature.TOOL_LOOP,
                        GarageFeature.MIXED_TOOL_SCHEMAS))
            .toList();

    ToolContext toolContext =
        new ToolContext(
            Map.of("garage.jobId", operationId, "garage.tenant", "sample-shop"));
    callback(callbacks, "inspect_vehicle_profile")
        .call(
            "{\"concern\":\""
                + jsonText(context.command().topic())
                + "\",\"severity\":4,\"safetyCritical\":true}",
            toolContext);
    callback(callbacks, "score_repair_plan")
        .call("{\"safetyRisk\":4,\"reliabilityRisk\":3,\"costRisk\":2}", toolContext);
    callback(callbacks, "hand_to_specialist")
        .call(
            "{\"job\":\"Inspect the cooling and brake symptoms; return measured next steps.\"}",
            toolContext);
    callback(callbacks, "log_to_jobsheet")
        .call(
            "{\"title\":\"Deterministic inspection\",\"markdown\":\"Inspection, specialist, and risk-score steps completed with typed inputs.\"}",
            toolContext);

    ToolCallback writer = callback(callbacks, "log_to_jobsheet");
    OpenRouterChatOptions options =
        context.optionsFactory()
            .serviceStory(
                operationId,
                context.requestMode(),
                context.command().foremanModel(),
                context.command().fallbackModels(),
                context.command().topic(),
                List.of(writer));
    context.evidence().recordAll(
        applicable,
        operationId,
        mode,
        EvidenceLevel.CONFIGURED,
        "requestOptions",
        context.optionsFactory().snapshot(options));

    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(
                    "You are the Garage Foreman. You have exactly one tool. Call"
                        + " log_to_jobsheet once with a short final recommendation, then return a"
                        + " one-sentence customer note. Do not invent completed repairs."),
                new UserMessage("Write the final service recommendation for: " + context.command().topic())),
            options);

    ChatResponse response;
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      response = context.chatClient().prompt(prompt).call().chatResponse();
    }
    String finalText = GarageResponses.text(response);
    appendFinalRecord(context.outputDirectory(), finalText);

    List<Map<String, Object>> invocations = tools.invocations();
    List<Map<String, Object>> observations = context.telemetry().observationsFor(operationId);
    Map<String, Object> usage = GarageResponses.usage(response.getMetadata().getUsage());
    List<String> failures = assertions(response, finalText, invocations, observations, usage);

    Map<String, Object> observed = new LinkedHashMap<>();
    observed.put("response", Map.of("textCharacters", finalText.length(), "metadata", GarageResponses.metadata(response)));
    observed.put("usage", usage);
    observed.put("toolInvocations", invocations);
    observed.put("observations", observations);
    observed.put("transport", context.transportEvidence().forOperation(operationId));
    observed.put("serviceRecord", context.outputDirectory().resolve("service-record.md").toString());
    observed.put(
        "costUsd",
        GarageCosts.usage(response.getMetadata().getUsage()) + GarageCosts.usageMaps(invocations));
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.EXECUTED, "completedAt", Instant.now().toString());
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.OBSERVED, "outcome", observed);

    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      applicable.forEach(feature -> context.evidence().error(feature, operationId, mode, failure));
      throw failure;
    }
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        observed);
  }

  private List<GarageFeature> applicable(OpenRouterRequestMode requestMode) {
    List<GarageFeature> applicable = new ArrayList<>(features());
    if (requestMode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
      applicable.remove(GarageFeature.RESPONSES_MODE);
    } else {
      applicable.remove(GarageFeature.CHAT_COMPLETIONS_MODE);
    }
    return applicable;
  }

  private List<String> assertions(
      ChatResponse response,
      String finalText,
      List<Map<String, Object>> invocations,
      List<Map<String, Object>> observations,
      Map<String, Object> usage) {
    List<String> failures = new ArrayList<>();
    if (!StringUtils.hasText(finalText)) {
      failures.add("final Foreman response was empty");
    }
    for (String tool :
        List.of(
            "inspect_vehicle_profile",
            "score_repair_plan",
            "hand_to_specialist",
            "log_to_jobsheet")) {
      if (invocations.stream().noneMatch(item -> tool.equals(item.get("tool")))) {
        failures.add(tool + " did not complete");
      }
    }
    if (observations.isEmpty()) {
      failures.add("no correlated sync observations were retained");
    }
    if (usage.get("totalTokens") == null) {
      failures.add("total token usage was missing");
    }
    if (response.getMetadata().getId() == null || response.getMetadata().getModel() == null) {
      failures.add("response id or served model was missing");
    }
    if (contextualReasoningMissing(response)) {
      failures.add("reasoning text or reasoning-token evidence was missing");
    }
    if (usage.get("cost") == null
        || usage.get("cachedTokens") == null
        || usage.get("reasoningTokens") == null) {
      failures.add("cost, cached-token, or reasoning-token evidence was missing");
    }
    return failures;
  }

  private boolean contextualReasoningMissing(ChatResponse response) {
    if (StringUtils.hasText(GarageResponses.reasoning(response))) {
      return false;
    }
    return !(response.getMetadata().getUsage() instanceof OpenRouterUsage usage)
        || usage.getReasoningTokens() == null
        || usage.getReasoningTokens() <= 0;
  }

  private ToolCallback callback(List<ToolCallback> callbacks, String name) {
    return callbacks.stream()
        .filter(callback -> name.equals(callback.getToolDefinition().name()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Missing Garage tool: " + name));
  }

  private void appendFinalRecord(Path outputDirectory, String finalText) throws Exception {
    Path serviceRecord = outputDirectory.resolve("service-record.md");
    Files.writeString(
        serviceRecord,
        "\n\n## Final Foreman note\n\n"
            + (StringUtils.hasText(finalText) ? finalText.strip() : "_No final text returned._")
            + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }

  private String jsonText(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
  }
}
