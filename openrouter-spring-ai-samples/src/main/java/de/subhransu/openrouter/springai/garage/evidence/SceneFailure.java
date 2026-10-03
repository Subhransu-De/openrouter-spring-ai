package de.subhransu.openrouter.springai.garage.evidence;

/**
 * A scene failure that already knows its reason, for scenes that catch the original
 * exception and summarize several checks in one message.
 */
public final class SceneFailure extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final SceneFailureReason reason;

  public SceneFailure(String message, SceneFailureReason reason) {
    super(message);
    this.reason = reason;
  }

  public SceneFailureReason reason() {
    return this.reason;
  }
}
