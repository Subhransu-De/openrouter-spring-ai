package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.scenes.ModalityBaysScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class GarageModalitySceneTelemetryTests {

  @TempDir Path output;
  private final GarageEvidence evidence = new GarageEvidence();
  private final GarageTelemetry telemetry = new GarageTelemetry(new SimpleMeterRegistry(), this.evidence);

  @Test
  void failedSceneWaitsForAsynchronousObservationCompletion() throws Exception {
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch stopping = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ObservationRegistry registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
      @Override
      public boolean supportsContext(Observation.Context context) {
        return telemetry.supportsContext(context);
      }
      @Override
      public void onStart(Observation.Context context) {
        telemetry.onStart(context);
        started.countDown();
      }
      @Override
      public void onStop(Observation.Context context) {
        stopping.countDown();
        try {
          assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(failure);
        }
        telemetry.onStop(context);
      }
    });
    Sinks.One<ImagesStreamEvent> source = Sinks.one();
    OpenRouterApi api = mock(OpenRouterApi.class);
    when(api.imagesStream(any())).thenReturn(source.asMono().flux());
    OpenRouterImageModel model = OpenRouterImageModel.builder().openRouterApi(api)
        .observationRegistry(registry).build();
    ModalityBaysScene scene = new ModalityBaysScene(mock(EmbeddingModel.class), model);
    FutureTask<Void> result = new FutureTask<>(() -> {
      assertThatThrownBy(() -> scene.execute(context(registry)))
          .isInstanceOf(IllegalStateException.class).hasMessageContaining("synthetic stream failure");
      return null;
    });
    Thread runner = new Thread(result);
    Thread completion = new Thread(
        () -> source.tryEmitError(new IllegalStateException("synthetic stream failure")));
    runner.start();
    try {
      assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
      completion.start();
      assertThat(stopping.await(5, TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
      release.countDown();
      result.get(5, TimeUnit.SECONDS);
      completion.join(5000);
      assertThat(completion.isAlive()).isFalse();
      assertThat(this.telemetry.observationSnapshot()).singleElement().satisfies(observation -> {
        assertThat(observation).containsEntry("modality", "image")
            .containsEntry("error", Map.of("type", IllegalStateException.class.getName()));
        assertThat(this.evidence.featureSnapshot()).singleElement().satisfies(feature ->
            assertThat(feature.get("operationId")).isEqualTo(observation.get("operationId")));
      });
    } finally {
      release.countDown();
      runner.interrupt();
      completion.interrupt();
      runner.join(5000);
      completion.join(5000);
    }
  }

  @Test
  void missingObservationDoesNotReplaceTheOriginalProbeFailure() {
    OpenRouterImageModel model = mock(OpenRouterImageModel.class);
    when(model.stream(any())).thenReturn(Flux.error(new IllegalArgumentException("synthetic request failure")));
    ModalityBaysScene scene = new ModalityBaysScene(mock(EmbeddingModel.class), model);
    assertThatThrownBy(() -> scene.execute(context(ObservationRegistry.NOOP)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic request failure", "Timed out waiting for all observations");
    assertThat(this.evidence.featureSnapshot()).singleElement().satisfies(feature ->
        assertThat(feature.get("complete")).isEqualTo(false));
  }

  private SceneContext context(ObservationRegistry registry) {
    GarageProperties properties = new GarageProperties();
    GarageCommand command = GarageCommand.from(new String[] {"--image", "--image-surface=streaming"}, properties);
    return new SceneContext(command, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, this.output,
        null, null, properties, null, null, this.evidence, this.telemetry, null, registry);
  }
}
