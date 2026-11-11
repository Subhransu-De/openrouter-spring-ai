package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class GarageTelemetryTests {

  private final List<GarageTelemetryFixture> fixtures = new ArrayList<>();

  @AfterEach
  void closeFixtures() {
    this.fixtures.forEach(GarageTelemetryFixture::close);
  }

  private GarageTelemetry telemetry(GarageEvidence evidence) {
    GarageTelemetryFixture fixture = new GarageTelemetryFixture(evidence);
    this.fixtures.add(fixture);
    return fixture.telemetry();
  }

  @Test
  void completedPlainStreamDoesNotHidePendingToolRounds() {
    GarageEvidence evidence = new GarageEvidence();
    GarageTelemetry telemetry = telemetry(evidence);
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
        Offset.offset(0.000000001));
  }

  @Test
  void observedUsageCostsAreRetainedPerOperationAndZeroCostIsIgnored() {
    GarageEvidence evidence = new GarageEvidence();
    GarageTelemetry telemetry = telemetry(evidence);
    for (ChatModelObservationContext observation : List.of(context("operation-1", 0.002),
        context("operation-1", 0.003), context("operation-2", 0.004), context("operation-3", 0))) {
      telemetry.onStart(observation);
      telemetry.onStop(observation);
    }

    assertThat(evidence.costFor("operation-1")).isCloseTo(0.005, Offset.offset(0.000000000001));
    assertThat(evidence.recordedCostUsd()).isCloseTo(0.009, Offset.offset(0.000000000001));
    assertThat(evidence.costSnapshot())
        .containsOnlyKeys("operation-1", "operation-2")
        .containsEntry("operation-2", 0.004);
    assertThat(evidence.costFor("operation-3")).isZero();
  }

  @Test
  void resetClearsExpectedCountsAndUnrelatedOperationsDoNotBlock() {
    GarageTelemetry telemetry = telemetry(new GarageEvidence());
    telemetry.onStart(context("operation-1", 0));
    telemetry.reset();
    telemetry.onStart(context("unrelated", 0));
    ChatModelObservationContext completed = context("operation-1", 0);
    telemetry.onStart(completed);
    telemetry.onStop(completed);
    assertThat(telemetry.awaitObservationsFor("operation-1", Duration.ZERO)).hasSize(1);
  }

  @Test
  void callOutcomesFollowStartOrderAndSkipExpectedFailures() {
    GarageTelemetry telemetry = telemetry(new GarageEvidence());
    ChatModelObservationContext first = context("operation-1", 0);
    ChatModelObservationContext failed = context("operation-1", 0);
    failed.setError(new OpenRouterProtocolException("synthetic"));
    ChatModelObservationContext expected = context("operation-1", 0);
    expected.setError(new IllegalStateException(new TimeoutException()));
    telemetry.onStart(first);
    telemetry.onStart(failed);
    telemetry.expectFailure(() -> telemetry.onStart(expected));
    telemetry.onStop(expected);
    telemetry.onStop(failed);
    telemetry.onStop(first);

    assertThat(telemetry.callOutcomesFor("operation-1")).containsExactly("ok", "protocol-error");
    assertThat(telemetry.observationsFor("operation-1"))
        .extracting(observation -> observation.get("outcome"))
        .containsExactly("transport-error", "protocol-error", "ok");
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
  void awaitingBlocksUntilAReactiveObservationIsFinalized() throws Exception {
    GarageTelemetry telemetry = telemetry(new GarageEvidence());
    ChatModelObservationContext context = context("operation-1", 0);
    telemetry.onStart(context);
    AtomicReference<Thread> waiter = new AtomicReference<>();
    CompletableFuture<List<Map<String, Object>>> observations = CompletableFuture.supplyAsync(() -> {
      waiter.set(Thread.currentThread());
      return telemetry.awaitObservationsFor("operation-1", Duration.ofSeconds(30));
    });

    // Finalize only once the waiter is parked inside the await, so the stop can never come first.
    await().atMost(Duration.ofSeconds(30)).until(() -> waiter.get() != null
        && (waiter.get().getState() == Thread.State.WAITING
            || waiter.get().getState() == Thread.State.TIMED_WAITING));
    assertThat(observations).isNotDone();
    telemetry.onStop(context);

    assertThat(observations.get(30, TimeUnit.SECONDS)).singleElement()
        .satisfies(observation -> assertThat(observation.get("streaming")).isEqualTo(true));
  }
}
