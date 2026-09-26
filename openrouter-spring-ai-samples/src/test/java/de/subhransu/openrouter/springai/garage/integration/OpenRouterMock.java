package de.subhransu.openrouter.springai.garage.integration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One OpenRouter mock per test JVM. Spring caches application contexts across test classes,
 * so the mock's base URL must stay stable for the whole run.
 *
 * <p>Default stubs under {@code openrouter-mock/mappings} cover the happy paths. A test loads
 * {@code openrouter-mock/scenarios/<name>} to override them for one sad or edge case.
 */
final class OpenRouterMock {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final WireMockServer SERVER = start();

  private OpenRouterMock() {
  }

  static WireMockServer server() {
    return SERVER;
  }

  static String baseUrl() {
    return SERVER.baseUrl() + "/api/v1";
  }

  static void reset() {
    SERVER.resetToDefaultMappings();
    SERVER.resetRequests();
    SERVER.resetScenarios();
  }

  static void useScenario(String name) {
    try {
      Resource[] stubs = new PathMatchingResourcePatternResolver()
          .getResources("classpath:openrouter-mock/scenarios/" + name + "/*.json");
      if (stubs.length == 0) {
        throw new IllegalArgumentException("No OpenRouter mock scenario named " + name);
      }
      for (Resource stub : stubs) {
        SERVER.addStubMapping(StubMapping.buildFrom(stub.getContentAsString(StandardCharsets.UTF_8)));
      }
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** Request bodies sent to one endpoint, oldest first. */
  static List<JsonNode> requests(String path) {
    return SERVER.getAllServeEvents().stream()
        .filter(event -> event.getRequest().getUrl().equals("/api/v1" + path))
        .sorted(Comparator.comparing(event -> event.getRequest().getLoggedDate()))
        .map(event -> JSON.readTree(event.getRequest().getBodyAsString()))
        .toList();
  }

  /** Requests no scenario or default stub answered; the catch-all returns HTTP 400 for them. */
  static List<ServeEvent> unmatched() {
    return SERVER.getAllServeEvents().stream()
        .filter(event -> event.getStubMapping() == null
            || "unmatched".equals(event.getStubMapping().getName()))
        .toList();
  }

  private static WireMockServer start() {
    WireMockServer server = new WireMockServer(
        wireMockConfig().dynamicPort().usingFilesUnderClasspath("openrouter-mock"));
    server.start();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "openrouter-mock-stop"));
    return server;
  }
}
