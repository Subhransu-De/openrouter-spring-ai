package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class GarageTelemetryTests {

  @Test
  void completedPlainStreamDoesNotHidePendingToolRounds() {
    GarageEvidence evidence = new GarageEvidence();
    GarageTelemetry telemetry = new GarageTelemetry(new SimpleMeterRegistry(), evidence);
    ChatModelObservationContext plain = context("operation-1", 0.001);
    ChatModelObservationContext tool = context("operation-1", 0.002);
    ChatModelObservationContext followup = context("operation-1", 0.003);
    telemetry.onStart(plain);
    telemetry.onStop(plain);
    telemetry.onStart(tool);
    telemetry.onStart(followup);
    telemetry.onStop(tool);

    assertThatThrownBy(() -> telemetry.awaitObservationsFor("operation-1", Duration.ZERO))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("all observations");

    telemetry.onStop(followup);
    assertThat(telemetry.awaitObservationsFor("operation-1", Duration.ZERO)).hasSize(3);
    assertThat(evidence.recordedCostUsd()).isCloseTo(0.006,
        org.assertj.core.data.Offset.offset(0.000000001));
  }

  @Test
  void resetClearsExpectedCountsAndUnrelatedOperationsDoNotBlock() {
    GarageTelemetry telemetry = new GarageTelemetry(new SimpleMeterRegistry(), new GarageEvidence());
    telemetry.onStart(context("operation-1", 0));
    telemetry.reset();
    telemetry.onStart(context("unrelated", 0));
    ChatModelObservationContext completed = context("operation-1", 0);
    telemetry.onStart(completed);
    telemetry.onStop(completed);
    assertThat(telemetry.awaitObservationsFor("operation-1", Duration.ZERO)).hasSize(1);
  }

  private ChatModelObservationContext context(String operationId, double cost) {
    ChatModelObservationContext context = ChatModelObservationContext.builder()
        .prompt(new Prompt("synthetic", OpenRouterChatOptions.builder()
            .metadata(Map.of("operationId", operationId, "sceneId", "streaming-dispatch"))
            .build()))
        .provider("openrouter").streaming(true).build();
    context.setResponse(new ChatResponse(List.of(), ChatResponseMetadata.builder()
        .usage(new OpenRouterUsage(1, 1, 2, 0, 0, cost, Map.of())).build()));
    return context;
  }

  @Test
  void awaitsReactiveObservationFinalization() throws InterruptedException {
    GarageTelemetry telemetry =
        new GarageTelemetry(new SimpleMeterRegistry(), new GarageEvidence());
    OpenRouterChatOptions options =
        OpenRouterChatOptions.builder()
            .metadata(Map.of("operationId", "operation-1", "sceneId", "streaming-dispatch"))
            .build();
    ChatModelObservationContext context =
        ChatModelObservationContext.builder()
            .prompt(new Prompt("test", options))
            .provider("openrouter")
            .streaming(true)
            .build();
    telemetry.onStart(context);
    Thread finalizer =
        new Thread(
            () -> {
              LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
              telemetry.onStop(context);
            });
    finalizer.start();

    List<Map<String, Object>> observations =
        telemetry.awaitObservationsFor("operation-1", Duration.ofSeconds(1));
    finalizer.join();

    assertThat(observations)
        .singleElement()
        .satisfies(observation -> assertThat(observation.get("streaming")).isEqualTo(true));
  }
}
