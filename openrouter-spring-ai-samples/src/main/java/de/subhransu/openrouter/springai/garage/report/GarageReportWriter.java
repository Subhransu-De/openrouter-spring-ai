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

  @SuppressWarnings("unchecked")
  public ReportPaths write(
      Path runDirectory, GarageCommand command, List<SceneResult> results,
      List<String> incompleteFeatures) throws IOException {
    Files.createDirectories(runDirectory);
    List<Map<String, Object>> featureEvidence = this.evidence.featureSnapshot();
    List<Map<String, Object>> registry = registry(featureEvidence, command);
    double recordedCostUsd = this.evidence.recordedCostUsd();
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("application", "garage");
    run.put("createdAt", Instant.now().toString());
    run.put(
        "status",
        results.stream().allMatch(result -> result.status() == SceneResult.Status.PASSED)
                && incompleteFeatures.isEmpty()
            ? "passed"
            : "failed");
    run.put("recordedCostUsd", recordedCostUsd);
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

    Map<String, Object> diagnostic =
        (Map<String, Object>) this.evidence.sanitizeForEvidence(run);
    Path json = runDirectory.resolve("garage-run.json");
    this.objectMapper
        .writerWithDefaultPrettyPrinter()
        .writeValue(json.toFile(), diagnostic);
    Path report = runDirectory.resolve("capability-report.md");
    Files.writeString(report,
        "# Run status: " + diagnostic.get("status") + "\n\n"
            + "Required features lacking complete evidence: " + diagnostic.get("incompleteFeatures") + "\n\n"
            + markdown(diagnostic),
        StandardCharsets.UTF_8);
    Path readme = runDirectory.resolve("README.md");
    Files.writeString(
        readme,
        "# Garage evidence bundle\n\n"
            + "Diagnostic reports retain only allowlisted fields and fixed labels, numeric values"
            + " and booleans, plus generated operation identifiers. Free-form text, payloads, paths"
            + " and unknown objects are omitted or"
            + " redacted. Authored service records are separate outputs and may contain customer"
            + " content. Raw diagnostic payload retention is not supported.\n\n"
            + "- `garage-run.json`: correlated scenes, features, observations, meters, tool and"
            + " transport evidence.\n"
            + "- `capability-report.md`: human-readable feature contract.\n",
        StandardCharsets.UTF_8);
    return new ReportPaths(json, report, readme);
  }

  private List<Map<String, Object>> registry(List<Map<String, Object>> featureEvidence, GarageCommand command) {
    List<Map<String, Object>> registry = new ArrayList<>();
    for (GarageFeature feature : GarageFeature.values()) {
      List<Map<String, Object>> matching =
          featureEvidence.stream()
              .filter(item -> feature.id().equals(item.get("featureId")))
              .toList();
      List<Map<String, Object>> modeStatuses = feature.coverageModes(command.requestModes()).stream()
          .map(mode -> Map.<String, Object>of("requestMode", mode.name(),
              "status", this.evidence.coverageStatus(feature, mode)))
          .toList();
      List<Object> statuses = modeStatuses.stream().map(mode -> mode.get("status")).distinct().toList();
      String status = statuses.isEmpty() ? "not-executed"
          : statuses.size() == 1 ? statuses.get(0).toString()
          : statuses.contains("covered") ? "partial"
          : statuses.contains("failed") ? "failed" : "incomplete";
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", feature.id());
      item.put("title", feature.title());
      item.put("sceneId", feature.sceneId());
      item.put("kind", feature.kind().name());
      item.put("status", status);
      item.put("modeStatuses", modeStatuses);
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
    values.put("foremanModel", command.foremanModel());
    values.put("specialistModel", command.specialistModel());
    values.put("fallbackModels", command.fallbackModels());
    values.put("requestModes", command.requestModes());
    values.put("sceneIds", command.sceneIds());
    return values;
  }

  @SuppressWarnings("unchecked")
  private String markdown(Map<String, Object> diagnostic) {
    StringBuilder report = new StringBuilder();
    report.append("# Garage capability report\n\n");
    Map<String, Object> command = (Map<String, Object>) diagnostic.get("command");
    report.append("- Capabilities: `").append(command.get("capabilities")).append("`\n");
    report.append("- Request modes: `").append(command.get("requestModes")).append("`\n");
    report.append("- Selected scenes: `").append(command.get("sceneIds")).append("`\n");
    report.append("- Image surface: `").append(command.get("imageSurface")).append("`\n");
    report.append("- Recorded inference cost: `$ ").append(diagnostic.get("recordedCostUsd")).append("`\n");
    report.append("- Free-form diagnostic text and raw payloads retained: `no`\n\n");
    List<Map<String, Object>> results = (List<Map<String, Object>>) diagnostic.get("scenes");
    List<Map<String, Object>> registry = (List<Map<String, Object>>) diagnostic.get("featureRegistry");
    report.append("## Scene results\n\n");
    report.append("| Scene | Mode | Status | Duration (ms) | Error |\n");
    report.append("| --- | --- | --- | ---: | --- |\n");
    for (Map<String, Object> result : results) {
      report.append("| `").append(result.get("sceneId")).append("` | `")
          .append(result.get("requestMode")).append("` | ")
          .append(result.get("status")).append(" | ")
          .append(result.get("durationMillis")).append(" | ")
          .append(result.get("error") != null ? result.get("error") : "")
          .append(" |\n");
    }
    report.append("\n## Feature contract\n\n");
    report.append("A feature is **covered** only when every selected applicable mode has complete evidence and no failed or incomplete operation. Mixed outcomes remain visible as partial coverage.\n\n");
    report.append("| Feature | Scene | Kind | Status | Modes | Evidence operations |\n");
    report.append("| --- | --- | --- | --- | --- | ---: |\n");
    for (Map<String, Object> feature : registry) {
      List<Map<String, Object>> modes = (List<Map<String, Object>>) feature.get("modeStatuses");
      report.append("| ").append(feature.get("title")).append(" | `")
          .append(feature.get("sceneId")).append("` | ")
          .append(feature.get("kind")).append(" | **")
          .append(feature.get("status")).append("** | ")
          .append(String.join("; ", modes.stream()
              .map(mode -> mode.get("requestMode") + ": " + mode.get("status")).toList())).append(" | ")
          .append(feature.get("evidenceOperations")).append(" |\n");
    }
    report.append("\n## Deliberately deferred library surface\n\n");
    report.append("- OpenRouter server web search and citation annotations.\n");
    report.append("- Audio/video modalities and model catalogue clients.\n");
    return report.toString();
  }

  public record ReportPaths(Path json, Path markdown, Path readme) {}
}
