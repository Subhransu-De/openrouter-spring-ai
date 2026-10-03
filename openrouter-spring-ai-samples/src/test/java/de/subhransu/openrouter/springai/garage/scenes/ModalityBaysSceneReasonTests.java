package de.subhransu.openrouter.springai.garage.scenes;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.garage.evidence.SceneFailureReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModalityBaysSceneReasonTests {

  @Test
  void theFirstFailedBayDecidesTheReason() {
    var checkFailed = Map.<String, Object>of("bay", "triage_matcher/embeddings", "status", "failed");
    var providerError = Map.<String, Object>of("bay", "digital_inspection/image-input", "status", "failed",
        "reason", "provider-error");

    assertThat(ModalityBaysScene.firstFailureReason(List.of(checkFailed, providerError))).isNull();
    assertThat(ModalityBaysScene.firstFailureReason(List.of(providerError, checkFailed)))
        .isEqualTo(SceneFailureReason.PROVIDER_ERROR);
    assertThat(ModalityBaysScene.firstFailureReason(List.of())).isNull();
  }
}
