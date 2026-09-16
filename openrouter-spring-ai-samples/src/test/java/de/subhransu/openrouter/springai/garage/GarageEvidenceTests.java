package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.ObjectMapper;

class GarageEvidenceTests {

  @Test
  void retainsIncrementalCostsByOperation() {
    GarageEvidence evidence = new GarageEvidence();

    evidence.recordCost("operation-1", 0.002);
    evidence.recordCost("operation-1", 0.003);
    evidence.recordCost("operation-2", 0.004);

    assertThat(evidence.costFor("operation-1")).isCloseTo(0.005, within(0.000000000001));
    assertThat(evidence.recordedCostUsd()).isCloseTo(0.009, within(0.000000000001));
    assertThat(evidence.costSnapshot())
        .containsExactly(
            Map.entry("operation-1", 0.005), Map.entry("operation-2", 0.004));
  }

  @Test
  void registryHasOneUniqueEntryForEveryAuditedFeature() {
    assertThat(GarageFeature.values()).hasSize(35);
    assertThat(List.of(GarageFeature.values()).stream().map(GarageFeature::id))
        .doesNotHaveDuplicates();
    assertThat(List.of(GarageFeature.values()).stream().map(GarageFeature::sceneId).distinct())
        .containsExactlyInAnyOrder(
            "service-story",
            "streaming-dispatch",
            "express-invoice",
            "digital-inspection",
            "modality-bays",
            "routing-lane",
            "attribution-check-in",
            "dyno-tuning",
            "recovery-road-test");
  }

  @Test
  @SuppressWarnings("unchecked")
  void fourEvidenceLevelsAreRequiredAndSensitiveValuesAreRedacted() {
    GarageEvidence evidence = new GarageEvidence();
    String operation = evidence.newOperation("service-story", "CHAT");
    for (EvidenceLevel level : EvidenceLevel.values()) {
      evidence.record(
          GarageFeature.SYNCHRONOUS_CHAT,
          operation,
          "CHAT",
          level,
          level.name(),
          true);
    }
    evidence.event(
        operation,
        "service-story",
        "redaction.check",
        Map.of(
            "Authorization", "Bearer secret-token",
            "topic", "private customer concern",
            "count", 2));

    assertThat(evidence.operationPassed(operation)).isTrue();
    assertThat(evidence.featureSnapshot().get(0).get("complete")).isEqualTo(true);
    Map<String, Object> details =
        (Map<String, Object>) evidence.eventSnapshot().get(1).get("details");
    assertThat(details)
        .containsOnly(Map.entry("count", 2));
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
        .isInstanceOf(RuntimeException.class);

    assertThat(evidence.eventSnapshot())
        .extracting(event -> event.get("type"))
        .contains("tool.attempted", "tool.failed", "feature.error");
    assertThat(evidence.operationPassed(operation)).isFalse();
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void aSuccessfulOperationCannotHideAnotherIncompleteOrFailedOperation(boolean failed) {
    GarageEvidence evidence = new GarageEvidence();
    var mode = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;
    String complete = evidence.newOperation("service-story", mode.name());
    for (EvidenceLevel level : EvidenceLevel.values()) {
      evidence.record(GarageFeature.TOOL_LOOP, complete, mode.name(), level, "status", "passed");
    }
    String other = evidence.newOperation("service-story", mode.name());
    evidence.record(GarageFeature.TOOL_LOOP, other, mode.name(), EvidenceLevel.CONFIGURED, "status", "passed");
    if (failed) {
      evidence.error(GarageFeature.TOOL_LOOP, other, mode.name(), new IllegalStateException("synthetic"));
    }
    assertThat(evidence.coverageStatus(GarageFeature.TOOL_LOOP, mode)).isEqualTo(failed ? "failed" : "incomplete");
  }

  @Test
  void modeIndependentBaysRequireOnlyThePassThatExecutesThem() {
    var chat = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;
    var responses = OpenRouterRequestMode.OPENAI_RESPONSES;
    for (var feature : List.of(GarageFeature.EMBEDDINGS, GarageFeature.IMAGE_GENERATION)) {
      assertThat(feature.coverageModes(List.of(chat, responses))).containsExactly(chat);
      assertThat(feature.coverageModes(List.of(responses))).containsExactly(responses);
    }
    assertThat(GarageFeature.IMAGE_INPUT.coverageModes(List.of(chat, responses))).containsExactly(chat, responses);
    assertThat(GarageFeature.STREAMING_TOOL_AGGREGATION.supports(responses)).isFalse();
  }

  record PrivatePayload(String prompt) {}

  static final class TypedTool {

    @Tool(name = "typed_tool", description = "Requires an integer.")
    String typed(@ToolParam(description = "Numeric severity.") Integer severity) {
      return severity.toString();
    }
  }
}
