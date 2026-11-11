package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatResponse;

class GarageCostsTests {

  @Test
  void usageCostIsTheNativeCostAndAbsentCostIsZero() {
    assertThat(GarageCosts.usage(new OpenRouterUsage(10, 5, 15, 0, 0, 0.0000123, Map.of())))
        .isCloseTo(0.0000123, within(1e-12));
    assertThat(GarageCosts.usage(new OpenRouterUsage(10, 5, 15, 0, 0, null, Map.of()))).isZero();
    assertThat(GarageCosts.usage(null)).isZero();
  }

  @Test
  void streamCostIsTheLargestRunningTotalNotTheSum() {
    ChatResponse first = response(0.001);
    ChatResponse last = response(0.004);

    assertThat(GarageCosts.stream(List.of(first, last, response(0.002)))).isCloseTo(0.004, within(1e-12));
    assertThat(GarageCosts.stream(List.of())).isZero();
  }

  @Test
  void sumsNestedProbeCostsWithoutDoubleCountingParentUsage() {
    List<Map<String, Object>> probes =
        List.of(
            Map.of("usage", Map.of("cost", 0.01), "copy", Map.of("usage", Map.of("cost", 9.0))),
            Map.of("result", Map.of("usage", Map.of("cost", 0.02))));

    assertThat(GarageCosts.usageMaps(probes)).isCloseTo(0.03, within(1e-12));
  }

  private static ChatResponse response(double cost) {
    return ChatResponse.builder().generations(List.of())
        .metadata(ChatResponseMetadata.builder()
            .usage(new OpenRouterUsage(1, 1, 2, 0, 0, cost, Map.of())).build())
        .build();
  }
}
