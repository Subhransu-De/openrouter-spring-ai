package de.subhransu.openrouter.springai.garage.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** A full run over HTTP: every scene in both request modes, all modalities and image surfaces. */
class GarageFullRunIntegrationTests extends MockedGarageIntegrationTest {

  private static final String TOPIC = "SENTINEL-TOPIC-7F3A a pickup that overheats";

  @Test
  void aFullRunPassesEverySceneInBothRequestModes() {
    String location = start("{\"full\": true, \"topic\": \"" + TOPIC + "\"}");
    JsonNode run = awaitFinished(location);

    assertThat(run.path("message").isNull()).as(run.path("message").asString()).isTrue();
    assertThat(run.path("status").asString()).isEqualTo("PASSED");
    assertThat(run.path("incompleteFeatures")).isEmpty();
    List<String> outcomes = new ArrayList<>();
    run.path("scenes").forEach(scene -> outcomes.add(scene.path("sceneId").asString() + "/"
        + scene.path("requestMode").asString() + "=" + scene.path("status").asString()));
    assertThat(outcomes).hasSize(17).allMatch(outcome -> outcome.endsWith("=PASSED"));

    JsonNode bundle = JSON.readTree(file(location, "evidence"));
    assertThat(bundle.path("status").asString()).isEqualTo("passed");
    // "partial" means covered in one mode and declared unsupported in the other.
    bundle.path("featureRegistry").forEach(feature -> {
      List<String> modes = new ArrayList<>();
      feature.path("modeStatuses").forEach(mode -> modes.add(mode.path("status").asString()));
      assertThat(modes).as(feature.path("id").asString())
          .isSubsetOf("covered", "unsupported-in-mode").contains("covered");
    });
    assertThat(OpenRouterMock.unmatched()).isEmpty();
  }

  @Test
  void evidenceAndReportsKeepNoTopicPromptsRepliesOrCredentials() {
    String location = start("{\"full\": true, \"topic\": \"" + TOPIC + "\"}");
    assertThat(awaitFinished(location).path("status").asString()).isEqualTo("PASSED");

    for (String published : List.of(file(location, "evidence"), file(location, "report"),
        body(this.mvc.get().uri(location).exchange()))) {
      assertThat(published)
          .doesNotContain("SENTINEL-TOPIC-7F3A")
          .doesNotContain(MOCK_KEY)
          .doesNotContain("Bearer")
          .doesNotContain("cooling-system pressure test")
          .doesNotContain("Pressure-test the radiator cap")
          .doesNotContain("CHECK ENGINE")
          .doesNotContain("You are the Garage Foreman");
    }
    // The topic does reach the model: the redaction is in the evidence, not the request.
    assertThat(OpenRouterMock.requests(CHAT)).anySatisfy(request ->
        assertThat(request.toString()).contains("SENTINEL-TOPIC-7F3A"));
  }

  @Test
  void eachModalitySendsItsWireShape() {
    assertThat(runToCompletion("{\"full\": true}").path("status").asString()).isEqualTo("PASSED");
    WireMockServer mock = OpenRouterMock.server();

    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + CHAT))
        .withRequestBody(matchingJsonPath("$.messages[*].content[?(@.type == 'image_url')]")));
    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + RESPONSES))
        .withRequestBody(matchingJsonPath("$.input[*].content[?(@.type == 'input_image')]")));
    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + CHAT))
        .withRequestBody(matchingJsonPath("$.modalities[?(@ == 'image')]")));
    mock.verify(2, postRequestedFor(urlEqualTo("/api/v1/images")));
    mock.verify(1, postRequestedFor(urlEqualTo("/api/v1/images"))
        .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    mock.verify(1, postRequestedFor(urlEqualTo("/api/v1/embeddings"))
        .withRequestBody(matchingJsonPath("$.input.size()", equalTo("5"))));
    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + CHAT))
        .withRequestBody(matchingJsonPath("$.messages[?(@.tool_call_id == 'call_bulletin')]")));
    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + CHAT))
        .withRequestBody(matchingJsonPath("$.metadata.phase", equalTo("specialist"))));
    mock.verify(postRequestedFor(urlEqualTo("/api/v1" + CHAT))
        .withRequestBody(matchingJsonPath("$.metadata.sceneId", equalTo("routing-lane")))
        .withRequestBody(matchingJsonPath("$.model", equalTo("openai/gpt-3.5-turbo-0613")))
        .withRequestBody(matchingJsonPath("$.models.size()", equalTo("2")))
        .withRequestBody(matchingJsonPath("$.route", equalTo("fallback"))));
    for (String path : List.of(CHAT, RESPONSES)) {
      mock.verify(postRequestedFor(urlEqualTo("/api/v1" + path))
          .withRequestBody(matchingJsonPath("$.metadata.sceneId", equalTo("attribution-check-in")))
          .withHeader("Authorization", equalTo("Bearer " + MOCK_KEY))
          .withHeader("HTTP-Referer", equalTo("https://github.com/Subhransu-De/openrouter-spring-ai"))
          .withHeader("X-OpenRouter-Title", equalTo("Garage Sample")));
    }
  }

  @Test
  void responsesToolRoundsReplayReasoningBeforeItsFunctionCall() {
    assertThat(runToCompletion("{\"scenes\": [\"service-story\"], \"requestModes\": [\"responses\"]}")
        .path("status").asString()).isEqualTo("PASSED");

    JsonNode followUp = OpenRouterMock.requests(RESPONSES).stream()
        .filter(request -> request.path("input").toString().contains("\"function_call_output\""))
        .findFirst().orElseThrow();
    List<String> types = new ArrayList<>();
    followUp.path("input").forEach(item -> types.add(item.path("type").asString(
        item.has("role") ? "message:" + item.path("role").asString() : "?")));
    assertThat(types).as("replayed input items").containsSubsequence("reasoning", "function_call",
        "function_call_output");
  }
}
