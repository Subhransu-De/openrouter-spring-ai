package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/** Run scheduling and per-run settings, observed at the OpenRouter wire. */
class GarageApiIntegrationTests extends MockedGarageIntegrationTest {

  private static final String ROUTING =
      "{\"capabilities\":[\"text\"],\"scenes\":[\"routing-lane\"],\"requestModes\":[\"chat\"]}";

  @Test
  void aSecondRunIsRejectedWhileOneIsActive() {
    OpenRouterMock.useScenario("routing-lane-slow");
    String active = start(ROUTING);

    assertThat(this.mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content(ROUTING))
        .hasStatus(HttpStatus.CONFLICT)
        .bodyJson().extractingPath("$.activeRun").isEqualTo(active);
    assertThat(this.mvc.get().uri(active + "/evidence")).hasStatus(HttpStatus.CONFLICT);
    assertThat(this.mvc.get().uri(active + "/report")).hasStatus(HttpStatus.CONFLICT);

    assertThat(awaitFinished(active).path("status").asString()).isEqualTo("PASSED");
    assertThat(runToCompletion(ROUTING).path("status").asString()).isEqualTo("PASSED");
  }

  @Test
  void requestOverridesReachTheWireForTheirRunOnly() {
    assertThat(runToCompletion("{\"capabilities\":[\"text\"],\"scenes\":[\"routing-lane\"],"
        + "\"requestModes\":[\"chat\"],\"limits\":{\"maxCompletionTokens\":256},"
        + "\"provider\":{\"sort\":\"price\",\"requireParameters\":true}}").path("status").asString())
        .isEqualTo("PASSED");
    JsonNode overridden = routingRequest();
    assertThat(overridden.path("max_completion_tokens").asInt()).isEqualTo(256);
    assertThat(overridden.path("provider").path("sort").asString()).isEqualTo("price");
    assertThat(overridden.path("provider").path("require_parameters").asBoolean()).isTrue();

    OpenRouterMock.reset();
    assertThat(runToCompletion(ROUTING).path("status").asString()).isEqualTo("PASSED");
    JsonNode next = routingRequest();
    assertThat(next.path("max_completion_tokens").asInt()).isEqualTo(900);
    assertThat(next.path("provider").path("sort").asString()).isEqualTo("throughput");
    assertThat(next.path("provider").path("require_parameters").asBoolean()).isFalse();
  }

  @Test
  void invalidRequestsNeverReachOpenRouter() {
    for (String invalid : List.of(
        "{\"scenes\":[\"paint-shop\"]}",
        "{\"full\":true,\"scenes\":[\"service-story\"]}",
        "{\"capabilites\":[\"text\"]}",
        "{\"capabilities\":[null]}",
        "{\"capabilities\":[]}",
        "{\"scenes\":[\"recovery-road-test\"],\"requestModes\":[\"responses\"]}",
        "{\"offlineContracts\":true,\"reasoningEffort\":\"\"}",
        "{\"requestModes\":[\"chats\"]}")) {
      assertThat(this.mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content(invalid))
          .as(invalid).hasStatus(HttpStatus.BAD_REQUEST);
    }
    assertThat(this.mvc.post().uri("/api/sweeps").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .hasStatus(HttpStatus.BAD_REQUEST);
    assertThat(OpenRouterMock.server().getAllServeEvents()).isEmpty();
  }

  private static JsonNode routingRequest() {
    return OpenRouterMock.requests(CHAT).stream()
        .filter(request -> "routing-lane".equals(request.path("metadata").path("sceneId").asString()))
        .findFirst().orElseThrow();
  }
}
