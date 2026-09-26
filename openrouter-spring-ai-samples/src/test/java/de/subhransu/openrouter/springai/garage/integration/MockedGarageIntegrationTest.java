package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives the Garage through its HTTP API against the OpenRouter mock. Only the base URL
 * differs from a live run: the real clients, SSE decoding, mappers, tool loops, and evidence
 * checks all run.
 */
@SpringBootTest(properties = "logging.level.root=off")
@AutoConfigureMockMvc
abstract class MockedGarageIntegrationTest {

  static final String CHAT = "/chat/completions";

  static final String RESPONSES = "/responses";

  static final String MOCK_KEY = "garage-mock-key";

  private static final Path OUTPUT = createOutputDirectory();

  static final ObjectMapper JSON = new ObjectMapper();

  @Autowired MockMvcTester mvc;

  @DynamicPropertySource
  static void mockOpenRouter(DynamicPropertyRegistry registry) {
    registry.add("spring.ai.openrouter.base-url", OpenRouterMock::baseUrl);
    registry.add("spring.ai.openrouter.api-key", () -> MOCK_KEY);
    registry.add("garage.output-dir", () -> OUTPUT.toString());
  }

  @BeforeEach
  void resetMock() {
    OpenRouterMock.reset();
  }

  String start(String requestJson) {
    MvcTestResult started = this.mvc.post().uri("/api/runs")
        .contentType(MediaType.APPLICATION_JSON).content(requestJson).exchange();
    assertThat(started).as(requestJson).hasStatus(HttpStatus.ACCEPTED);
    return started.getResponse().getHeader("Location");
  }

  String startSweep(String requestJson) {
    MvcTestResult started = this.mvc.post().uri("/api/sweeps")
        .contentType(MediaType.APPLICATION_JSON).content(requestJson).exchange();
    assertThat(started).as(requestJson).hasStatus(HttpStatus.ACCEPTED);
    return started.getResponse().getHeader("Location");
  }

  JsonNode run(String location) {
    return JSON.readTree(body(this.mvc.get().uri(location).exchange()));
  }

  JsonNode awaitFinished(String location) {
    await().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofMillis(100))
        .until(() -> !"RUNNING".equals(run(location).path("status").asString()));
    return run(location);
  }

  JsonNode runToCompletion(String requestJson) {
    return awaitFinished(start(requestJson));
  }

  String file(String location, String name) {
    MvcTestResult result = this.mvc.get().uri(location + "/" + name).exchange();
    assertThat(result).as(location + "/" + name).hasStatusOk();
    return body(result);
  }

  static String body(MvcTestResult result) {
    try {
      return result.getResponse().getContentAsString();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static Path createOutputDirectory() {
    try {
      return Files.createTempDirectory("garage-integration-");
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
