package de.subhransu.openrouter.springai.garage.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * JSON body of {@code POST /api/runs}. A missing field keeps its {@code garage.*} default;
 * an empty list clears a list-valued default.
 */
public record GarageRunRequest(
    @Nullable List<String> capabilities,
    @Nullable List<String> scenes,
    @Nullable Boolean full,
    @Nullable Boolean offlineContracts,
    @Nullable List<String> requestModes,
    @Nullable String topic,
    @Nullable Models models,
    @Nullable Image image,
    @Nullable Limits limits,
    @Nullable String reasoningEffort,
    @Nullable Provider provider) {

  public static GarageRunRequest defaults() {
    return new GarageRunRequest(null, null, null, null, null, null, null, null, null, null, null);
  }

  public record Models(
      @Nullable String foreman,
      @Nullable String specialist,
      @Nullable String embedding,
      @Nullable String vision,
      @Nullable String image,
      @Nullable List<String> fallbacks) {}

  public record Image(@Nullable String surface, @Nullable String quality) {}

  public record Limits(
      @Nullable Integer maxCompletionTokens, @Nullable Integer specialistMaxCompletionTokens) {}

  public record Provider(
      @Nullable String sort,
      @Nullable Boolean requireParameters,
      @Nullable List<String> order,
      @Nullable List<String> ignore,
      @Nullable List<String> quantizations) {}
}
