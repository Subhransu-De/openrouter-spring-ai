package de.subhransu.openrouter.springai.garage.integration;

import de.subhransu.openrouter.springai.garage.GarageRunRequests;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.ObjectMapper;

// Pin a placeholder key and an unroutable base URL: an OPENROUTER_API_KEY in the environment
// must never let a test reach OpenRouter.
@SpringBootTest(properties = {"logging.level.root=off",
    "spring.ai.openrouter.api-key=garage-missing-api-key",
    "spring.ai.openrouter.base-url=http://127.0.0.1:1/api/v1"})
@AutoConfigureMockMvc
class GarageOfflineContractsIntegrationTests {

  @TempDir static Path output;

  @Autowired MockMvcTester mvc;

  private String startedRun;

  /** The offline run is a singleton-service run; never leave it active for another test. */
  @AfterEach
  void leaveNoActiveRun() {
    if (this.startedRun != null) {
      await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
          assertThat(this.mvc.get().uri(this.startedRun).exchange())
              .bodyJson().extractingPath("$.status").isNotEqualTo("RUNNING"));
    }
  }

  @DynamicPropertySource
  static void outputDirectory(DynamicPropertyRegistry registry) {
    registry.add("garage.output-dir", () -> output.toAbsolutePath().toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void offlineContractsRunOverHttpWithoutAnApiKey() throws Exception {
    MvcTestResult started = this.mvc.post().uri("/api/runs")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"offlineContracts\": true, \"topic\": \"offline contract test\"}")
        .exchange();
    assertThat(started).hasStatus(HttpStatus.ACCEPTED);
    String location = started.getResponse().getHeader("Location");
    this.startedRun = location;
    assertThat(location).startsWith("/api/runs/");

    await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
        assertThat(this.mvc.get().uri(location).exchange())
            .bodyJson().extractingPath("$.status").isNotEqualTo("RUNNING"));
    assertThat(this.mvc.get().uri(location).exchange())
        .hasStatusOk()
        .bodyJson()
        .hasPathSatisfying("$.status", status -> status.assertThat().isEqualTo("PASSED"))
        .hasPathSatisfying("$.links.evidence",
            link -> link.assertThat().isEqualTo(location + "/evidence"));

    MvcTestResult evidence = this.mvc.get().uri(location + "/evidence").exchange();
    assertThat(evidence).hasStatusOk().hasContentType(MediaType.APPLICATION_JSON);
    String rawBundle = evidence.getResponse().getContentAsString();
    assertThat(rawBundle)
        .doesNotContain("Bearer secret")
        .doesNotContain("offline contract test")
        .doesNotContain("garage-offline-key");
    Map<String, Object> bundle = new ObjectMapper().readValue(rawBundle, Map.class);
    assertThat(bundle.get("status")).isEqualTo("passed");
    List<Map<String, Object>> scenes = (List<Map<String, Object>>) bundle.get("scenes");
    assertThat(scenes)
        .extracting(scene -> scene.get("sceneId"))
        .containsExactly("recovery-road-test", "dyno-tuning");
    assertThat(scenes).allMatch(scene -> "PASSED".equals(scene.get("status")));
    List<Map<String, Object>> registry =
        (List<Map<String, Object>>) bundle.get("featureRegistry");
    List<String> offlineFeatures = List.of(
        "connection-timeout",
        "sync-retry",
        "error-surfacing",
        "extension-points",
        "standard-sampling",
        "openrouter-samplers",
        "tool-context-merge",
        "full-property-binding");
    List<Map<String, Object>> offlineRows =
        registry.stream().filter(feature -> offlineFeatures.contains(feature.get("id"))).toList();
    // Naming the ids first keeps a renamed or missing feature from emptying the status check.
    assertThat(offlineRows)
        .extracting(feature -> feature.get("id"))
        .containsExactlyInAnyOrderElementsOf(offlineFeatures);
    assertThat(offlineRows)
        .extracting(feature -> feature.get("status"))
        .containsOnly("covered");

    assertThat(this.mvc.get().uri(location + "/report").exchange())
        .hasStatusOk()
        .bodyText().contains("# Run status: passed", "# Garage capability report");
  }

  @Test
  void liveScenesAreRejectedWithoutAnApiKey() {
    assertThat(this.mvc.post().uri("/api/runs")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"capabilities\": [\"text\"]}"))
        .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        .bodyJson().extractingPath("$.title").isEqualTo("OpenRouter API key missing");
    assertThat(this.mvc.post().uri("/api/runs")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"capabilities\": [\"text\"]}"))
        .bodyJson().extractingPath("$.detail").asString().contains("OPENROUTER_API_KEY");
  }

  @Test
  void scenesListEverySelectableScene() {
    assertThat(this.mvc.get().uri("/api/scenes"))
        .hasStatusOk()
        .bodyJson()
        .hasPathSatisfying("$[*].id", ids -> ids.assertThat().asArray()
            .containsExactlyInAnyOrderElementsOf(GarageRunRequests.plan("{\"full\":true}").sceneIds()))
        .hasPathSatisfying("$[?(@.id == 'recovery-road-test')].offline",
            offline -> offline.assertThat().asArray().containsExactly(true));
  }
}
