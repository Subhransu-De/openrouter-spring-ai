package de.subhransu.openrouter.springai.garage.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SceneFailureReasonTests {

  private static final IllegalStateException CHECK = new IllegalStateException("synthetic check failed");

  @Test
  void aRecordedReasonWinsOverEveryCall() {
    var recorded = new SceneFailure("synthetic", SceneFailureReason.CHECK_FAILED);
    assertThat(SceneFailureReason.resolve(recorded, List.of("provider-error"))).isEqualTo(SceneFailureReason.CHECK_FAILED);
  }

  @Test
  void theFirstErroredCallExplainsALaterFailedCheck() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "truncated", "no-endpoint", "provider-error")))
        .isEqualTo(SceneFailureReason.NO_ENDPOINT);
  }

  @Test
  void anErroredCallOutranksTheThrownFailure() {
    var thrown = new OpenRouterProtocolException("synthetic");
    assertThat(SceneFailureReason.resolve(thrown, List.of("provider-error", "protocol-error")))
        .isEqualTo(SceneFailureReason.PROVIDER_ERROR);
  }

  @Test
  void theThrownFailureOutranksATruncatedCall() {
    var thrown = new IllegalStateException(new OpenRouterProtocolException("synthetic"));
    assertThat(SceneFailureReason.resolve(thrown, List.of("truncated", "error")))
        .isEqualTo(SceneFailureReason.PROTOCOL_ERROR);
  }

  @Test
  void aTruncatedCallExplainsAFailedCheck() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "error", "truncated"))).isEqualTo(SceneFailureReason.TRUNCATED);
  }

  @Test
  void completeCallsLeaveTheCheckToBlame() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "error"))).isEqualTo(SceneFailureReason.CHECK_FAILED);
    assertThat(SceneFailureReason.resolve(null, List.of())).isEqualTo(SceneFailureReason.CHECK_FAILED);
  }
}
