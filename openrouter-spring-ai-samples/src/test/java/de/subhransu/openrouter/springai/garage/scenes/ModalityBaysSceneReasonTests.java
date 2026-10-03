package de.subhransu.openrouter.springai.garage.scenes;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.garage.evidence.SceneFailureReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModalityBaysSceneReasonTests {

  private static final Map<String, Object> CHECK_FAILED =
      Map.of("bay", "triage_matcher/embeddings", "status", "failed");
  private static final Map<String, Object> PROVIDER_ERROR =
      Map.of("bay", "digital_inspection/image-input", "status", "failed", "reason", "provider-error");
  private static final Map<String, Object> CUT_OFF =
      Map.of("bay", "paint_bay/chat-modalities", "status", "failed", "reason", "truncated");

  @Test
  void theFirstFailedBayDecidesTheReason() {
    assertThat(ModalityBaysScene.firstFailureReason(List.of(PROVIDER_ERROR, CHECK_FAILED)))
        .isEqualTo(SceneFailureReason.PROVIDER_ERROR);
    assertThat(ModalityBaysScene.firstFailureReason(List.of(CUT_OFF, PROVIDER_ERROR)))
        .isEqualTo(SceneFailureReason.TRUNCATED);
  }

  @Test
  void aFirstBayThatFailedItsOwnCheckIsNotRelabelledByLaterBays() {
    assertThat(ModalityBaysScene.firstFailureReason(List.of(CHECK_FAILED, PROVIDER_ERROR)))
        .isEqualTo(SceneFailureReason.CHECK_FAILED);
    assertThat(ModalityBaysScene.firstFailureReason(List.of(CHECK_FAILED, CUT_OFF)))
        .isEqualTo(SceneFailureReason.CHECK_FAILED);
  }

  @Test
  void noFailedBayHasNoReason() {
    assertThat(ModalityBaysScene.firstFailureReason(List.of())).isNull();
  }
}
