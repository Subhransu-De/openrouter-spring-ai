package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageRunRequests.request;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
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
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

// Keep synthetic failure inputs explicit in each test.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class GarageRunServiceTests {

  private static final String DYNO = "{\"scenes\":[\"dyno-tuning\"]}";

  @TempDir Path output;

  @Test
  void scenesExposeEveryRegistryFeature() throws Exception {
    GarageScene scene = scene();
    when(scene.features()).thenReturn(List.of(GarageFeature.values()));
    GarageRunService service = service(scene, new GarageEvidence(), writer());

    assertThat(service.scenes()).containsExactly(scene);
    assertThat(service.scenes().get(0).features()).containsExactly(GarageFeature.values());
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
  void recordedCostAloneNeverFailsARun() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      evidence.recordCost("synthetic-operation", 0.01);
      return SceneResult.passed("dyno-tuning", "synthetic-operation",
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of());
    });
    GarageRunService service = service(scene, evidence, writer());

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.PASSED);
    assertThat(run.recordedCostUsd()).isEqualTo(0.01);
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

  @ParameterizedTest
  @ValueSource(strings = {"responses", "chat", "both"})
  void recoveryEvidenceIsRequiredOnlyWhenItsModeRuns(String mode) throws Exception {
    GarageScene scene = scene();
    when(scene.id()).thenReturn("recovery-road-test");
    when(scene.features()).thenReturn(GarageFeature.forScene("recovery-road-test"));
    when(scene.execute(any())).thenReturn(SceneResult.passed("recovery-road-test",
        "synthetic-operation", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO,
        this.output, Map.of()));
    GarageRunService service = service(scene, new GarageEvidence(), writer());
    String selection = "{\"capabilities\":[\"text\"],"
        + "\"scenes\":[\"recovery-road-test\"],\"requestModes\":[\"" + mode + "\"]}";

    if ("responses".equals(mode)) {
      // Nothing would run, so a PASSED run would certify work that never happened.
      assertThatThrownBy(() -> service.start(request(selection)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("No selected scene runs in the selected request modes");
    }
    else {
      GarageRun run = finished(service, service.start(request(selection)));
      assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
      assertThat(run.incompleteFeatures()).contains("connection-timeout");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "image"})
  void sweepFileOmitsProviderErrorTextAndModelInput(String surface) throws Exception {
    String secret = "SYNTHETIC_PRIVATE_SENTINEL";
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
    List<String> entries = List.of(secret);

    GarageRun run = finished(service, service.startSweep("embedding".equals(surface)
        ? new GarageSweepRequest(entries, null, null, null)
        : new GarageSweepRequest(null, entries, null, null)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
    assertThat(run.message()).isEqualTo("1 sweep entries failed");
    Path sweepFile = run.files().get(surface);
    assertThat(sweepFile).isRegularFile().startsWith(run.directory());
    String report = Files.readString(sweepFile);
    assertThat(report).doesNotContain(secret).contains("failed", "[REDACTED]");
    var json = new ObjectMapper().readTree(report);
    assertThat(json.get("failed").intValue()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"covered", "failed", "incomplete", "not-executed"})
  void everySelectedModeNeedsCompleteEvidenceEvenWhenScenesReturnPassed(String responseStatus) throws Exception {
    GarageEvidence evidence = new GarageEvidence();
    GarageScene scene = scene();
    when(scene.features()).thenReturn(List.of(GarageFeature.STANDARD_SAMPLING));
    when(scene.execute(any())).thenAnswer(invocation -> {
      var context = invocation.getArgument(0, SceneContext.class);
      String mode = context.requestMode().name();
      String operation = evidence.newOperation(scene.id(), mode);
      String status = context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS ? "covered" : responseStatus;
      if (!"not-executed".equals(status)) {
        for (var level : EvidenceLevel.values()) {
          if (!"incomplete".equals(status) || level != EvidenceLevel.ASSERTED) {
            evidence.record(GarageFeature.STANDARD_SAMPLING, operation, mode, level, "status", "passed");
          }
        }
        if ("failed".equals(status)) {
          evidence.error(GarageFeature.STANDARD_SAMPLING, operation, mode, new IllegalStateException("synthetic"));
        }
      }
      return SceneResult.passed(scene.id(), operation, context.requestMode(), Duration.ZERO, this.output, Map.of());
    });
    GarageReportWriter writer = writer();
    GarageRunService service = service(scene, evidence, writer);

    GarageRun run = finished(service, service.start(request(
        "{\"capabilities\":[\"text\"],\"scenes\":[\"dyno-tuning\"],\"requestModes\":[\"both\"]}")));

    if ("covered".equals(responseStatus)) {
      assertThat(run.status()).isEqualTo(GarageRun.Status.PASSED);
    } else {
      assertThat(run.status()).isEqualTo(GarageRun.Status.FAILED);
      assertThat(run.message()).contains("0 scene runs failed and 1 features");
    }
    verify(writer).write(any(), any(), any(), eq("covered".equals(responseStatus) ? List.of() : List.of("standard-sampling")));
  }

  @Test
  void runSettingsNeverLeakIntoTheNextRun() throws Exception {
    GarageScene scene = scene();
    List<GarageProperties> seen = new ArrayList<>();
    when(scene.execute(any())).thenAnswer(invocation -> {
      SceneContext context = invocation.getArgument(0, SceneContext.class);
      seen.add(context.properties());
      return SceneResult.passed("dyno-tuning", "synthetic-operation",
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of());
    });
    GarageRunService service = service(scene, new GarageEvidence(), writer());

    finished(service, service.start(request("{\"scenes\":[\"dyno-tuning\"],"
        + "\"limits\":{\"maxCompletionTokens\":256},\"provider\":{\"sort\":\"price\"}}")));
    finished(service, service.start(request(DYNO)));

    assertThat(seen).hasSize(2);
    assertThat(seen.get(0).getMaxCompletionTokens()).isEqualTo(256);
    assertThat(seen.get(0).getProviderSort()).isEqualTo("price");
    assertThat(seen.get(1).getMaxCompletionTokens()).isEqualTo(900);
    assertThat(seen.get(1).getProviderSort()).isEqualTo("throughput");
  }

  @Test
  void onlyOneRunIsActiveAtATime() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResult.passed("dyno-tuning", "synthetic-operation",
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
    when(scene.execute(any())).thenReturn(SceneResult.passed("dyno-tuning", "synthetic-operation",
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
    GarageRunService service = service(scene(), new GarageEvidence(), writer());

    assertThatThrownBy(() -> service.start(request("{\"scenes\":[\"paint-shop\"]}")))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paint-shop");
  }

  @Test
  void anUnexpectedFailureEndsTheRunAsAnErrorAndFreesTheService() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenReturn(SceneResult.passed("dyno-tuning", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of()));
    GarageReportWriter writer = mock(GarageReportWriter.class);
    when(writer.write(any(), any(), any(), any())).thenThrow(new IOException("disk full"));
    GarageRunService service = service(scene, new GarageEvidence(), writer);

    GarageRun run = finished(service, service.start(request(DYNO)));

    assertThat(run.status()).isEqualTo(GarageRun.Status.ERROR);
    assertThat(run.message()).isEqualTo("IOException: disk full");
    assertThat(run.finishedAt()).isNotNull();
    assertThat(service.start(request(DYNO)).status()).isEqualTo(GarageRun.Status.RUNNING);
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
    when(scene.execute(any())).thenReturn(SceneResult.passed("digital-inspection",
        "synthetic-operation", OpenRouterRequestMode.OPENAI_RESPONSES, Duration.ZERO,
        this.output, Map.of("status", "unsupported-in-mode")));
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
