package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.RetryPolicy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

class GarageModalityTelemetryTests {

  private static final String PRIVATE = "synthetic-private-payload";
  private static final String SCENE = "modality-bays";
  private final GarageEvidence evidence = new GarageEvidence();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final GarageTelemetry telemetry = new GarageTelemetry(this.meters, this.evidence);
  private final ObservationRegistry registry = ObservationRegistry.create();
  private final OpenRouterApi api = mock(OpenRouterApi.class);

  GarageModalityTelemetryTests() {
    this.registry.observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(this.meters))
        .observationHandler(this.telemetry);
  }

  @Test
  void successfulModelCallsCaptureTheirOwnMetadataAndCostOnce() {
    when(this.api.embeddings(any())).thenReturn(new EmbeddingsResponse("list",
        List.of(new EmbeddingsResponse.EmbeddingData("embedding", 0, new float[] {1, 2})),
        PRIVATE, usage()));
    when(this.api.images(any())).thenReturn(new ImagesResponse(1L,
        List.of(new ImagesResponse.ImageData(PRIVATE, "image/png", PRIVATE)), usage()));
    String embeddings = operation();
    String images = operation();

    this.telemetry.observeOperation(embeddings, SCENE, () -> embeddingModel().call(embeddingRequest()));
    this.telemetry.observeOperation(images, SCENE, () -> imageModel().call(imagePrompt()));

    assertThat(completed(embeddings)).containsEntry("modality", "embedding")
        .containsEntry("inputCount", 1).containsEntry("dimensions", 2).containsEntry("count", 1);
    assertThat(completed(images)).containsEntry("modality", "image")
        .containsEntry("width", 256).containsEntry("height", 256).containsEntry("count", 1);
    assertThat(((Map<?, ?>) completed(embeddings).get("usage")).get("totalTokens")).isEqualTo(3);
    assertThat(completed(embeddings).get("error")).isEqualTo(Map.of());
    assertThat(completed(images).get("error")).isEqualTo(Map.of());
    assertThat(this.evidence.recordedCostUsd()).isEqualTo(0.02);
    assertThat(this.meters.find("gen_ai.client.operation").timers()).hasSize(2)
        .allSatisfy(timer -> assertThat(timer.count()).isEqualTo(1));
    assertSanitized();
  }

  @ParameterizedTest
  @ValueSource(strings = {"embedding", "image", "image-stream"})
  void failedCallsCompleteAndDoNotLeakErrorPayloads(String modality) {
    IllegalStateException failure = new IllegalStateException(PRIVATE);
    when(this.api.embeddings(any())).thenThrow(failure);
    when(this.api.images(any())).thenThrow(failure);
    when(this.api.imagesStream(any())).thenReturn(Flux.error(failure));
    String operation = operation();

    assertThatThrownBy(() -> this.telemetry.observeOperation(operation, SCENE, () -> {
      if ("embedding".equals(modality)) {
        return embeddingModel().call(embeddingRequest());
      }
      if ("image".equals(modality)) {
        return imageModel().call(imagePrompt());
      }
      return imageModel().stream(imagePrompt()).collectList().block(Duration.ofSeconds(2));
    })).isSameAs(failure);

    assertThat(completed(operation)).containsEntry("modality",
        "embedding".equals(modality) ? "embedding" : "image");
    assertThat(completed(operation).get("error")).isEqualTo(Map.of("type", failure.getClass().getName()));
    assertThat(this.evidence.recordedCostUsd()).isZero();
    assertSanitized();
  }

  @Test
  void imageStreamRetainsOperationAfterScopeEndsAndOtherCallsStart() {
    Sinks.Many<ImagesStreamEvent> source = Sinks.many().unicast().onBackpressureBuffer();
    when(this.api.imagesStream(any())).thenReturn(source.asFlux());
    String first = operation();
    String second = operation();
    Disposable subscription = this.telemetry.observeOperation(first, SCENE,
        () -> imageModel().stream(imagePrompt()).subscribe());
    try {
      assertThatThrownBy(() -> this.telemetry.awaitObservationsFor(first, Duration.ZERO))
          .isInstanceOf(IllegalStateException.class);
      this.telemetry.observeOperation(second, SCENE, () -> {
        source.tryEmitNext(new ImagesStreamEvent(ImagesStreamEvent.PARTIAL_IMAGE, 0,
            PRIVATE, "image/png", 1L, usage(), null));
        source.tryEmitNext(new ImagesStreamEvent(ImagesStreamEvent.COMPLETED, null,
            PRIVATE, "image/png", 1L, usage(), null));
        source.tryEmitComplete();
        return null;
      });
      assertThat(completed(first)).containsEntry("modality", "image").containsEntry("count", 1);
      assertThat(this.telemetry.observationsFor(second)).isEmpty();
      assertThat(this.evidence.costFor(first)).isEqualTo(0.01);
      assertThat(this.evidence.costFor(second)).isZero();
    } finally {
      subscription.dispose();
    }
    assertSanitized();
  }

  @Test
  void cancellationCompletesAndScopeRestoresAfterFailure() {
    when(this.api.imagesStream(any())).thenReturn(Flux.never());
    String operation = operation();
    Disposable subscription = this.telemetry.observeOperation(operation, SCENE,
        () -> imageModel().stream(imagePrompt()).subscribe());
    subscription.dispose();
    assertThat(completed(operation)).containsEntry("modality", "image").doesNotContainKey("usage");

    assertThatThrownBy(() -> this.telemetry.observeOperation(operation, SCENE, () -> {
      throw new IllegalStateException(PRIVATE);
    })).isInstanceOf(IllegalStateException.class);
    when(this.api.images(any())).thenReturn(new ImagesResponse(1L, List.of(), null));
    imageModel().call(imagePrompt());
    assertThat(this.telemetry.observationsFor(operation)).hasSize(1);
    assertThat(this.telemetry.observationSnapshot()).hasSize(2);
    assertThat(this.telemetry.supportsContext(new Observation.Context())).isFalse();
  }

  private Map<String, Object> completed(String operation) {
    List<Map<String, Object>> observations =
        this.telemetry.awaitObservationsFor(operation, Duration.ofSeconds(2));
    assertThat(observations).hasSize(1);
    Map<String, Object> observation = observations.get(0);
    assertThat(observation).containsEntry("operationId", operation).containsEntry("sceneId", SCENE);
    assertThat((Long) observation.get("durationNanos")).isNotNegative();
    assertThat(this.evidence.eventSnapshot()).anySatisfy(event -> {
      assertThat(event).containsEntry("operationId", operation).containsEntry("type", "observation.stopped");
      assertThat(event.get("details")).isEqualTo(observation);
    });
    return observation;
  }

  private void assertSanitized() {
    assertThat(this.telemetry.observationSnapshot().toString()).doesNotContain(PRIVATE);
    assertThat(this.telemetry.meterSnapshot().toString()).doesNotContain(PRIVATE);
    assertThat(this.evidence.eventSnapshot().toString()).doesNotContain(PRIVATE);
  }

  private String operation() {
    return this.evidence.newOperation(SCENE, "OPENAI_CHAT_COMPLETIONS");
  }

  private Usage usage() {
    return new Usage(1, 2, 3, null, null, 0.01, null, null, Map.of("private", PRIVATE));
  }

  private OpenRouterEmbeddingModel embeddingModel() {
    return OpenRouterEmbeddingModel.builder().openRouterApi(this.api)
        .observationRegistry(this.registry).retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build();
  }

  private OpenRouterImageModel imageModel() {
    return OpenRouterImageModel.builder().openRouterApi(this.api)
        .observationRegistry(this.registry).retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build())).build();
  }

  private EmbeddingRequest embeddingRequest() {
    return new EmbeddingRequest(List.of(PRIVATE),
        OpenRouterEmbeddingOptions.builder().model(PRIVATE).dimensions(2).user(PRIVATE).build());
  }

  private ImagePrompt imagePrompt() {
    return new ImagePrompt(PRIVATE,
        OpenRouterImageOptions.builder().model(PRIVATE).width(256).height(256).build());
  }
}
