package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunRequest;
import de.subhransu.openrouter.springai.garage.scenes.AttributionScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/** The attribution scene over a real scene context; only the model and transport capture are doubles. */
class GarageAttributionSceneTests {

  private static final Map<String, Boolean> ALL_HEADERS =
      Map.of("referer", true, "title", true, "categories", true);

  private static final OpenRouterRequestMode MODE = OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;

  @TempDir Path output;

  @Test
  void sumsTheSyncCostAndTheStreamCostAndWaitsForItsObservations() {
    GarageTelemetry telemetry = mock(GarageTelemetry.class);
    SceneContext context = context(telemetry, ALL_HEADERS, ALL_HEADERS);

    SceneResult result = new AttributionScene().execute(context);

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat((Double) result.details().get("costUsd")).isCloseTo(0.003, within(1e-12));
    assertThat(result.details()).containsEntry("valuesRetained", false);
    assertThat(context.evidence().coverageStatus(GarageFeature.ATTRIBUTION_HEADERS, MODE)).isEqualTo("covered");
    verify(telemetry).awaitObservationsFor(eq(result.operationId()), any(Duration.class));
  }

  @Test
  void aTransportThatLostAnAttributionHeaderFailsTheSceneAndTheFeature() {
    Map<String, Boolean> withoutCategories = Map.of("referer", true, "title", true, "categories", false);
    SceneContext context = context(mock(GarageTelemetry.class), ALL_HEADERS, withoutCategories);

    assertThatThrownBy(() -> new AttributionScene().execute(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("stream attribution headers were not all observed");
    assertThat(context.evidence().coverageStatus(GarageFeature.ATTRIBUTION_HEADERS, MODE)).isEqualTo("failed");
  }

  @Test
  void aTransportWithNoCapturedRequestFailsBothDirections() {
    SceneContext context = context(mock(GarageTelemetry.class), null, null);

    assertThatThrownBy(() -> new AttributionScene().execute(context))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("sync attribution headers were not all observed; "
            + "stream attribution headers were not all observed");
  }

  private SceneContext context(GarageTelemetry telemetry, Map<String, Boolean> syncHeaders,
      Map<String, Boolean> streamHeaders) {
    ChatModel model = mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(responseCosting(0.001));
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(responseCosting(0.0005), responseCosting(0.002)));
    GarageTransportEvidence transport = mock(GarageTransportEvidence.class);
    List<Map<String, Object>> requests = new java.util.ArrayList<>();
    if (syncHeaders != null) {
      requests.add(Map.of("transport", "sync", "headerPresence", syncHeaders));
    }
    if (streamHeaders != null) {
      requests.add(Map.of("transport", "stream", "headerPresence", streamHeaders));
    }
    when(transport.forOperation(any())).thenReturn(requests);
    GarageProperties properties = new GarageProperties();
    return new SceneContext(GarageRunPlan.from(GarageRunRequest.defaults(), properties), MODE, this.output,
        model, ChatClient.builder(model).build(), properties, new GarageOptionsFactory(properties),
        new ObjectMapper(), new GarageEvidence(), telemetry, transport, ObservationRegistry.NOOP);
  }

  private static ChatResponse responseCosting(double cost) {
    return ChatResponse.builder().generations(List.of())
        .metadata(ChatResponseMetadata.builder().usage(new OpenRouterUsage(1, 1, 2, 0, 0, cost, Map.of())).build())
        .build();
  }

}
