package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/** Cost helpers used by CI profiles and their sanitized reports. */
public final class GarageCosts {

  private GarageCosts() {}

  public static double usage(Usage usage) {
    if (usage instanceof OpenRouterUsage openRouterUsage && openRouterUsage.getCost() != null) {
      return openRouterUsage.getCost();
    }
    return 0.0;
  }

  public static double usageMaps(Object value) {
    if (value instanceof Map<?, ?> map) {
      Object usage = map.get(GarageEvidenceKeys.USAGE);
      if (usage instanceof Map<?, ?> usageMap) {
        Object cost = usageMap.get("cost");
        if (cost instanceof Number number) {
          return number.doubleValue();
        }
      }
      return map.values().stream().mapToDouble(GarageCosts::usageMaps).sum();
    }
    if (value instanceof Iterable<?> iterable) {
      double total = 0.0;
      for (Object item : iterable) {
        total += usageMaps(item);
      }
      return total;
    }
    return 0.0;
  }

  public static double stream(List<ChatResponse> responses) {
    return responses.stream()
        .map(ChatResponse::getMetadata)
        .map(metadata -> metadata.getUsage())
        .mapToDouble(GarageCosts::usage)
        .max()
        .orElse(0.0);
  }

  public static double scenes(List<SceneResult> results) {
    return results.stream()
        .map(SceneResult::details)
        .map(details -> details.get("costUsd"))
        .filter(Number.class::isInstance)
        .map(Number.class::cast)
        .mapToDouble(Number::doubleValue)
        .sum();
  }
}
