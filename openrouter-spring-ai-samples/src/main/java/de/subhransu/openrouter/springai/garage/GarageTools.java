package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;

public final class GarageTools {

  private final ChatModel chatModel;
  private final GarageProperties properties;
  private final String specialistModel;
  private final Path outputDirectory;
  private final OpenRouterRequestMode requestMode;
  private final String operationId;
  private final String sceneId;
  private final List<Map<String, Object>> invocations =
      Collections.synchronizedList(new ArrayList<>());

  public GarageTools(
      ChatModel chatModel,
      GarageProperties properties,
      Path outputDirectory,
      OpenRouterRequestMode requestMode) {
    this(
        chatModel,
        properties,
        outputDirectory,
        requestMode,
        null,
        "service-story",
        properties.getSpecialistModel());
  }

  public GarageTools(
      ChatModel chatModel,
      GarageProperties properties,
      Path outputDirectory,
      OpenRouterRequestMode requestMode,
      String operationId,
      String sceneId) {
    this(
        chatModel,
        properties,
        outputDirectory,
        requestMode,
        operationId,
        sceneId,
        properties.getSpecialistModel());
  }

  public GarageTools(
      ChatModel chatModel,
      GarageProperties properties,
      Path outputDirectory,
      OpenRouterRequestMode requestMode,
      String operationId,
      String sceneId,
      String specialistModel) {
    this.chatModel = chatModel;
    this.properties = properties;
    this.specialistModel = specialistModel;
    this.outputDirectory = outputDirectory;
    this.requestMode = requestMode;
    this.operationId = operationId;
    this.sceneId = sceneId;
  }

  @Tool(
      name = "hand_to_specialist",
      description =
          "Hand one narrow garage job card to a specialist bay. Use this when the Foreman needs a"
              + " second model to inspect one subtask.")
  public String handToSpecialist(
      @ToolParam(description = "A narrow, self-contained job card for the specialist bay.")
          String job) {
    String selectedModel = this.specialistModel;
    OpenRouterChatOptions options =
        OpenRouterChatOptions.builder()
            .model(selectedModel)
            .requestMode(this.requestMode)
            .temperature(0.1)
            .maxCompletionTokens(this.properties.getSpecialistMaxCompletionTokens())
            .includeUsage(this.requestMode == OpenRouterRequestMode.OPENAI_RESPONSES ? null : true)
            .metadata(
                Map.of(
                    "application", "garage",
                    "sceneId", this.sceneId,
                    "operationId", this.operationId != null ? this.operationId : "uncorrelated",
                    "phase", "specialist"))
            .build();

    Prompt prompt =
        new Prompt(
            List.of(
                new SystemMessage(
                    "You are a specialist mechanic in the Garage sample app. Answer only the"
                        + " delegated job card. Be concrete, terse, and write markdown bullets."),
                new UserMessage(job)),
            options);

    ChatResponse response = this.chatModel.call(prompt);
    String text = GarageResponses.text(response);
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("requestedModel", selectedModel);
    arguments.put("job", job);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("characters", text.length());
    result.put("finishReason", GarageResponses.finishReason(response));
    result.put("servedModel", response.getMetadata().getModel());
    result.put("provider", response.getMetadata().get("openrouter.provider"));
    result.put("usage", GarageResponses.usage(response.getMetadata().getUsage()));
    recordInvocation("hand_to_specialist", arguments, result);
    return text;
  }

  @Tool(
      name = "inspect_vehicle_profile",
      description =
          "Inspect the customer concern against the Garage's deterministic symptom checklist. Use"
              + " this before delegating a specialist job.")
  public String inspectVehicleProfile(
      @ToolParam(description = "The primary concern or symptom cluster being investigated.")
          String concern,
      @ToolParam(description = "Severity from 1 to 5, where 5 is an immediate safety risk.")
          Integer severity,
      @ToolParam(description = "Whether the concern has an immediate safety implication.")
          Boolean safetyCritical) {
    int normalizedSeverity = clamp(severity != null ? severity : 3, 1, 5);
    boolean safety = Boolean.TRUE.equals(safetyCritical);
    String triage =
        switch (normalizedSeverity) {
          case 1, 2 -> "low urgency; confirm baseline condition before replacing parts";
          case 3 -> "moderate urgency; run mechanical and electrical checks before road test";
          case 4 -> "high urgency; isolate the fault before returning the car to service";
          default -> "critical; keep the vehicle parked until the safety path is understood";
        };
    String response =
        "- Concern: "
            + textOrFallback(concern, "unspecified concern")
            + "\n"
            + "- Severity: "
            + normalizedSeverity
            + "/5\n"
            + "- Safety critical: "
            + safety
            + "\n"
            + "- Garage checklist: verify symptoms, isolate the subsystem, record measured"
            + " evidence, then recommend the smallest reversible repair.\n"
            + "- Triage: "
            + triage
            + ".";

    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("concern", concern);
    arguments.put("severity", severity);
    arguments.put("safetyCritical", safetyCritical);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("normalizedSeverity", normalizedSeverity);
    result.put("characters", response.length());
    recordInvocation("inspect_vehicle_profile", arguments, result);
    return response;
  }

  @Tool(
      name = "score_repair_plan",
      description =
          "Calculate a deterministic Garage repair priority score from risk dimensions. Use this"
              + " before writing the final service record.")
  public String scoreRepairPlan(
      @ToolParam(description = "Safety risk from 0 to 5.") Integer safetyRisk,
      @ToolParam(description = "Reliability risk from 0 to 5.") Integer reliabilityRisk,
      @ToolParam(description = "Cost escalation risk from 0 to 5.") Integer costRisk) {
    int safety = clamp(safetyRisk != null ? safetyRisk : 0, 0, 5);
    int reliability = clamp(reliabilityRisk != null ? reliabilityRisk : 0, 0, 5);
    int cost = clamp(costRisk != null ? costRisk : 0, 0, 5);
    int score = safety * 3 + reliability * 2 + cost;
    String band =
        score >= 22 ? "do-not-release" : score >= 14 ? "same-day repair" : "scheduled repair";
    String response =
        "- Safety risk: "
            + safety
            + "/5\n"
            + "- Reliability risk: "
            + reliability
            + "/5\n"
            + "- Cost escalation risk: "
            + cost
            + "/5\n"
            + "- Weighted priority score: "
            + score
            + "\n"
            + "- Dispatch band: "
            + band
            + ".";

    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("safetyRisk", safetyRisk);
    arguments.put("reliabilityRisk", reliabilityRisk);
    arguments.put("costRisk", costRisk);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("score", score);
    result.put("band", band);
    recordInvocation("score_repair_plan", arguments, result);
    return response;
  }

  @Tool(
      name = "log_to_jobsheet",
      description =
          "Append a markdown section to the Garage service record on disk. Use this to create the"
              + " customer-facing paper trail.")
  public String logToJobsheet(
      @ToolParam(description = "Short markdown heading for this service-record section.")
          String title,
      @ToolParam(description = "Markdown content to append under the heading.") String markdown) {
    try {
      Files.createDirectories(this.outputDirectory);
      Path serviceRecord = this.outputDirectory.resolve("service-record.md");
      String content =
          StringUtils.hasText(markdown) ? markdown.strip() : "_No markdown content supplied._";
      String section = "\n\n## " + sanitizeHeading(title) + "\n\n" + content + "\n";
      Files.writeString(
          serviceRecord,
          section,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
      Map<String, Object> arguments = new LinkedHashMap<>();
      arguments.put("title", title);
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("path", serviceRecord.toString());
      result.put("characters", content.length());
      recordInvocation("log_to_jobsheet", arguments, result);
      return "Appended section '" + title + "' to " + serviceRecord + ".";
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to append Garage service record", ex);
    }
  }

  @Tool(
      name = "lookup_service_bulletin",
      description =
          "Look up a deterministic service bulletin from a model year, VIN prefix, and symptom.")
  public String lookupServiceBulletin(
      @ToolParam(description = "Four-character VIN prefix.") String vinPrefix,
      @ToolParam(description = "Vehicle model year.") Integer modelYear,
      @ToolParam(description = "Short symptom name.") String symptom) {
    String bulletin =
        "TSB-"
            + clamp(modelYear != null ? modelYear : 2000, 1900, 2100)
            + "-"
            + textOrFallback(vinPrefix, "NONE").toUpperCase()
            + ": inspect "
            + textOrFallback(symptom, "reported symptom")
            + " before replacing parts.";
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("vinPrefix", vinPrefix);
    arguments.put("modelYear", modelYear);
    arguments.put("symptom", symptom);
    recordInvocation(
        "lookup_service_bulletin", arguments, Map.of("bulletin", bulletin));
    return bulletin;
  }

  @Tool(
      name = "generate_express_invoice",
      description = "Create a deterministic one-line express-lane invoice.",
      returnDirect = true)
  public String generateExpressInvoice(
      @ToolParam(description = "Invoice line item.") String item,
      @ToolParam(description = "Whole-dollar charge.") Integer amount) {
    String invoice =
        "GARAGE-INVOICE | "
            + textOrFallback(item, "inspection")
            + " | $"
            + Math.max(amount != null ? amount : 0, 0);
    recordInvocation(
        "generate_express_invoice",
        Map.of("item", textOrFallback(item, "inspection"), "amount", amount != null ? amount : 0),
        Map.of("invoice", invoice));
    return invoice;
  }

  public List<Map<String, Object>> invocations() {
    synchronized (this.invocations) {
      return List.copyOf(this.invocations);
    }
  }

  private void recordInvocation(
      String tool, Map<String, Object> arguments, Map<String, Object> result) {
    Map<String, Object> invocation = new LinkedHashMap<>();
    invocation.put("tool", tool);
    invocation.put("at", Instant.now().toString());
    invocation.put("arguments", arguments);
    invocation.put("result", result);
    this.invocations.add(invocation);
  }

  private String sanitizeHeading(String title) {
    if (!StringUtils.hasText(title)) {
      return "Garage note";
    }
    return title.replace('\n', ' ').replace('\r', ' ').strip();
  }

  private int clamp(int value, int min, int max) {
    return Math.min(Math.max(value, min), max);
  }

  private String textOrFallback(String value, String fallback) {
    return StringUtils.hasText(value) ? value : fallback;
  }
}
