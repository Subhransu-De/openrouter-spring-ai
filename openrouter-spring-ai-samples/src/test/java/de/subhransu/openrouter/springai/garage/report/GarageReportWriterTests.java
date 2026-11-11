package de.subhransu.openrouter.springai.garage.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageRunRequests;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.ModeEvidence;
import de.subhransu.openrouter.springai.garage.SceneResults;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class GarageReportWriterTests {

  @TempDir Path output;

  record PrivatePayload(String prompt) {}

  private static final String CHAT_COMPLETIONS = "Chat Completions";

  private static final Set<String> RESPONSES_GAPS = Set.of("structured-output:OPENAI_RESPONSES",
      "streaming-tool-aggregation:OPENAI_RESPONSES", "connection-timeout:OPENAI_RESPONSES",
      "sync-retry:OPENAI_RESPONSES", "error-surfacing:OPENAI_RESPONSES", "extension-points:OPENAI_RESPONSES");

  private static final Set<OpenRouterRequestMode> CHAT = Set.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);

  private static final Set<OpenRouterRequestMode> RESPONSES = Set.of(OpenRouterRequestMode.OPENAI_RESPONSES);

  private static final Set<OpenRouterRequestMode> BOTH = Set.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
      OpenRouterRequestMode.OPENAI_RESPONSES);

  /**
   * The (feature:mode) pairs the registry does not track for a set of selected modes: each mode
   * feature names its own mode, and with both modes selected the embeddings and image generation
   * features belong to Chat Completions only.
   */
  private static final Map<Set<OpenRouterRequestMode>, Set<String>> UNTRACKED = Map.of(
      CHAT, Set.of("responses-mode:OPENAI_CHAT_COMPLETIONS"),
      RESPONSES, Set.of("chat-completions-mode:OPENAI_RESPONSES"),
      BOTH, Set.of("responses-mode:OPENAI_CHAT_COMPLETIONS", "chat-completions-mode:OPENAI_RESPONSES",
          "embeddings:OPENAI_RESPONSES", "image-generation:OPENAI_RESPONSES"));

  /** A documented request body, the modes it selects and the (feature:mode) pairs the registry cannot verify. */
  record Selection(String body, String documentedModes, Set<OpenRouterRequestMode> modes, Set<String> unsupported) {
  }

  static Stream<Arguments> documentedSelections() {
    return Stream.of(selection("empty body", "", CHAT_COMPLETIONS, CHAT, Set.of()),
        selection("chat only", "{\"requestModes\":[\"chat\"]}", CHAT_COMPLETIONS, CHAT, Set.of()),
        selection("responses only", "{\"requestModes\":[\"responses\"]}", "Responses", RESPONSES,
            RESPONSES_GAPS),
        selection("text", "{\"capabilities\":[\"text\"]}", "Both", BOTH, RESPONSES_GAPS),
        selection("text, chat only", "{\"capabilities\":[\"text\"],\"requestModes\":[\"chat\"]}",
            CHAT_COMPLETIONS, CHAT, Set.of()),
        selection("text, responses only", "{\"capabilities\":[\"text\"],\"requestModes\":[\"responses\"]}",
            "Responses", RESPONSES, RESPONSES_GAPS),
        selection("full", "{\"full\":true}", "Both", BOTH, RESPONSES_GAPS),
        selection("embedding", "{\"capabilities\":[\"embedding\"]}", CHAT_COMPLETIONS, CHAT, Set.of()),
        selection("vision", "{\"capabilities\":[\"vision\"]}", "Both", BOTH, RESPONSES_GAPS),
        selection("image", "{\"capabilities\":[\"image\"]}", CHAT_COMPLETIONS, CHAT, Set.of()));
  }

  private static Arguments selection(String name, String body, String documentedModes,
      Set<OpenRouterRequestMode> modes, Set<String> unsupported) {
    return Arguments.of(Named.of(name, new Selection(body, documentedModes, modes, unsupported)));
  }

  @ParameterizedTest
  @MethodSource("documentedSelections")
  void documentedSelectionsRetainRegistryAndModeOutcomes(Selection selection) throws Exception {
    GarageRunPlan command = GarageRunRequests.plan(selection.body());
    ObjectMapper mapper = new ObjectMapper();
    var reports = new GarageReportWriter(mapper, new GarageEvidence(),
        mock(GarageTelemetry.class), mock(GarageTransportEvidence.class))
        .write(this.output, command, List.of(), List.of());
    var registry = mapper.readTree(reports.json().toFile()).get("featureRegistry");
    String markdown = Files.readString(reports.markdown());
    assertThat(command.requestModes()).containsExactlyInAnyOrderElementsOf(selection.modes());
    assertThat(registry.size()).isEqualTo(GarageFeature.values().length);
    Map<String, String> statuses = new TreeMap<>();
    for (GarageFeature feature : GarageFeature.values()) {
      var row = registry.get(feature.ordinal());
      assertThat(row.get("id").stringValue()).as("registry row %s", feature.id()).isEqualTo(feature.id());
      assertThat(markdown).as("markdown report names %s", feature.id()).contains(feature.title());
      for (var status : row.get("modeStatuses")) {
        statuses.put(feature.id() + ":" + status.get("requestMode").stringValue(),
            status.get("status").stringValue());
      }
    }
    // One status per tracked feature and selected mode. The registry cannot verify the documented
    // gaps in a mode, and nothing else ran.
    Map<String, String> expected = new TreeMap<>();
    for (GarageFeature feature : GarageFeature.values()) {
      for (OpenRouterRequestMode mode : selection.modes()) {
        String key = feature.id() + ":" + mode;
        if (!UNTRACKED.get(selection.modes()).contains(key)) {
          expected.put(key, selection.unsupported().contains(key) ? "unsupported-in-mode" : "not-executed");
        }
      }
    }
    assertThat(statuses).isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("documentedSelections")
  void theGarageGuideDocumentsEachSelectionsRequestModes(Selection selection) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    while (root != null && !(Files.isRegularFile(root.resolve("README.md"))
        && Files.isDirectory(root.resolve(".github")))) {
      root = root.getParent();
    }
    assertThat(root).as("repository root above the module working directory").isNotNull();
    String garageGuide = Files.readString(root.resolve("docs/garage.md"));
    String label = selection.body().isEmpty() ? "Empty body" : "`" + selection.body() + "`";

    String[] row = Arrays.stream(garageGuide.split("\\R")).map(line -> line.split("\\|"))
        .filter(cells -> cells.length > 2 && cells[1].strip().equals(label)).findFirst()
        .orElseThrow(() -> new AssertionError("docs/garage.md has no row for " + label));

    assertThat(row[2].strip()).startsWith(selection.documentedModes());
  }

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
        payload, failure, List.of());
    GarageRunPlan command =
        GarageRunRequests.plan("{\"capabilities\":[\"text\"],\"topic\":\"" + secret + "\"}");

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
    GarageRunPlan command = GarageRunRequests.plan("{\"capabilities\":[\"text\"]}");
    SceneResult result = SceneResults.passed("digital-inspection", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_RESPONSES, Duration.ZERO, this.output, Map.of());
    List<String> missing = incomplete ? List.of("structured-output") : List.of();

    GarageReportWriter.ReportPaths reports = writer.write(this.output, command, List.of(result), missing);

    var json = mapper.readTree(reports.json().toFile());
    String expected = incomplete ? "failed" : "passed";
    assertThat(json.get("status").stringValue()).isEqualTo(expected);
    assertThat(json.get("incompleteFeatures").size()).isEqualTo(missing.size());
    assertThat(Files.readString(reports.markdown())).contains("# Run status: " + expected);
  }

  /** The outcome in the second mode, the feature status and the second mode's status that must result. */
  record ModeCase(GarageFeature feature, ModeEvidence.Outcome responses, String featureStatus,
      String responsesStatus) {
  }

  static Stream<Arguments> modeCases() {
    return Stream.of(
        mode("covered in both", GarageFeature.SYNCHRONOUS_CHAT, ModeEvidence.Outcome.COVERED, "covered",
            "covered"),
        mode("failed in Responses", GarageFeature.SYNCHRONOUS_CHAT, ModeEvidence.Outcome.FAILED, "partial",
            "failed"),
        mode("incomplete in Responses", GarageFeature.SYNCHRONOUS_CHAT, ModeEvidence.Outcome.INCOMPLETE,
            "partial", "incomplete"),
        mode("not executed in Responses", GarageFeature.SYNCHRONOUS_CHAT, ModeEvidence.Outcome.NOT_EXECUTED,
            "partial", "not-executed"),
        mode("unsupported in Responses", GarageFeature.STRUCTURED_OUTPUT, ModeEvidence.Outcome.NOT_EXECUTED,
            "partial", "unsupported-in-mode"));
  }

  private static Arguments mode(String name, GarageFeature feature, ModeEvidence.Outcome responses,
      String featureStatus, String responsesStatus) {
    return Arguments.of(Named.of(name, new ModeCase(feature, responses, featureStatus, responsesStatus)));
  }

  @ParameterizedTest
  @MethodSource("modeCases")
  void coverageRetainsEachModesOutcome(ModeCase scenario) throws Exception {
    GarageEvidence evidence = new GarageEvidence();
    GarageFeature feature = scenario.feature();
    ModeEvidence.record(evidence, feature, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
        ModeEvidence.Outcome.COVERED);
    ModeEvidence.record(evidence, feature, OpenRouterRequestMode.OPENAI_RESPONSES, scenario.responses());
    ObjectMapper mapper = new ObjectMapper();
    var writer = new GarageReportWriter(mapper, evidence, mock(GarageTelemetry.class),
        mock(GarageTransportEvidence.class));

    var reports = writer.write(this.output,
        GarageRunRequests.plan("{\"capabilities\":[\"text\"],\"requestModes\":[\"both\"]}"), List.of(),
        List.of());

    var registry = mapper.readTree(reports.json().toFile()).get("featureRegistry");
    var entry = StreamSupport.stream(registry.spliterator(), false)
        .filter(item -> feature.id().equals(item.get("id").stringValue())).findFirst().orElseThrow();
    assertThat(entry.get("status").stringValue()).isEqualTo(scenario.featureStatus());
    assertThat(entry.get("modeStatuses").get(0).get("status").stringValue()).isEqualTo("covered");
    assertThat(entry.get("modeStatuses").get(1).get("status").stringValue()).isEqualTo(scenario.responsesStatus());
    assertThat(Files.readString(reports.markdown())).contains("OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES",
        scenario.responsesStatus());
  }
}
