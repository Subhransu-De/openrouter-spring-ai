package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.execution.ToolExecutionException;
import tools.jackson.databind.ObjectMapper;

class GarageEvidenceTests {

  @Test
  void everyFeatureHasAUniqueId() {
    assertThat(List.of(GarageFeature.values()).stream().map(GarageFeature::id))
        .doesNotHaveDuplicates();
  }

  // Compares the scene that owns each feature with the scenes a full run plans. The context test
  // compares the registered scene beans with the same plan, so a feature owned by a scene that is
  // not planned, or a planned scene that owns no feature, fails only here.
  @Test
  void everyFeatureBelongsToAPlannedSceneAndEveryPlannedSceneOwnsFeatures() {
    assertThat(List.of(GarageFeature.values()).stream().map(GarageFeature::sceneId).distinct())
        .containsExactlyInAnyOrderElementsOf(GarageRunRequests.plan("{\"full\":true}").sceneIds());
  }

  @Test
  void aFeatureIsCompleteOnlyWhenAllFourEvidenceLevelsAreRecorded() {
    GarageEvidence evidence = new GarageEvidence();
    String operation = evidence.newOperation("service-story", "CHAT");
    for (EvidenceLevel level : EvidenceLevel.values()) {
      evidence.record(GarageFeature.SYNCHRONOUS_CHAT, operation, "CHAT", level, level.name(), true);
    }

    assertThat(evidence.featureSnapshot()).singleElement()
        .satisfies(feature -> assertThat(feature.get("complete")).isEqualTo(true));
  }

  @ParameterizedTest
  @EnumSource(EvidenceLevel.class)
  void aFeatureWithoutOneEvidenceLevelIsIncomplete(EvidenceLevel missing) {
    GarageEvidence evidence = new GarageEvidence();
    String operation = evidence.newOperation("service-story", "CHAT");
    for (EvidenceLevel level : EvidenceLevel.values()) {
      if (level != missing) {
        evidence.record(GarageFeature.SYNCHRONOUS_CHAT, operation, "CHAT", level, level.name(), true);
      }
    }

    assertThat(evidence.featureSnapshot()).singleElement()
        .satisfies(feature -> assertThat(feature.get("complete")).as("complete without " + missing).isEqualTo(false));
  }

  @Test
  @SuppressWarnings("unchecked")
  void sensitiveEventValuesAreRedacted() {
    GarageEvidence evidence = new GarageEvidence();
    String operation = evidence.newOperation("service-story", "CHAT");
    evidence.event(operation, "service-story", "redaction.check", Map.of(
        "Authorization", "Bearer secret-token",
        "topic", "private customer concern",
        "count", 2));

    assertThat(evidence.eventSnapshot()).filteredOn(event -> "redaction.check".equals(event.get("type")))
        .singleElement().satisfies(event -> assertThat((Map<String, Object>) event.get("details"))
            .containsOnly(Map.entry("count", 2)));
  }

  @Test
  void toolArgumentConversionFailuresAreRetainedAsEvidence() {
    GarageEvidence evidence = new GarageEvidence();
    String operation = evidence.newOperation("service-story", "CHAT");
    ToolCallback delegate = ToolCallbacks.from(new TypedTool())[0];
    ToolCallback callback =
        GarageToolCallback.wrap(
            delegate,
            evidence,
            operation,
            "service-story",
            "CHAT",
            GarageFeature.MIXED_TOOL_SCHEMAS);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> callback.call("{\"severity\":\"moderate\"}"))
        .isExactlyInstanceOf(ToolExecutionException.class);

    assertThat(evidence.eventSnapshot())
        .extracting(event -> event.get("type"))
        .contains("tool.attempted", "tool.failed", "feature.error");
    assertThat(evidence.featureSnapshot())
        .filteredOn(item -> operation.equals(item.get("operationId")))
        .isNotEmpty()
        .extracting(item -> item.get("complete"))
        .containsOnly(false);
  }

  @Test
  void nestedPayloadsAndUnknownObjectsFailClosed() {
    GarageEvidence evidence = new GarageEvidence();
    String secret = "SYNTHETIC_PRIVATE_SENTINEL";
    String operation = evidence.newOperation(secret, "OPENAI_CHAT_COMPLETIONS");
    Object payload = Map.of("operationId", operation, "results", new Object[] {
        "{\"concern\":\"" + secret + "\"}",
        new PrivatePayload(secret), java.nio.file.Path.of(secret), Map.of("input", secret),
        Map.of(secret, true), new char[] {'s', 'e', 'c', 'r', 'e', 't'},
        new Object() {
          @Override
          public String toString() {
            throw new AssertionError("must not stringify");
          }
        }});

    String serialized = new ObjectMapper()
        .writeValueAsString(evidence.sanitizeForEvidence(payload));

    assertThat(serialized).doesNotContain(secret, "secret").contains(operation, "[REDACTED]");
    assertThat(evidence.sanitizeForEvidence(new byte[] {83, 89, 78})).isEqualTo("[REDACTED]");
    assertThat(evidence.sanitizeForEvidence(evidence.sanitizeForEvidence(payload)))
        .isEqualTo(evidence.sanitizeForEvidence(payload));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void toolsStillReceiveAndReturnPayloadsWithoutRetainingThem(boolean withContext) {
    GarageEvidence evidence = new GarageEvidence();
    String secret = "SYNTHETIC_PRIVATE_SENTINEL";
    ToolCallback callback = GarageToolCallback.wrap(ToolCallbacks.from(new EchoTool())[0],
        evidence, evidence.newOperation("service-story", "OPENAI_CHAT_COMPLETIONS"),
        "service-story", "OPENAI_CHAT_COMPLETIONS", GarageFeature.TOOL_LOOP);
    String input = "{\"concern\":\"" + secret + "\"}";

    String output = withContext
        ? callback.call(input, new ToolContext(Map.of("job", secret)))
        : callback.call(input);

    assertThat(output).contains(secret);
    assertThat(new ObjectMapper().writeValueAsString(evidence.eventSnapshot()))
        .doesNotContain(secret);
    assertThat(evidence.eventSnapshot()).extracting(event -> event.get("type"))
        .contains("tool.attempted", "tool.succeeded");
  }

  static final class EchoTool {
    @Tool(description = "Synthetic echo.")
    String echo(String concern) {
      return concern;
    }
  }

  @Test
  void aSuccessfulOperationCannotHideAnotherIncompleteOperation() {
    GarageEvidence evidence = evidenceWithACompleteOperationAnd(ModeEvidence.Outcome.INCOMPLETE);

    assertThat(evidence.coverageStatus(GarageFeature.TOOL_LOOP, CHAT)).isEqualTo("incomplete");
  }

  @Test
  void aSuccessfulOperationCannotHideAnotherFailedOperation() {
    GarageEvidence evidence = evidenceWithACompleteOperationAnd(ModeEvidence.Outcome.FAILED);

    assertThat(evidence.coverageStatus(GarageFeature.TOOL_LOOP, CHAT)).isEqualTo("failed");
  }

  private static GarageEvidence evidenceWithACompleteOperationAnd(ModeEvidence.Outcome other) {
    GarageEvidence evidence = new GarageEvidence();
    ModeEvidence.record(evidence, GarageFeature.TOOL_LOOP, CHAT, ModeEvidence.Outcome.COVERED);
    ModeEvidence.record(evidence, GarageFeature.TOOL_LOOP, CHAT, other);
    return evidence;
  }

  private static final OpenRouterRequestMode CHAT = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;

  private static final OpenRouterRequestMode RESPONSES = OpenRouterRequestMode.OPENAI_RESPONSES;

  static Stream<Arguments> coverageModes() {
    return Stream.of(
        Arguments.of(Named.of("embeddings run once, in the first mode", GarageFeature.EMBEDDINGS),
            List.of(CHAT, RESPONSES), List.of(CHAT)),
        Arguments.of(Named.of("embeddings in a responses-only run", GarageFeature.EMBEDDINGS),
            List.of(RESPONSES), List.of(RESPONSES)),
        Arguments.of(Named.of("image generation run once, in the first mode", GarageFeature.IMAGE_GENERATION),
            List.of(CHAT, RESPONSES), List.of(CHAT)),
        Arguments.of(Named.of("image generation in a responses-only run", GarageFeature.IMAGE_GENERATION),
            List.of(RESPONSES), List.of(RESPONSES)),
        Arguments.of(Named.of("image input runs in every selected mode", GarageFeature.IMAGE_INPUT),
            List.of(CHAT, RESPONSES), List.of(CHAT, RESPONSES)));
  }

  @ParameterizedTest
  @MethodSource("coverageModes")
  void modeIndependentBaysRequireOnlyThePassThatExecutesThem(GarageFeature feature,
      List<OpenRouterRequestMode> selected, List<OpenRouterRequestMode> required) {
    assertThat(feature.coverageModes(selected)).containsExactlyElementsOf(required);
  }

  @Test
  void aChatOnlyFeatureDoesNotSupportResponses() {
    assertThat(GarageFeature.STREAMING_TOOL_AGGREGATION.supports(RESPONSES)).isFalse();
    assertThat(GarageFeature.STREAMING_TOOL_AGGREGATION.supports(CHAT)).isTrue();
  }

  record PrivatePayload(String prompt) {}

  static final class TypedTool {

    @Tool(name = "typed_tool", description = "Requires an integer.")
    String typed(@ToolParam(description = "Numeric severity.") Integer severity) {
      return severity.toString();
    }
  }
}
