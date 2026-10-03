package de.subhransu.openrouter.springai.garage;

/**
 * Shared keys of the structured evidence maps that the Garage bays, scenes, and sweeps
 * emit and assert on.
 */
public final class GarageEvidenceKeys {

  public static final String STATUS = "status";
  public static final String PASSED = "passed";
  public static final String FAILED = "failed";
  public static final String ERROR = "error";
  public static final String REASON = "reason";
  public static final String OUTCOME = "outcome";
  public static final String USAGE = "usage";
  public static final String BAY = "bay";

  private GarageEvidenceKeys() {}
}
