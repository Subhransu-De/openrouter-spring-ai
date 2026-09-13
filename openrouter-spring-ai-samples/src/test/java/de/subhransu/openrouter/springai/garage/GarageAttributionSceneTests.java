package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.scenes.AttributionScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

class GarageAttributionSceneTests {

  @Test
  void waitsForAttributionCostsBeforeReturning() {
    SceneContext context = mock(SceneContext.class, RETURNS_DEEP_STUBS);
    GarageEvidence evidence = new GarageEvidence();
    when(context.evidence()).thenReturn(evidence);
    when(context.requestMode()).thenReturn(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
    when(context.chatModel().call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(new ChatResponse(List.of()));
    when(context.chatModel().stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(Flux.just(new ChatResponse(List.of())));
    Map<String, Boolean> headers = Map.of("referer", true, "title", true, "categories", true);
    when(context.transportEvidence().forOperation(anyString())).thenReturn(List.of(
        Map.of("transport", "sync", "headerPresence", headers),
        Map.of("transport", "stream", "headerPresence", headers)));
    when(context.telemetry().awaitObservationsFor(anyString(), eq(Duration.ofSeconds(1))))
        .thenAnswer(invocation -> {
          evidence.recordCost(invocation.getArgument(0), 0.001);
          return List.of();
        });

    SceneResult result = new AttributionScene().execute(context);

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat(evidence.recordedCostUsd()).isEqualTo(0.001);
  }
}
