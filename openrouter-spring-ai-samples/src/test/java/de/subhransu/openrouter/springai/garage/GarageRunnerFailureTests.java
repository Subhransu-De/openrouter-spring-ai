package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.report.GarageReportWriter;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

class GarageRunnerFailureTests {

  @TempDir Path output;

  @Test
  void failedSceneReportsAndFailsWithoutAuto() throws Exception {
    GarageScene scene = scene();
    when(scene.execute(any())).thenThrow(new IllegalStateException("synthetic failure"));
    GarageReportWriter writer = writer();
    GarageRunner runner = runner(scene, new GarageEvidence(), writer);

    assertThatThrownBy(() -> runner.run("--scene=dyno-tuning", "--output=" + this.output))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("1 failed");
    verify(writer).write(any(), any(), any(), any());
  }

  @Test
  void exceededBudgetReportsAndFailsWithoutAuto() throws Exception {
    GarageScene scene = scene();
    GarageEvidence evidence = new GarageEvidence();
    when(scene.execute(any())).thenAnswer(invocation -> {
      evidence.recordCost("synthetic-operation", 0.01);
      return SceneResult.passed("dyno-tuning", "synthetic-operation",
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO, this.output, Map.of());
    });
    GarageReportWriter writer = writer();
    GarageRunner runner = runner(scene, evidence, writer);

    assertThatThrownBy(() -> runner.run("--scene=dyno-tuning", "--max-cost-usd=0.002",
        "--output=" + this.output))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("recorded cost was $0.01000000");
    verify(writer).write(any(), any(), any(), any());
  }

  @Test
  void responsesOnlyAllowsUnsupportedStructuredOutput() throws Exception {
    GarageScene scene = structuredOutputScene();
    GarageReportWriter writer = writer();

    runner(scene, new GarageEvidence(), writer).run("--text", "--scene=digital-inspection",
        "--request-mode=responses", "--output=" + this.output);

    verify(writer).write(any(), any(), any(), eq(List.of()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"chat", "both"})
  void chatModesStillRequireStructuredOutputEvidence(String mode) throws Exception {
    GarageReportWriter writer = writer();
    GarageRunner runner = runner(structuredOutputScene(), new GarageEvidence(), writer);

    assertThatThrownBy(() -> runner.run("--text", "--scene=digital-inspection",
        "--request-mode=" + mode, "--output=" + this.output))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("structured-output");
    verify(writer).write(any(), any(), any(), eq(List.of("structured-output")));
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

  @ParameterizedTest
  @ValueSource(strings = {"responses", "chat", "both"})
  void recoveryEvidenceIsRequiredOnlyWhenItsModeRuns(String mode) throws Exception {
    GarageScene scene = scene();
    when(scene.id()).thenReturn("recovery-road-test");
    when(scene.features()).thenReturn(GarageFeature.forScene("recovery-road-test"));
    when(scene.execute(any())).thenReturn(SceneResult.passed("recovery-road-test",
        "synthetic-operation", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, Duration.ZERO,
        this.output, Map.of()));
    GarageReportWriter writer = writer();
    GarageRunner runner = runner(scene, new GarageEvidence(), writer);
    String[] args = {"--text", "--scene=recovery-road-test", "--request-mode=" + mode,
        "--output=" + this.output};

    if ("responses".equals(mode)) {
      runner.run(args);
      verify(writer).write(any(), any(), any(), eq(List.of()));
    }
    else {
      assertThatThrownBy(() -> runner.run(args)).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("connection-timeout");
    }
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

  private GarageRunner runner(GarageScene scene, GarageEvidence evidence, GarageReportWriter writer) {
    return new GarageRunner(mock(ChatModel.class), mock(EmbeddingModel.class), mock(ImageModel.class),
        new GarageProperties(), mock(GarageOptionsFactory.class), new ObjectMapper(),
        new MockEnvironment().withProperty("spring.ai.openrouter.api-key", "synthetic-test-key"),
        List.of(scene), evidence, mock(GarageTelemetry.class),
        mock(GarageTransportEvidence.class), ObservationRegistry.create(), writer);
  }
}
