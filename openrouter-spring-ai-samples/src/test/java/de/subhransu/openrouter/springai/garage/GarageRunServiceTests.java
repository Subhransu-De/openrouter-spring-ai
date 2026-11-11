package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageRunRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.report.GarageReportWriter;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/**
 * Run lifecycle of {@link GarageRunService}: planning, exclusivity, failure handling and cleanup.
 * The scenes and the report writer are test doubles here, so a test that verifies a call to the
 * writer proves only that the service hands the finished run over; the report content is proved
 * by {@code GarageReportWriterTests} and the full-run integration tests. The scene list test
 * asserts the identity of its doubles because the service returns the registered beans.
 */
// Keep synthetic failure inputs explicit in each test.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class GarageRunServiceTests {

  private static final String DYNO = "{\"scenes\":[\"dyno-tuning\"]}";

  private static final String RECOVERY_RESPONSES = "{\"capabilities\":[\"text\"],"
      + "\"scenes\":[\"recovery-road-test\"],\"requestModes\":[\"responses\"]}";

  @TempDir Path output;

  @Test
  void scenesAreListedByIdAndTheFirstOfADuplicateIdWins() throws Exception {
    GarageScene second = namedScene("routing-lane");
    GarageScene first = namedScene("attribution-check-in");
    GarageScene duplicate = namedScene("routing-lane");
    GarageRunService service = new GarageRunService(mock(ChatModel.class), mock(EmbeddingModel.class),
        mock(ImageModel.class), new ObjectMapper(), environment(), List.of(second, first, duplicate),
        new GarageEvidence(), mock(GarageTelemetry.class), mock(GarageTransportEvidence.class),
        ObservationRegistry.create(), writer(), Runnable::run);

    assertThat(service.scenes()).containsExactly(first, second);
  }

  @Test
  void failedSceneIsReportedAsAFailedRun() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      String mode = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS.name();
      evidence.record(GarageFeature.STANDARD_SAMPLING, evidence.newOperation(scene.id(), mode),
          mode, EvidenceLevel.CONFIGURED, "status", "passed");
      throw new IllegalStateException("synthetic failure");
    });
    GarageReportWriter writer = writer();
    GarageRunService service = service(scene, evidence, writer);

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.message()).contains("1 scene runs failed");
    assertThat(run.scenes()).singleElement().satisfies(outcome -> {
      assertThat(outcome.sceneId()).isEqualTo("dyno-tuning");
      assertThat(outcome.status()).isEqualTo("FAILED");
    });
    assertThat(run.files()).containsKeys(GarageRun.EVIDENCE, GarageRun.REPORT);
    verify(writer).write(any(), any(), any(), any());
    assertThat(evidence.coverageStatus(GarageFeature.STANDARD_SAMPLING,
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)).isEqualTo("failed");
    assertThat(evidence.coverageStatus(GarageFeature.FULL_PROPERTY_BINDING,
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)).isEqualTo("not-executed");
  }

  @Test
  void aFailedSceneKeepsTheCostItsOperationRecorded() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      String operation = ModeEvidence.record(evidence, GarageFeature.STANDARD_SAMPLING,
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, ModeEvidence.Outcome.INCOMPLETE);
      recordCost(evidence, operation, 0.01);
      throw new IllegalStateException("invalid image");
    });
    GarageReportWriter writer = writer();
    GarageRunService service = service(scene, evidence, writer);

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.recordedCostUsd()).isCloseTo(0.01, within(1e-12));
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<SceneResult>> results = ArgumentCaptor.forClass(List.class);
    verify(writer).write(any(), any(), results.capture(), any());
    assertThat(results.getValue()).singleElement().satisfies(result -> {
      assertThat(result.status()).isEqualTo(SceneResult.Status.FAILED);
      assertThat(result.details()).containsEntry("costUsd", 0.01);
    });
  }

  @Test
  void recordedCostAloneNeverFailsARun() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      String operation = evidence.newOperation("dyno-tuning", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS.name());
      recordCost(evidence, operation, 0.01);
      return SceneResults.passed("dyno-tuning", operation, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
          Duration.ZERO, this.output, Map.of());
    });
    GarageRunService service = service(scene, evidence, writer());

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.PASSED);
    assertThat(run.recordedCostUsd()).isCloseTo(0.01, within(1e-12));
    assertThat(run.message()).isNull();
  }

  @Test
  void responsesOnlyAllowsUnsupportedStructuredOutput() throws Exception {
    GarageReportWriter writer = writer();
    GarageRunService service = service(structuredOutputScene(), new GarageEvidence(), writer);

    GarageRun run = finished(service, service.start(request("{\"capabilities\":[\"text\"],"
        + "\"scenes\":[\"digital-inspection\"],\"requestModes\":[\"responses\"]}")));

    assertThat(run.status()).isEqualTo(GarageRun.Status.PASSED);
    verify(writer).write(any(), any(), any(), eq(List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "both"})
  void chatModesStillRequireStructuredOutputEvidence(String mode) throws Exception {
    GarageReportWriter writer = writer();
    GarageRunService service = service(structuredOutputScene(), new GarageEvidence(), writer);

    GarageRun run = finished(service, service.start(request("{\"capabilities\":[\"text\"],"
        + "\"scenes\":[\"digital-inspection\"],\"requestModes\":[\"" + mode + "\"]}")));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.incompleteFeatures()).containsExactly("structured-output");
    verify(writer).write(any(), any(), any(), eq(List.of("structured-output")));
  }

  @Test
  void aResponsesOnlySelectionOfTheChatOnlyRecoveryContractsStartsNoRun() throws Exception {
    GarageRunService service = service(recoveryScene(), new GarageEvidence(), writer());

    // Nothing would run, so a PASSED run would certify work that never happened.
    assertThatThrownBy(() -> service.start(request(RECOVERY_RESPONSES)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("No selected scene runs in the selected request modes");
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "both"})
  void recoveryEvidenceIsRequiredWhenItsModeRuns(String mode) throws Exception {
    GarageRunService service = service(recoveryScene(), new GarageEvidence(), writer());

    GarageRun run = finished(service, service.start(request("{\"capabilities\":[\"text\"],"
        + "\"scenes\":[\"recovery-road-test\"],\"requestModes\":[\"" + mode + "\"]}")));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.incompleteFeatures()).contains("connection-timeout");
  }

  private static final String SWEEP_SECRET = "SYNTHETIC_PRIVATE_SENTINEL";

  static Stream<Arguments> sweepSurfaces() {
    List<String> entries = List.of(SWEEP_SECRET);
    return Stream.of(
        Arguments.of(Named.of("embedding", new GarageSweepRequest(entries, null, null, null)), "embedding"),
        Arguments.of(Named.of("image", new GarageSweepRequest(null, entries, null, null)), "image"));
  }

  @ParameterizedTest
  @MethodSource("sweepSurfaces")
  void sweepFileOmitsProviderErrorTextAndModelInput(GarageSweepRequest sweep, String surface) throws Exception {
    String secret = SWEEP_SECRET;
    EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.call(any(EmbeddingRequest.class)))
        .thenThrow(new IllegalStateException(secret, new IllegalArgumentException(secret)));
    ImageModel imageModel = mock(ImageModel.class);
    when(imageModel.call(any(ImagePrompt.class)))
        .thenThrow(new IllegalStateException(secret, new IllegalArgumentException(secret)));
    GarageRunService service = new GarageRunService(mock(ChatModel.class), model, imageModel,
        new ObjectMapper(), environment(), List.of(), new GarageEvidence(),
        mock(GarageTelemetry.class), mock(GarageTransportEvidence.class),
        ObservationRegistry.create(), writer(), Runnable::run);

    GarageRun run = finished(service, service.startSweep(sweep));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.message()).isEqualTo("1 sweep entries failed");
    Path sweepFile = run.files().get(surface);
    assertThat(sweepFile).isRegularFile().startsWith(run.directory());
    String report = Files.readString(sweepFile);
    assertThat(report).doesNotContain(secret).contains("failed", "[REDACTED]");
    var json = new ObjectMapper().readTree(report);
    assertThat(json.get("failed").intValue()).isEqualTo(1);
  }

  static Stream<Arguments> responsesEvidence() {
    return Stream.of(
        Arguments.of(Named.of("complete in both modes", ModeEvidence.Outcome.COVERED),
            GarageRun.Status.PASSED, List.of()),
        Arguments.of(Named.of("failed in Responses", ModeEvidence.Outcome.FAILED), GarageRun.Status.FAILED,
            List.of("standard-sampling")),
        Arguments.of(Named.of("incomplete in Responses", ModeEvidence.Outcome.INCOMPLETE),
            GarageRun.Status.FAILED, List.of("standard-sampling")),
        Arguments.of(Named.of("no evidence in Responses", ModeEvidence.Outcome.NOT_EXECUTED),
            GarageRun.Status.FAILED, List.of("standard-sampling")));
  }

  @ParameterizedTest
  @MethodSource("responsesEvidence")
  void everySelectedModeNeedsCompleteEvidenceEvenWhenScenesReturnPassed(ModeEvidence.Outcome responses,
      GarageRun.Status expectedStatus, List<String> expectedMissing) throws Exception {
    GarageEvidence evidence = new GarageEvidence();
    GarageScene scene = scene();
    when(scene.features()).thenReturn(List.of(GarageFeature.STANDARD_SAMPLING));
    when(scene.execute(any())).thenAnswer(invocation -> {
      var context = invocation.getArgument(0, SceneContext.class);
      ModeEvidence.Outcome outcome = context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
          ? ModeEvidence.Outcome.COVERED : responses;
      String operation = ModeEvidence.record(evidence, GarageFeature.STANDARD_SAMPLING, context.requestMode(),
          outcome);
      return SceneResults.passed(scene.id(), operation, context.requestMode(), Duration.ZERO, this.output, Map.of());
    });
    GarageReportWriter writer = writer();
    GarageRunService service = service(scene, evidence, writer);

    GarageRun run = finished(service, service.start(request(
        "{\"capabilities\":[\"text\"],\"scenes\":[\"dyno-tuning\"],\"requestModes\":[\"both\"]}")));

    assertThat(run.status()).isEqualTo(expectedStatus);
    assertThat(run.incompleteFeatures()).containsExactlyElementsOf(expectedMissing);
    verify(writer).write(any(), any(), any(), eq(expectedMissing));
  }

  @Test
  void runSettingsNeverLeakIntoTheNextRun() throws Exception {
    GarageScene scene = scene();
    List<GarageProperties> seen = new ArrayList<>();
    when(scene.execute(any())).thenAnswer(invocation -> {
      SceneContext context = invocation.getArgument(0, SceneContext.class);
      seen.add(context.properties());
      return SceneResults.passed("dyno-tuning", "synthetic-operation",
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of());
    });
    GarageRunService service = service(scene, new GarageEvidence(), writer());

    finished(service, service.start(request("{\"scenes\":[\"dyno-tuning\"],"
        + "\"limits\":{\"maxCompletionTokens\":256},\"provider\":{\"sort\":\"price\"}}")));
    finished(service, service.start(request(DYNO)));

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).getMaxCompletionTokens()).isEqualTo(256);
    assertThat(seen.get(0).getProviderSort()).isEqualTo("price");
    assertThat(seen.get(1).getMaxCompletionTokens()).isEqualTo(4096);
    assertThat(seen.get(1).getProviderSort()).isEqualTo("throughput");
  }

  @Test
  void onlyOneRunIsActiveAtATime() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResults.passed("dyno-tuning", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of()));
    List<Runnable> queued = new ArrayList<>();
    GarageRunService service = service(scene, new GarageEvidence(), writer(), environment(), queued::add);

    GarageRun first = service.start(request(DYNO));

    assertThat(first.status()).isEqualTo(GarageRun.Status.RUNNING);
    assertThatThrownBy(() -> service.start(request(DYNO)))
        .isInstanceOf(GarageRunService.RunActiveException.class)
        .hasMessageContaining(first.id());
    queued.remove(0).run();
    assertThat(service.find(first.id())).get().extracting(GarageRun::status)
        .isEqualTo(GarageRun.Status.PASSED);
    assertThat(service.start(request(DYNO)).id()).isNotEqualTo(first.id());
  }

  @Test
  void liveScenesNeedAnApiKeyButOfflineContractsDoNot() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResults.passed("dyno-tuning", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of()));
    MockEnvironment withoutKey = new MockEnvironment()
        .withProperty("spring.ai.openrouter.api-key", "garage-missing-api-key")
        .withProperty("garage.output-dir", this.output.toString());
    GarageScene modalityBays = scene();
    when(modalityBays.id()).thenReturn("modality-bays");
    GarageRunService service = new GarageRunService(mock(ChatModel.class), mock(EmbeddingModel.class),
        mock(ImageModel.class), new ObjectMapper(), withoutKey, List.of(scene, modalityBays),
        new GarageEvidence(), mock(GarageTelemetry.class), mock(GarageTransportEvidence.class),
        ObservationRegistry.create(), writer(), Runnable::run);

    assertThatThrownBy(() -> service.start(request("{\"capabilities\":[\"embedding\"]}")))
        .isInstanceOf(GarageRunService.MissingApiKeyException.class);
    assertThatThrownBy(() -> service.startSweep(
        new GarageSweepRequest(List.of("synthetic/embedding"), null, null, null)))
        .isInstanceOf(GarageRunService.MissingApiKeyException.class);
    assertThat(finished(service, service.start(request(DYNO))).status())
        .isEqualTo(GarageRun.Status.PASSED);
  }

  @Test
  void unknownScenesAreRejectedBeforeARunStarts() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResults.passed("dyno-tuning", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of()));
    GarageRunService service = service(scene, new GarageEvidence(), writer());

    assertThatThrownBy(() -> service.start(request("{\"scenes\":[\"paint-shop\"]}")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paint-shop");
    // The rejected selection left the service free: the next valid run is accepted, not a conflict.
    assertThat(finished(service, service.start(request(DYNO))).status()).isEqualTo(GarageRun.Status.PASSED);
  }

  @Test
  void aReportFailureEndsTheRunAsAnErrorButKeepsCompletedWorkAndFreesTheService() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      String operation = evidence.newOperation("dyno-tuning", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS.name());
      recordCost(evidence, operation, 0.02);
      return SceneResults.passed("dyno-tuning", operation, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
          Duration.ZERO, this.output, Map.of());
    });
    GarageReportWriter writer = mock(GarageReportWriter.class);
    when(writer.write(any(), any(), any(), any())).thenThrow(new IOException("disk full"));
    GarageRunService service = service(scene, evidence, writer);

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.ERROR);
    assertThat(run.message()).isEqualTo("IOException: disk full");
    assertThat(run.finishedAt()).isNotNull();
    assertThat(run.scenes()).singleElement().satisfies(outcome -> {
      assertThat(outcome.sceneId()).isEqualTo("dyno-tuning");
      assertThat(outcome.status()).isEqualTo("PASSED");
    });
    assertThat(run.recordedCostUsd()).isCloseTo(0.02, within(1e-12));
    assertThat(run.files()).isEmpty();
    assertThat(service.start(request(DYNO)).status()).isEqualTo(GarageRun.Status.RUNNING);
  }

  @Test
  void anOutputDirectoryThatCannotBeCreatedEndsTheRunAsAnErrorAndFreesTheService() throws Exception {
    // A regular file where the run directory's parent belongs makes directory creation fail on every platform.
    Path blocker = Files.writeString(this.output.resolve("not-a-directory"), "synthetic");
    MockEnvironment blocked = new MockEnvironment()
        .withProperty("spring.ai.openrouter.api-key", "synthetic-test-key")
        .withProperty("garage.output-dir", blocker.toString());
    GarageScene scene = scene();
    GarageRunService service = service(scene, new GarageEvidence(), writer(), blocked, Runnable::run);

    GarageRun started = service.start(request(DYNO));
    GarageRun run = service.find(started.id()).orElseThrow();

    assertThat(run.status()).isEqualTo(GarageRun.Status.ERROR);
    assertThat(run.message()).isNotBlank();
    assertThat(run.files()).isEmpty();
    verify(scene, never()).execute(any());
    // The failed run released the service, so the next request is accepted rather than a conflict.
    assertThat(service.start(request(DYNO)).id()).isNotEqualTo(started.id());
  }

  @Test
  void aSweepDocumentFailureKeepsTheCostAndTheDocumentsAlreadyWritten() throws Exception {
    EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    when(embeddingModel.call(any(EmbeddingRequest.class))).thenThrow(new IllegalStateException("synthetic"));
    ImageModel imageModel = mock(ImageModel.class);
    when(imageModel.call(any(ImagePrompt.class))).thenThrow(new IllegalStateException("synthetic"));
    List<Runnable> queued = new ArrayList<>();
    GarageRunService service = new GarageRunService(mock(ChatModel.class), embeddingModel, imageModel,
        new ObjectMapper(), environment(), List.of(), new GarageEvidence(), mock(GarageTelemetry.class),
        mock(GarageTransportEvidence.class), ObservationRegistry.create(), writer(), queued::add);

    GarageRun started = service.startSweep(new GarageSweepRequest(
        List.of("synthetic/embedding"), List.of("synthetic/image"), null, null));
    // A directory where the image document belongs makes only that write fail.
    Files.createDirectories(started.directory().resolve("image-sweep.json"));
    queued.remove(0).run();
    GarageRun run = service.find(started.id()).orElseThrow();

    assertThat(run.status()).isEqualTo(GarageRun.Status.ERROR);
    assertThat(run.message()).isNotBlank().doesNotContain("synthetic");
    assertThat(run.files()).containsOnlyKeys(GarageRun.EMBEDDING_SWEEP);
    assertThat(run.files().get(GarageRun.EMBEDDING_SWEEP)).isRegularFile();
  }

  @Test
  // The service under test owns the executor and shuts it down in destroy().
  @SuppressWarnings("PMD.CloseResource")
  void closingTheServiceWhileARunIsActiveInterruptsItAndEndsItAsAFailedRun() throws Exception {
    CountDownLatch inScene = new CountDownLatch(1);
    GarageScene scene = scene();
    when(scene.execute(any())).thenAnswer(invocation -> {
      inScene.countDown();
      // Blocks until the service closes its executor; the interrupt is the only way out.
      new CountDownLatch(1).await();
      throw new IllegalStateException("not reached");
    });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    GarageRunService service = service(scene, new GarageEvidence(), writer(), environment(), executor);

    GarageRun started = service.start(request(DYNO));
    assertThat(inScene.await(30, TimeUnit.SECONDS)).as("scene started").isTrue();
    assertThatThrownBy(() -> service.start(request(DYNO)))
        .isInstanceOf(GarageRunService.RunActiveException.class);
    service.destroy();

    // The run finishes on the executor thread; termination signals that its outcome is published.
    assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).as("executor terminated").isTrue();
    GarageRun run = service.find(started.id()).orElseThrow();
    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.finished()).isTrue();
    assertThat(run.scenes()).singleElement().satisfies(outcome -> {
      assertThat(outcome.sceneId()).isEqualTo("dyno-tuning");
      assertThat(outcome.status()).isEqualTo("FAILED");
    });
  }

  @Test
  // The service under test owns the executor and shuts it down in destroy().
  @SuppressWarnings("PMD.CloseResource")
  void closingTheServiceWithNoActiveRunLeavesItsFinishedRunsReadable() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResults.passed("dyno-tuning", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of()));
    ExecutorService executor = Executors.newSingleThreadExecutor();
    GarageRunService service = service(scene, new GarageEvidence(), writer(), environment(), executor);
    GarageRun started = service.start(request(DYNO));
    executor.submit(() -> { }).get(30, TimeUnit.SECONDS);

    service.destroy();

    assertThat(executor.isShutdown()).isTrue();
    assertThat(service.find(started.id())).get().extracting(GarageRun::status)
        .isEqualTo(GarageRun.Status.PASSED);
  }

  /** Records a model call's cost through the real telemetry, the only way cost reaches the evidence. */
  private static void recordCost(GarageEvidence evidence, String operation, double costUsd) {
    GarageTelemetryFixture fixture = new GarageTelemetryFixture(evidence);
    try {
      OpenRouterApi api = mock(OpenRouterApi.class);
      when(api.embeddings(any())).thenReturn(new EmbeddingsResponse("list",
          List.of(new EmbeddingsResponse.EmbeddingData("embedding", 0, new float[] {1})), "synthetic/embedding",
          new Usage(1, 0, 1, null, null, costUsd, null, null, Map.of())));
      OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder().openRouterApi(api)
          .observationRegistry(fixture.observations())
          .retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build();
      fixture.telemetry().observeOperation(operation, "dyno-tuning",
          () -> model.call(new EmbeddingRequest(List.of("synthetic"), OpenRouterEmbeddingOptions.builder().model("synthetic/embedding").build())));
      fixture.telemetry().awaitObservationsFor(operation, Duration.ofSeconds(5));
    } finally {
      fixture.close();
    }
  }

  private GarageRun finished(GarageRunService service, GarageRun started) {
    GarageRun run = service.find(started.id()).orElseThrow();
    assertThat(run.finished()).as("synchronous executor completes the run").isTrue();
    assertThat(run.directory()).startsWith(this.output);
    return run;
  }

  private GarageScene structuredOutputScene() throws Exception {
    GarageScene scene = scene();
    when(scene.id()).thenReturn("digital-inspection");
    when(scene.features()).thenReturn(List.of(GarageFeature.STRUCTURED_OUTPUT));
    when(scene.execute(any())).thenReturn(SceneResults.passed("digital-inspection",
        "synthetic-operation", OpenRouterRequestMode.OPENAI_RESPONSES, Duration.ZERO,
        this.output, Map.of("status", "unsupported-in-mode")));
    return scene;
  }

  private GarageScene recoveryScene() throws Exception {
    GarageScene scene = namedScene("recovery-road-test");
    when(scene.features()).thenReturn(GarageFeature.forScene("recovery-road-test"));
    when(scene.execute(any())).thenReturn(SceneResults.passed("recovery-road-test",
        "synthetic-operation", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO,
        this.output, Map.of()));
    return scene;
  }

  private GarageScene namedScene(String id) {
    GarageScene scene = scene();
    when(scene.id()).thenReturn(id);
    return scene;
  }

  private GarageScene scene() {
    GarageScene scene = mock(GarageScene.class);
    when(scene.id()).thenReturn("dyno-tuning");
    when(scene.title()).thenReturn("Synthetic contract");
    when(scene.features()).thenReturn(List.of());
    return scene;
  }

  private GarageReportWriter writer() throws Exception {
    GarageReportWriter writer = mock(GarageReportWriter.class);
    when(writer.write(any(), any(), any(), any())).thenReturn(new GarageReportWriter.ReportPaths(
        this.output.resolve("garage-run.json"), this.output.resolve("capability-report.md"),
        this.output.resolve("README.md")));
    return writer;
  }

  private MockEnvironment environment() {
    return new MockEnvironment()
        .withProperty("spring.ai.openrouter.api-key", "synthetic-test-key")
        .withProperty("garage.output-dir", this.output.toString());
  }

  private GarageRunService service(GarageScene scene, GarageEvidence evidence, GarageReportWriter writer) {
    return service(scene, evidence, writer, environment(), Runnable::run);
  }

  private GarageRunService service(GarageScene scene, GarageEvidence evidence,
      GarageReportWriter writer, MockEnvironment environment, Executor executor) {
    return new GarageRunService(mock(ChatModel.class), mock(EmbeddingModel.class), mock(ImageModel.class),
        new ObjectMapper(), environment, List.of(scene), evidence, mock(GarageTelemetry.class),
        mock(GarageTransportEvidence.class), ObservationRegistry.create(), writer, executor);
  }
}
