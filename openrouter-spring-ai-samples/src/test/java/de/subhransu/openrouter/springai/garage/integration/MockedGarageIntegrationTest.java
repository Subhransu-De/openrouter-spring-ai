package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
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

  /** Runs this test started, so a failing test cannot leave one active for the next test. */
  private final List<String> started = new ArrayList<>();

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

  /**
   * The Garage runs one run at a time in a singleton shared by every test in the cached
   * context. Wait for each run this test started, whether the test passed or failed, so the
   * next test never meets a 409 from a leftover run.
   */
  @AfterEach
  void leaveNoActiveRun() {
    this.started.forEach(this::awaitFinished);
    this.started.clear();
  }

  String start(String requestJson) {
    return accepted("/api/runs", requestJson);
  }

  String startSweep(String requestJson) {
    return accepted("/api/sweeps", requestJson);
  }

  /** Posts a request and tracks the run it starts, if any, for {@link #leaveNoActiveRun()}. */
  MvcTestResult post(String uri, String requestJson) {
    MvcTestResult result = this.mvc.post().uri(uri)
        .contentType(MediaType.APPLICATION_JSON).content(requestJson).exchange();
    if (result.getResponse().getStatus() == HttpStatus.ACCEPTED.value()) {
      this.started.add(result.getResponse().getHeader("Location"));
    }
    return result;
  }

  private String accepted(String uri, String requestJson) {
    MvcTestResult result = post(uri, requestJson);
    assertThat(result).as(requestJson).hasStatus(HttpStatus.ACCEPTED);
    return result.getResponse().getHeader("Location");
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
