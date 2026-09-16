package de.subhransu.openrouter.springai.garage.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageProperties;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class GarageReportWriterTests {

  @TempDir Path output;

  record PrivatePayload(String prompt) {}

  @ParameterizedTest
  @ValueSource(strings = {"OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES"})
  void allReportFilesExcludeUntrustedPayloads(String mode) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    GarageEvidence evidence = new GarageEvidence();
    GarageTelemetry telemetry = mock(GarageTelemetry.class);
    GarageTransportEvidence transport = mock(GarageTransportEvidence.class);
    String secret = "SYNTHETIC_PRIVATE_SENTINEL";
    Map<String, Object> payload = Map.of(
        "input", "{\"concern\":\"" + secret + "\"}",
        "results", new Object[] {new PrivatePayload(secret), Map.of("job", secret)},
        "details", Map.of(secret, secret), "count", 3, "streaming", true);
    when(telemetry.observationSnapshot()).thenReturn(List.of(payload));
    when(transport.snapshot()).thenReturn(List.of(payload));
    String operation = evidence.newOperation("service-story", mode);
    evidence.event(operation, "service-story", "tool.attempted", payload);
    RuntimeException failure = new IllegalStateException(secret, new IllegalArgumentException(secret));
    evidence.error(GarageFeature.SYNCHRONOUS_CHAT, operation, mode, failure);
    SceneResult result = SceneResult.failed("service-story", operation,
        OpenRouterRequestMode.valueOf(mode), Duration.ofMillis(12), this.output.resolve(secret),
        payload, failure);
    GarageCommand command = GarageCommand.from(new String[] {"--text", "--topic=" + secret},
        new GarageProperties());

    var reports = new GarageReportWriter(mapper, evidence, telemetry, transport)
        .write(this.output, command, List.of(result), List.of());

    String jsonText = Files.readString(reports.json());
    String markdownText = Files.readString(reports.markdown());
    String readmeText = Files.readString(reports.readme());
    assertSoftly(softly -> {
      softly.assertThat(jsonText).as("JSON").doesNotContain(secret);
      softly.assertThat(markdownText).as("Markdown").doesNotContain(secret);
      softly.assertThat(readmeText).as("README").doesNotContain(secret);
    });
    var json = mapper.readTree(reports.json().toFile());
    assertThat(json.get("scenes").get(0).get("details").get("count").intValue()).isEqualTo(3);
    assertThat(json.get("scenes").get(0).get("status").stringValue()).isEqualTo("FAILED");
    assertThat(json.get("scenes").get(0).get("operationId").stringValue()).isEqualTo(operation);
    assertThat(json.get("featureEvidence").get(0).get("errors").get(0).get("type").stringValue())
        .isEqualTo(IllegalStateException.class.getName());
    assertThat(Files.readString(reports.markdown())).contains("FAILED", "service-story", "12");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reportStatusIncludesRequiredEvidenceCompleteness(boolean incomplete) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    GarageReportWriter writer = new GarageReportWriter(mapper, new GarageEvidence(),
        mock(GarageTelemetry.class), mock(GarageTransportEvidence.class));
    GarageCommand command = GarageCommand.from(new String[] {"--text"}, new GarageProperties());
    SceneResult result = SceneResult.passed("digital-inspection", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_RESPONSES, Duration.ZERO, this.output, Map.of());
    List<String> missing = incomplete ? List.of("structured-output") : List.of();

    GarageReportWriter.ReportPaths reports = writer.write(this.output, command, List.of(result), missing);

    var json = mapper.readTree(reports.json().toFile());
    String expected = incomplete ? "failed" : "passed";
    assertThat(json.get("status").stringValue()).isEqualTo(expected);
    assertThat(json.get("incompleteFeatures").size()).isEqualTo(missing.size());
    assertThat(Files.readString(reports.markdown())).contains("# Run status: " + expected);
  }

  @ParameterizedTest
  @ValueSource(strings = {"covered", "failed", "incomplete", "not-executed", "unsupported-in-mode"})
  void coverageRetainsEachModesOutcome(String responseStatus) throws Exception {
    GarageEvidence evidence = new GarageEvidence();
    GarageFeature feature = "unsupported-in-mode".equals(responseStatus)
        ? GarageFeature.STRUCTURED_OUTPUT : GarageFeature.SYNCHRONOUS_CHAT;
    for (var mode : OpenRouterRequestMode.values()) {
      String status = mode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS ? "covered" : responseStatus;
      if ("not-executed".equals(status) || "unsupported-in-mode".equals(status)) {
        continue;
      }
      String operation = evidence.newOperation(feature.sceneId(), mode.name());
      for (var level : EvidenceLevel.values()) {
        if (!"incomplete".equals(status) || level != EvidenceLevel.ASSERTED) {
          evidence.record(feature, operation, mode.name(), level, "status", "passed");
        }
      }
      if ("failed".equals(status)) {
        evidence.error(feature, operation, mode.name(), new IllegalStateException("synthetic"));
      }
    }
    ObjectMapper mapper = new ObjectMapper();
    var writer = new GarageReportWriter(mapper, evidence, mock(GarageTelemetry.class), mock(GarageTransportEvidence.class));
    var reports = writer.write(this.output,
        GarageCommand.from(new String[] {"--text", "--request-mode=both"}, new GarageProperties()), List.of(), List.of());
    var registry = mapper.readTree(reports.json().toFile()).get("featureRegistry");
    var entry = java.util.stream.StreamSupport.stream(registry.spliterator(), false)
        .filter(item -> feature.id().equals(item.get("id").stringValue())).findFirst().orElseThrow();
    assertThat(entry.get("status").stringValue()).isEqualTo("covered".equals(responseStatus) ? "covered" : "partial");
    assertThat(entry.get("modeStatuses").get(0).get("status").stringValue()).isEqualTo("covered");
    assertThat(entry.get("modeStatuses").get(1).get("status").stringValue()).isEqualTo(responseStatus);
    assertThat(Files.readString(reports.markdown())).contains("OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES", responseStatus);
  }
}
