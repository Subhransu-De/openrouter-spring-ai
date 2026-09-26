package de.subhransu.openrouter.springai.garage.web;

import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import java.util.List;

/** One selectable scene and the feature ids its evidence covers. */
public record GarageSceneView(
    String id, String title, String description, boolean offline, List<String> features) {

  static GarageSceneView of(GarageScene scene) {
    return new GarageSceneView(
        scene.id(),
        scene.title(),
        scene.description(),
        scene.offline(),
        scene.features().stream().map(GarageFeature::id).toList());
  }
}
