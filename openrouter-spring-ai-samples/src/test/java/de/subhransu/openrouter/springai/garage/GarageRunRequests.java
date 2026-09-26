package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunRequest;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Builds run requests and plans from the same JSON a client posts to {@code /api/runs}. */
public final class GarageRunRequests {

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private GarageRunRequests() {
  }

  public static GarageRunRequest request(String json) {
    return json.isBlank() ? GarageRunRequest.defaults() : MAPPER.readValue(json, GarageRunRequest.class);
  }

  public static GarageRunPlan plan(String json) {
    return plan(json, new GarageProperties());
  }

  public static GarageRunPlan plan(String json, GarageProperties settings) {
    return GarageRunPlan.from(request(json), settings);
  }
}
