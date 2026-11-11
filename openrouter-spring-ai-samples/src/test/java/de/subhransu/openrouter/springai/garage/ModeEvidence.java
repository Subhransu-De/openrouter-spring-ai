package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;

/**
 * Records the evidence a scene would leave for one feature in one request mode, so tests
 * can state each coverage outcome as data instead of rebuilding it in every class.
 */
public final class ModeEvidence {

  /** The coverage status that the recorded evidence must produce. */
  public enum Outcome {

    COVERED("covered"), FAILED("failed"), INCOMPLETE("incomplete"), NOT_EXECUTED("not-executed");

    private final String status;

    Outcome(String status) {
      this.status = status;
    }

    public String status() {
      return this.status;
    }

  }

  private ModeEvidence() {
  }

  /** Starts an operation and records evidence for it; returns the operation id. */
  public static String record(GarageEvidence evidence, GarageFeature feature, OpenRouterRequestMode mode,
      Outcome outcome) {
    String operation = evidence.newOperation(feature.sceneId(), mode.name());
    if (outcome == Outcome.NOT_EXECUTED) {
      return operation;
    }
    for (EvidenceLevel level : EvidenceLevel.values()) {
      if (outcome != Outcome.INCOMPLETE || level != EvidenceLevel.ASSERTED) {
        evidence.record(feature, operation, mode.name(), level, "status", "passed");
      }
    }
    if (outcome == Outcome.FAILED) {
      evidence.error(feature, operation, mode.name(), new IllegalStateException("synthetic"));
    }
    return operation;
  }

}
