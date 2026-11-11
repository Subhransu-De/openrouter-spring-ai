package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the results that a scene returns. A scene is the only production code that
 * creates a passed result, so tests outside the scenes package build one directly.
 */
public final class SceneResults {

  private SceneResults() {
  }

  public static SceneResult passed(String sceneId, String operationId, OpenRouterRequestMode requestMode,
      Duration duration, Path outputDirectory, Map<String, Object> details) {
    return new SceneResult(sceneId, operationId, requestMode, SceneResult.Status.PASSED, duration, outputDirectory,
        new LinkedHashMap<>(details), null, null);
  }

}
