package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

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
            "safe", "retained"));

    assertThat(evidence.operationPassed(operation)).isTrue();
    assertThat(evidence.featureSnapshot().get(0).get("complete")).isEqualTo(true);
    Map<String, Object> details =
        (Map<String, Object>) evidence.eventSnapshot().get(1).get("details");
    assertThat(details)
        .containsEntry("Authorization", "[REDACTED]")
        .containsEntry("topic", "[REDACTED]")
        .containsEntry("safe", "retained");
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

  static final class TypedTool {

    @Tool(name = "typed_tool", description = "Requires an integer.")
    String typed(@ToolParam(description = "Numeric severity.") Integer severity) {
      return severity.toString();
    }
  }
}
