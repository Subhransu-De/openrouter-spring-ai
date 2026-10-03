package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.evidence.SceneFailureReason;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Result retained even when a scene fails, so later scenes and reporting still run. */
public record SceneResult(
    String sceneId,
    String operationId,
    OpenRouterRequestMode requestMode,
    Status status,
    Duration duration,
    Path outputDirectory,
    Map<String, Object> details,
    @Nullable String error,
    @Nullable SceneFailureReason reason) {

  public static SceneResult passed(
      String sceneId,
      String operationId,
      OpenRouterRequestMode requestMode,
      Duration duration,
      Path outputDirectory,
      Map<String, Object> details) {
    return new SceneResult(
        sceneId,
        operationId,
        requestMode,
        Status.PASSED,
        duration,
        outputDirectory,
        new LinkedHashMap<>(details),
        null,
        null);
  }

  public static SceneResult failed(
      String sceneId,
      String operationId,
      OpenRouterRequestMode requestMode,
      Duration duration,
      Path outputDirectory,
      Throwable failure) {
    return failed(
        sceneId,
        operationId,
        requestMode,
        duration,
        outputDirectory,
        Map.of(),
        failure);
  }

  public static SceneResult failed(
      String sceneId,
      String operationId,
      OpenRouterRequestMode requestMode,
      Duration duration,
      Path outputDirectory,
      Map<String, Object> details,
      Throwable failure) {
    return failed(
        sceneId, operationId, requestMode, duration, outputDirectory, details, failure, false);
  }

  /**
   * Records a failed scene. {@code truncatedReply} is true when a model reply in the scene
   * stopped at its token limit, which usually explains a later failed check.
   */
  public static SceneResult failed(
      String sceneId,
      String operationId,
      OpenRouterRequestMode requestMode,
      Duration duration,
      Path outputDirectory,
      Map<String, Object> details,
      Throwable failure,
      boolean truncatedReply) {
    SceneFailureReason reason = SceneFailureReason.classify(failure, truncatedReply);
    String message = failure.getClass().getName() + ": " + failure.getMessage();
    if (reason == SceneFailureReason.TRUNCATED && truncatedReply) {
      message = "A model reply stopped at its token limit; " + message;
    }
    return new SceneResult(
        sceneId,
        operationId,
        requestMode,
        Status.FAILED,
        duration,
        outputDirectory,
        new LinkedHashMap<>(details),
        message,
        reason);
  }

  public Map<String, Object> asMap() {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("sceneId", this.sceneId);
    values.put("operationId", this.operationId);
    values.put("requestMode", this.requestMode.name());
    values.put("status", this.status.name());
    values.put("reason", this.reason != null ? this.reason.code() : null);
    values.put("durationMillis", this.duration.toMillis());
    values.put("outputDirectory", this.outputDirectory.toString());
    values.put("details", this.details);
    values.put("error", this.error);
    return values;
  }

  public enum Status {
    PASSED,
    FAILED
  }
}
