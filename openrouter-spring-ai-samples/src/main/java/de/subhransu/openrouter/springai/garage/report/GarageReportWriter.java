package de.subhransu.openrouter.springai.garage.report;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Writes the JSON evidence bundle and Markdown capability contract after success or failure. */
@Component
public final class GarageReportWriter {

  private final ObjectMapper objectMapper;
  private final GarageEvidence evidence;
  private final GarageTelemetry telemetry;
  private final GarageTransportEvidence transportEvidence;

  GarageReportWriter(
      ObjectMapper objectMapper,
      GarageEvidence evidence,
      GarageTelemetry telemetry,
      GarageTransportEvidence transportEvidence) {
    this.objectMapper = objectMapper;
    this.evidence = evidence;
    this.telemetry = telemetry;
    this.transportEvidence = transportEvidence;
  }

  public ReportPaths write(
      Path runDirectory, GarageCommand command, List<SceneResult> results,
      List<String> incompleteFeatures) throws IOException {
    Files.createDirectories(runDirectory);
    List<Map<String, Object>> featureEvidence = this.evidence.featureSnapshot();
    List<Map<String, Object>> registry = registry(featureEvidence);
    double recordedCostUsd = this.evidence.recordedCostUsd();
    boolean budgetExceeded =
        command.maxCostUsd() != null && recordedCostUsd > command.maxCostUsd() + 0.000000001;
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("application", "garage");
    run.put("createdAt", Instant.now().toString());
    run.put(
        "status",
        results.stream().allMatch(result -> result.status() == SceneResult.Status.PASSED)
                && incompleteFeatures.isEmpty()
                && !budgetExceeded
            ? "passed"
            : "failed");
    run.put("recordedCostUsd", recordedCostUsd);
    run.put("maxCostUsd", command.maxCostUsd());
    run.put("costBudgetExceeded", budgetExceeded);
    run.put("incompleteFeatures", incompleteFeatures);
    run.put("costsByOperation", this.evidence.costSnapshot());
    run.put("command", commandEvidence(command));
    run.put("scenes", results.stream().map(SceneResult::asMap).toList());
    run.put("featureRegistry", registry);
    run.put("featureEvidence", featureEvidence);
    run.put("events", this.evidence.eventSnapshot());
    run.put("observations", this.telemetry.observationSnapshot());
    run.put("meters", this.telemetry.meterSnapshot());
    run.put("transport", this.transportEvidence.snapshot());

    Path json = runDirectory.resolve("garage-run.json");
    this.objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(json.toFile(), this.evidence.sanitizeForEvidence(run));
    Path report = runDirectory.resolve("capability-report.md");
    Files.writeString(report,
        "# Run status: " + run.get("status") + "\n\n"
            + "Required features lacking complete evidence: " + incompleteFeatures + "\n\n"
            + markdown(command, results, registry),
        StandardCharsets.UTF_8);
    Path readme = runDirectory.resolve("README.md");
    Files.writeString(
        readme,
        "# Garage evidence bundle\n\n"
            + "This directory was generated from the runtime feature registry. Prompt/topic text"
            + " and credentials are redacted.\n\n"
            + "- `garage-run.json`: correlated scenes, features, observations, meters, tool and"
            + " transport evidence.\n"
            + "- `capability-report.md`: human-readable feature contract.\n",
        StandardCharsets.UTF_8);
    return new ReportPaths(json, report, readme);
  }

  private List<Map<String, Object>> registry(List<Map<String, Object>> featureEvidence) {
    List<Map<String, Object>> registry = new ArrayList<>();
    for (GarageFeature feature : GarageFeature.values()) {
      List<Map<String, Object>> matching =
          featureEvidence.stream()
              .filter(item -> feature.id().equals(item.get("featureId")))
              .toList();
      boolean complete = matching.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("complete")));
      boolean failed =
          matching.stream()
              .map(item -> item.get("errors"))
              .filter(List.class::isInstance)
              .map(List.class::cast)
              .anyMatch(errors -> !errors.isEmpty());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", feature.id());
      item.put("title", feature.title());
      item.put("sceneId", feature.sceneId());
      item.put("kind", feature.kind().name());
      item.put("status", complete ? "covered" : failed ? "failed" : matching.isEmpty() ? "not-executed" : "incomplete");
      item.put("evidenceOperations", matching.size());
      registry.add(item);
    }
    return registry;
  }

  private Map<String, Object> commandEvidence(GarageCommand command) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("topic", "[REDACTED]");
    values.put("full", command.full());
    values.put("offlineContracts", command.offlineContracts());
    values.put("capabilities", command.capabilities());
    values.put("imageSurface", command.imageSurface());
    values.put("imageQuality", command.imageQuality());
    values.put("maxCostUsd", command.maxCostUsd());
    values.put("foremanModel", command.foremanModel());
    values.put("specialistModel", command.specialistModel());
    values.put("fallbackModels", command.fallbackModels());
    values.put("requestModes", command.requestModes());
    values.put("sceneIds", command.sceneIds());
    return values;
  }

  private String markdown(
      GarageCommand command, List<SceneResult> results, List<Map<String, Object>> registry) {
    StringBuilder report = new StringBuilder();
    report.append("# Garage capability report\n\n");
    report.append("- Created: ").append(Instant.now()).append('\n');
    report.append("- Capabilities: `").append(command.capabilities()).append("`\n");
    report.append("- Request modes: `").append(command.requestModes()).append("`\n");
    report.append("- Selected scenes: `").append(command.sceneIds()).append("`\n");
    report.append("- Image surface: `").append(command.imageSurface()).append("`\n");
    report.append("- Recorded inference cost: `$ ")
        .append(
            String.format(
                java.util.Locale.ROOT, "%.8f", this.evidence.recordedCostUsd()))
        .append("`\n");
    report.append("- Cost ceiling: `")
        .append(command.maxCostUsd() != null ? "$ " + command.maxCostUsd() : "not configured")
        .append("`\n");
    report.append("- Sensitive prompt/topic text retained: `no`\n\n");
    report.append("## Scene results\n\n");
    report.append("| Scene | Mode | Status | Duration (ms) | Error |\n");
    report.append("| --- | --- | --- | ---: | --- |\n");
    for (SceneResult result : results) {
      report.append("| `").append(result.sceneId()).append("` | `")
          .append(result.requestMode()).append("` | ")
          .append(result.status()).append(" | ")
          .append(result.duration().toMillis()).append(" | ")
          .append(result.error() != null ? escape(result.error()) : "")
          .append(" |\n");
    }
    report.append("\n## Feature contract\n\n");
    report.append("A feature is **covered** only when one operation produced configured, executed, observed, and asserted evidence with no error.\n\n");
    report.append("| Feature | Scene | Kind | Status | Evidence operations |\n");
    report.append("| --- | --- | --- | --- | ---: |\n");
    for (Map<String, Object> feature : registry) {
      report.append("| ").append(feature.get("title")).append(" | `")
          .append(feature.get("sceneId")).append("` | ")
          .append(feature.get("kind")).append(" | **")
          .append(feature.get("status")).append("** | ")
          .append(feature.get("evidenceOperations")).append(" |\n");
    }
    report.append("\n## Deliberately deferred library surface\n\n");
    report.append("- OpenRouter server web search and citation annotations.\n");
    report.append("- Audio/video modalities and model catalogue clients.\n");
    return report.toString();
  }

  private String escape(String value) {
    return value.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  public record ReportPaths(Path json, Path markdown, Path readme) {}
}
