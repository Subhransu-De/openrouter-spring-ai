package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

class GarageOfflineContractsTests {

  @TempDir Path output;

  @Test
  @SuppressWarnings("unchecked")
  void oneCommandProducesAPassingOfflineEvidenceBundle() throws Exception {
    SpringApplication application = new SpringApplication(GarageApplication.class);
    application.setDefaultProperties(
        Map.of("spring.main.banner-mode", "off", "logging.level.root", "off"));
    try (ConfigurableApplicationContext ignored =
        application.run(
            "--offline-contracts",
            "--output=" + this.output.toAbsolutePath(),
            "--topic=offline contract test")) {
      Path runDirectory;
      try (var directories = Files.list(this.output)) {
        runDirectory = directories.filter(Files::isDirectory).findFirst().orElseThrow();
      }
      Path json = runDirectory.resolve("garage-run.json");
      Path markdown = runDirectory.resolve("capability-report.md");
      assertThat(json).isRegularFile();
      assertThat(markdown).isRegularFile();

      String rawBundle = Files.readString(json);
      assertThat(rawBundle)
          .doesNotContain("Bearer secret")
          .doesNotContain("offline contract test")
          .doesNotContain("garage-offline-key");
      Map<String, Object> bundle = new ObjectMapper().readValue(json.toFile(), Map.class);
      assertThat(bundle.get("status")).isEqualTo("passed");
      List<Map<String, Object>> scenes = (List<Map<String, Object>>) bundle.get("scenes");
      assertThat(scenes)
          .extracting(scene -> scene.get("sceneId"))
          .containsExactly("recovery-road-test", "dyno-tuning");
      assertThat(scenes).allMatch(scene -> "PASSED".equals(scene.get("status")));
      List<Map<String, Object>> registry =
          (List<Map<String, Object>>) bundle.get("featureRegistry");
      assertThat(registry).isNotEmpty();
      assertThat(registry)
          .filteredOn(
              feature ->
                  List.of(
                          "connection-timeout",
                          "sync-retry",
                          "error-surfacing",
                          "extension-points",
                          "standard-sampling",
                          "openrouter-samplers",
                          "tool-context-merge",
                          "full-property-binding")
                      .contains(feature.get("id")))
          .allMatch(feature -> "covered".equals(feature.get("status")));
    }
  }
}
