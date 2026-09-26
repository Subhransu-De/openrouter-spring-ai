package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Model compatibility sweeps started over HTTP against the OpenRouter mock. */
class GarageSweepIntegrationTests extends MockedGarageIntegrationTest {

  @Test
  void anEmbeddingSweepPassesAndPinsTheRequestedProvider() {
    String location = startSweep(
        "{\"embeddingModels\": [\"openai/text-embedding-3-small\", \"openai/text-embedding-3-small@openai\"]}");
    JsonNode run = awaitFinished(location);

    assertThat(run.path("status").asString()).isEqualTo("PASSED");
    JsonNode document = JSON.readTree(file(location, "sweeps/embedding"));
    assertThat(document.path("passed").asInt()).isEqualTo(2);
    assertThat(document.path("failed").asInt()).isZero();
    List<JsonNode> requests = OpenRouterMock.requests("/embeddings");
    assertThat(requests).hasSize(2).allSatisfy(request ->
        assertThat(request.path("input").size()).isEqualTo(3));
    assertThat(requests.get(0).path("provider").path("order").isMissingNode()
        || requests.get(0).path("provider").path("order").isEmpty()).isTrue();
    assertThat(requests.get(1).path("provider").path("order").toString()).isEqualTo("[\"openai\"]");
    assertThat(requests.get(1).path("provider").path("allow_fallbacks").asBoolean(true)).isFalse();
  }

  @Test
  void anEmbeddingSweepFailsWhenTheParaphraseDoesNotRankCloser() {
    OpenRouterMock.useScenario("embeddings-sweep-no-semantics");

    String location = startSweep("{\"embeddingModels\": [\"openai/text-embedding-3-small\"]}");
    JsonNode run = awaitFinished(location);

    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    assertThat(run.path("message").asString()).isEqualTo("1 sweep entries failed");
    assertThat(JSON.readTree(file(location, "sweeps/embedding")).path("failed").asInt()).isEqualTo(1);
  }

  @Test
  void theSweepImageQualityIsTheDefaultThatAnEntryCanOverride() {
    String location = startSweep("{\"imageModels\": [\"google/gemini-2.5-flash-image\","
        + "\"google/gemini-2.5-flash-image?quality=high\"], \"imageQuality\": \"low\"}");

    assertThat(awaitFinished(location).path("status").asString()).isEqualTo("PASSED");
    List<JsonNode> requests = OpenRouterMock.requests("/images");
    assertThat(requests).extracting(request -> request.path("quality").asString())
        .containsExactly("low", "high");
  }

  @Test
  void anImageSweepSendsEachEntryConfigAndReportsBadEntriesWithoutStopping() {
    String location = startSweep("{\"imageModels\": ["
        + "\"google/gemini-2.5-flash-image?quality=low&aspect-ratio=16:9&output-format=png\","
        + "\"garage/does-not-exist\","
        + "\"google/gemini-2.5-flash-image?shininess=high\"]}");
    JsonNode run = awaitFinished(location);

    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    assertThat(run.path("message").asString()).isEqualTo("2 sweep entries failed");
    String document = file(location, "sweeps/image");
    JsonNode sweep = JSON.readTree(document);
    assertThat(sweep.path("passed").asInt()).isEqualTo(1);
    assertThat(sweep.path("failed").asInt()).isEqualTo(2);
    assertThat(document).doesNotContain(MOCK_KEY).doesNotContain("Bearer");
    // The unknown option is rejected before a request is sent.
    List<JsonNode> requests = OpenRouterMock.requests("/images");
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).path("quality").asString()).isEqualTo("low");
    assertThat(requests.get(0).path("aspect_ratio").asString()).isEqualTo("16:9");
    assertThat(requests.get(0).path("output_format").asString()).isEqualTo("png");
    assertThat(requests.get(1).path("model").asString()).isEqualTo("garage/does-not-exist");
  }
}
