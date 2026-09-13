package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GarageCostsTests {

  @Test
  void readsNativeUsageCost() {
    OpenRouterUsage usage = new OpenRouterUsage(10, 5, 15, 0, 0, 0.0000123, Map.of());

    assertThat(GarageCosts.usage(usage)).isEqualTo(0.0000123);
  }

  @Test
  void sumsNestedProbeCostsWithoutDoubleCountingParentUsage() {
    List<Map<String, Object>> probes =
        List.of(
            Map.of("usage", Map.of("cost", 0.01), "copy", Map.of("usage", Map.of("cost", 9.0))),
            Map.of("result", Map.of("usage", Map.of("cost", 0.02))));

    assertThat(GarageCosts.usageMaps(probes)).isEqualTo(0.03);
  }

  @Test
  void includesCostRetainedByAFailedScene() {
    SceneResult failed =
        SceneResult.failed(
            "paint-bay",
            "operation-1",
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            Duration.ZERO,
            Path.of("output"),
            Map.of("costUsd", 0.01),
            new IllegalStateException("invalid image"));

    assertThat(GarageCosts.scenes(List.of(failed))).isEqualTo(0.01);
  }
}
