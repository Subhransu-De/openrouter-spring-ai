package de.subhransu.openrouter.springai.starter.integration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * One OpenRouter mock per test JVM, serving the recorded stubs under
 * {@code openrouter-mock/mappings}. Tests reach it through the auto-configured models, so
 * the real HTTP clients, SSE decoding, mappers, and retries all run.
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

	/** Request bodies sent to one endpoint of the versioned API, oldest first. */
	static List<JsonNode> requests(String path) {
		return requestsAt("/api/v1" + path);
	}

	/** Request bodies sent to one URL path, oldest first. */
	static List<JsonNode> requestsAt(String url) {
		return SERVER.getAllServeEvents()
			.stream()
			.filter(event -> event.getRequest().getUrl().equals(url))
			.sorted(Comparator.comparing(event -> event.getRequest().getLoggedDate()))
			.map(event -> JSON.readTree(event.getRequest().getBodyAsString()))
			.toList();
	}

	/** Requests no default stub answered; the catch-all returns HTTP 400 for them. */
	static List<ServeEvent> unmatched() {
		return SERVER.getAllServeEvents()
			.stream()
			.filter(event -> event.getStubMapping() == null || "unmatched".equals(event.getStubMapping().getName()))
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
