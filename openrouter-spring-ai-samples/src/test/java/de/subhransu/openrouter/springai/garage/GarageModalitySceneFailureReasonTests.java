package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.SceneFailure;
import de.subhransu.openrouter.springai.garage.evidence.SceneFailureReason;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.scenes.ModalityBaysScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.http.HttpStatus;

/**
 * The scene reports why it failed from the first failed bay, in bay order. Bays are
 * independent, so a later bay's failure cannot stand in for the first one.
 */
class GarageModalitySceneFailureReasonTests {

  private static final String EMBEDDING_AND_IMAGE_BAYS = "{\"capabilities\":[\"embedding\",\"image\"],"
      + "\"image\":{\"surface\":\"sync\"}}";

  @TempDir Path output;

  private final GarageEvidence evidence = new GarageEvidence();

  private final GarageTelemetryFixture fixture = new GarageTelemetryFixture(this.evidence);

  @AfterEach
  void closeFixture() {
    this.fixture.close();
  }

  static Stream<Arguments> failedBays() {
    RuntimeException providerError = new OpenRouterApiException("synthetic provider failure",
        HttpStatus.BAD_GATEWAY, "{}");
    RuntimeException cutOff = new OpenRouterTruncatedResponseException("synthetic early end");
    return Stream.of(
        Arguments.of(Named.of("provider error, then a failed check", embeddingModelThrowing(providerError)),
            unreachedImageModel(), SceneFailureReason.PROVIDER_ERROR),
        Arguments.of(Named.of("cut off, then a provider error", embeddingModelThrowing(cutOff)),
            imageModelThrowing(providerError), SceneFailureReason.TRUNCATED),
        Arguments.of(Named.of("failed check, then a provider error", embeddingModelWithoutVectors()),
            imageModelThrowing(providerError), SceneFailureReason.CHECK_FAILED),
        Arguments.of(Named.of("failed check, then a cut off", embeddingModelWithoutVectors()),
            imageModelThrowing(cutOff), SceneFailureReason.CHECK_FAILED));
  }

  private static EmbeddingModel embeddingModelThrowing(RuntimeException failure) {
    EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.call(any(EmbeddingRequest.class))).thenThrow(failure);
    return model;
  }

  private static EmbeddingModel embeddingModelWithoutVectors() {
    EmbeddingModel model = mock(EmbeddingModel.class);
    when(model.call(any(EmbeddingRequest.class))).thenReturn(new EmbeddingResponse(List.of()));
    return model;
  }

  private static ImageModel imageModelThrowing(RuntimeException failure) {
    ImageModel model = mock(ImageModel.class);
    when(model.call(any(ImagePrompt.class))).thenThrow(failure);
    return model;
  }

  /** A model whose image bay is never reached, because the embedding bay failed first. */
  private static ImageModel unreachedImageModel() {
    return mock(ImageModel.class);
  }

  @ParameterizedTest
  @MethodSource("failedBays")
  void theFirstFailedBayDecidesTheReason(EmbeddingModel embeddingModel, ImageModel imageModel,
      SceneFailureReason expected) {
    ModalityBaysScene scene = new ModalityBaysScene(embeddingModel, imageModel);

    assertThatThrownBy(() -> scene.execute(context())).isInstanceOf(SceneFailure.class)
        .satisfies(failure -> assertThat(SceneFailureReason.resolve(failure, List.of())).isEqualTo(expected));
  }

  private SceneContext context() {
    GarageProperties properties = new GarageProperties();
    GarageRunPlan plan = GarageRunRequests.plan(EMBEDDING_AND_IMAGE_BAYS, properties);
    return new SceneContext(plan, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, this.output, null, null,
        properties, null, null, this.evidence, this.fixture.telemetry(), null, this.fixture.observations());
  }

}
