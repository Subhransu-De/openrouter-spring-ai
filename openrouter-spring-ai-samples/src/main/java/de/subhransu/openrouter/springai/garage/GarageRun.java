package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Immutable snapshot of one Garage run or sweep; the service replaces it as the run advances. */
public record GarageRun(
    String id,
    Kind kind,
    Status status,
    Instant createdAt,
    @Nullable Instant finishedAt,
    GarageRunPlan plan,
    Path directory,
    List<SceneOutcome> scenes,
    List<String> incompleteFeatures,
    double recordedCostUsd,
    @Nullable String message,
    Map<String, Path> files) {

  public static final String EVIDENCE = "evidence";
  public static final String REPORT = "report";
  public static final String EMBEDDING_SWEEP = "embedding";
  public static final String IMAGE_SWEEP = "image";

  static GarageRun running(String id, Kind kind, GarageRunPlan plan, Path directory) {
    return new GarageRun(
        id, kind, Status.RUNNING, Instant.now(), null, plan, directory, List.of(), List.of(), 0.0,
        null, Map.of());
  }

  GarageRun completed(
      Status status,
      List<SceneOutcome> scenes,
      List<String> incompleteFeatures,
      double recordedCostUsd,
      @Nullable String message,
      Map<String, Path> files) {
    return new GarageRun(
        this.id, this.kind, status, this.createdAt, Instant.now(), this.plan, this.directory,
        List.copyOf(scenes), List.copyOf(incompleteFeatures), recordedCostUsd, message,
        Map.copyOf(files));
  }

  GarageRun failedToComplete(String message) {
    return completed(Status.ERROR, this.scenes, this.incompleteFeatures, this.recordedCostUsd,
        message, this.files);
  }

  public boolean finished() {
    return this.status != Status.RUNNING;
  }

  public enum Kind {
    SCENES,
    SWEEP
  }

  public enum Status {
    RUNNING,
    PASSED,
    FAILED,
    ERROR
  }

  public record SceneOutcome(
      String sceneId, String requestMode, String status, long durationMillis) {}
}
