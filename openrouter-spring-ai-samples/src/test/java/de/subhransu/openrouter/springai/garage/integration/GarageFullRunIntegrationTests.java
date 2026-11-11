package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.subhransu.openrouter.springai.garage.GarageRunRequests;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;

/**
 * A full run over HTTP: every scene in both request modes, all modalities and image surfaces.
 * The run is the expensive part (17 scene runs), so it executes once and every test reads the
 * same outcome and the same captured OpenRouter traffic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GarageFullRunIntegrationTests extends MockedGarageIntegrationTest {

  private static final String TOPIC = "SENTINEL-TOPIC-7F3A a pickup that overheats";

  private String location;

  private JsonNode run;

  private String evidenceBody;

  private String reportBody;

  private String runBody;

  private List<LoggedRequest> traffic;

  private List<ServeEvent> unmatched;

  @BeforeAll
  void runEverySceneOnce() {
    OpenRouterMock.reset();
    this.location = start("{\"full\": true, \"topic\": \"" + TOPIC + "\"}");
    this.run = awaitFinished(this.location);
    this.evidenceBody = file(this.location, "evidence");
    this.reportBody = file(this.location, "report");
    this.runBody = body(this.mvc.get().uri(this.location).exchange());
    this.traffic = OpenRouterMock.server().getAllServeEvents().stream().map(ServeEvent::getRequest).toList();
    this.unmatched = OpenRouterMock.unmatched();
  }

  // Runs even when the setup above failed half way, so no run stays active for the next class.
  @AfterAll
  void drain() {
    leaveNoActiveRun();
  }

  @Test
  void aFullRunPassesEverySceneInBothRequestModes() {
    assertThat(this.run.path("message").isNull()).as(this.run.path("message").asString()).isTrue();
    assertThat(this.run.path("status").asString()).isEqualTo("PASSED");
    assertThat(this.run.path("incompleteFeatures")).isEmpty();
    List<String> outcomes = new ArrayList<>();
    List<String> sceneIds = new ArrayList<>();
    this.run.path("scenes").forEach(scene -> {
      sceneIds.add(scene.path("sceneId").asString());
      outcomes.add(scene.path("sceneId").asString() + "/" + scene.path("requestMode").asString() + "="
          + scene.path("status").asString());
    });
    // Every planned scene ran; the 17 runs are the 9 scenes in the modes each one supports.
    assertThat(sceneIds).containsAll(GarageRunRequests.plan("{\"full\":true}").sceneIds());
    assertThat(outcomes).hasSize(17).allMatch(outcome -> outcome.endsWith("=PASSED"));

    JsonNode bundle = JSON.readTree(this.evidenceBody);
    assertThat(bundle.path("status").asString()).isEqualTo("passed");
    // "partial" means covered in one mode and declared unsupported in the other.
    assertThat(bundle.path("featureRegistry")).hasSize(GarageFeature.values().length).allSatisfy(feature -> {
      List<String> modes = new ArrayList<>();
      feature.path("modeStatuses").forEach(mode -> modes.add(mode.path("status").asString()));
      assertThat(modes).as(feature.path("id").asString())
          .isSubsetOf("covered", "unsupported-in-mode").contains("covered");
    });
    assertThat(this.unmatched).isEmpty();
  }

  @Test
  void evidenceAndReportsKeepNoTopicPromptsRepliesOrCredentials() {
    Map<String, String> published = Map.of("evidence", this.evidenceBody, "report", this.reportBody, "run",
        this.runBody);
    published.forEach((name, body) -> {
      assertThat(body)
          .as(name)
          .doesNotContain("SENTINEL-TOPIC-7F3A")
          .doesNotContain(MOCK_KEY)
          .doesNotContain("Bearer")
          .doesNotContain("cooling-system pressure test")
          .doesNotContain("Pressure-test the radiator cap")
          .doesNotContain("CHECK ENGINE")
          .doesNotContain("You are the Garage Foreman");
    });
    // The topic does reach the model: the redaction is in the evidence, not the request.
    assertThat(bodies(CHAT)).anySatisfy(request -> assertThat(request.toString()).contains("SENTINEL-TOPIC-7F3A"));
  }

  @Test
  void eachModalitySendsItsWireShape() {
    assertThat(bodies(CHAT)).anyMatch(request -> hasPart(request.path("messages"), "image_url"));
    assertThat(bodies(RESPONSES)).anyMatch(request -> hasPart(request.path("input"), "input_image"));
    assertThat(bodies(CHAT)).anyMatch(request -> contains(request.path("modalities"), "image"));
    assertThat(bodies("/images")).hasSize(2).filteredOn(request -> request.path("stream").asBoolean())
        .hasSize(1);
    assertThat(bodies("/embeddings")).filteredOn(request -> request.path("input").size() == 5).hasSize(1);
    assertThat(bodies(CHAT)).anyMatch(request -> anyItem(request.path("messages"),
        message -> "call_bulletin".equals(message.path("tool_call_id").asString())));
    assertThat(bodies(CHAT)).anyMatch(request -> "specialist".equals(request.path("metadata").path("phase").asString()));
    assertThat(bodies(CHAT)).anySatisfy(request -> {
      assertThat(request.path("metadata").path("sceneId").asString()).isEqualTo("routing-lane");
      assertThat(request.path("model").asString()).isEqualTo("openai/gpt-3.5-turbo-0613");
      assertThat(request.path("models").size()).isEqualTo(2);
      assertThat(request.path("route").asString()).isEqualTo("fallback");
    });
  }

  @Test
  void everyModeSendsItsAttributionHeadersOnTheAttributionScene() {
    for (String path : List.of(CHAT, RESPONSES)) {
      assertThat(traffic(path).stream().filter(request -> "attribution-check-in"
          .equals(JSON.readTree(request.getBodyAsString()).path("metadata").path("sceneId").asString())))
          .as(path).isNotEmpty().allSatisfy(request -> {
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer " + MOCK_KEY);
            assertThat(request.getHeader("HTTP-Referer"))
                .isEqualTo("https://github.com/Subhransu-De/openrouter-spring-ai");
            assertThat(request.getHeader("X-OpenRouter-Title")).isEqualTo("Garage Sample");
          });
    }
  }

  @Test
  void responsesToolRoundsReplayReasoningBeforeItsFunctionCall() {
    // Read from the full run's own traffic, so the Responses service-story run is not repeated.
    JsonNode followUp = bodies(RESPONSES).stream()
        .filter(request -> request.path("input").toString().contains("\"function_call_output\""))
        .findFirst().orElseThrow();
    List<String> types = new ArrayList<>();
    followUp.path("input").forEach(item -> types.add(item.path("type").asString(
        item.has("role") ? "message:" + item.path("role").asString() : "?")));
    assertThat(types).as("replayed input items").containsSubsequence("reasoning", "function_call",
        "function_call_output");
  }

  private List<JsonNode> bodies(String path) {
    return traffic(path).stream().map(request -> JSON.readTree(request.getBodyAsString())).toList();
  }

  private List<LoggedRequest> traffic(String path) {
    return this.traffic.stream().filter(request -> request.getUrl().equals("/api/v1" + path)).toList();
  }

  private static boolean hasPart(JsonNode items, String type) {
    return anyItem(items, item -> anyItem(item.path("content"), part -> type.equals(part.path("type").asString())));
  }

  private static boolean contains(JsonNode items, String value) {
    return anyItem(items, item -> value.equals(item.asString()));
  }

  private static boolean anyItem(JsonNode items, Predicate<JsonNode> test) {
    for (JsonNode item : items) {
      if (test.test(item)) {
        return true;
      }
    }
    return false;
  }

}
