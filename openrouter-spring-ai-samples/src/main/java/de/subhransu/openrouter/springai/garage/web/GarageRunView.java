package de.subhransu.openrouter.springai.garage.web;

import de.subhransu.openrouter.springai.garage.GarageRun;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Response body for a run; it links to the stored files instead of exposing server paths. */
public record GarageRunView(
    String id,
    GarageRun.Kind kind,
    GarageRun.Status status,
    Instant createdAt,
    @Nullable Instant finishedAt,
    Map<String, Object> selection,
    List<GarageRun.SceneOutcome> scenes,
    List<String> incompleteFeatures,
    double recordedCostUsd,
    @Nullable String message,
    Map<String, String> links) {

  static String path(GarageRun run) {
    return "/api/runs/" + run.id();
  }

  static GarageRunView of(GarageRun run) {
    return new GarageRunView(
        run.id(),
        run.kind(),
        run.status(),
        run.createdAt(),
        run.finishedAt(),
        selection(run.plan()),
        run.scenes(),
        run.incompleteFeatures(),
        run.recordedCostUsd(),
        run.message(),
        links(run));
  }

  private static Map<String, Object> selection(GarageRunPlan plan) {
    Map<String, Object> selection = new LinkedHashMap<>();
    if (plan.isSweep()) {
      selection.put("embeddingModels", plan.embeddingSweepModels());
      selection.put("imageModels", plan.imageSweepModels());
      return selection;
    }
    selection.put("capabilities", plan.capabilities());
    selection.put("scenes", plan.sceneIds());
    selection.put("requestModes", plan.requestModes());
    selection.put("imageSurface", plan.imageSurface());
    selection.put("offlineContracts", plan.offlineContracts());
    return selection;
  }

  private static Map<String, String> links(GarageRun run) {
    String self = path(run);
    Map<String, String> links = new LinkedHashMap<>();
    links.put("self", self);
    run.files().keySet().stream().sorted().forEach(name -> links.put(name,
        GarageRun.EVIDENCE.equals(name) || GarageRun.REPORT.equals(name)
            ? self + "/" + name
            : self + "/sweeps/" + name));
    return links;
  }
}
