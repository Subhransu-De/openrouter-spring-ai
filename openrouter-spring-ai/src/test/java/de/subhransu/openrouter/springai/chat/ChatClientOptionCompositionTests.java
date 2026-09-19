package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ChatClientOptionCompositionTests {

	private final List<String> paths = new CopyOnWriteArrayList<>();

	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	private HttpServer server;

	private OpenRouterApi api;

	@BeforeEach
	void start() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/", exchange -> {
			var request = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
			this.requests.add(request);
			this.paths.add(exchange.getRequestURI().getPath());
			boolean responses = exchange.getRequestURI().getPath().endsWith("responses");
			String body = responses
					? "{\"id\":\"synthetic\",\"status\":\"completed\",\"output\":[],\"model\":\"synthetic-model\"}"
					: "{\"model\":\"synthetic-model\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
			boolean stream = request.path("stream").asBoolean();
			if (stream) {
				body = responses ? "data: {\"type\":\"response.completed\",\"response\":" + body + "}\n\n"
						: "data: " + body.replace("message", "delta") + "\n\ndata: [DONE]\n\n";
			}
			exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		this.server.start();
		this.api = OpenRouterApi.builder()
			.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort())
			.apiKey("synthetic-key")
			.build();
	}

	@AfterEach
	void stop() {
		this.server.stop(0);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void inheritedUnsupportedOptionsFailBeforeHttp(boolean streaming) {
		var defaults = OpenRouterChatOptions.builder().model("synthetic-model").includeUsage(true).build();
		var client = ChatClient.builder(model(defaults)).build();
		for (Boolean includeUsage : new Boolean[] { null, false }) {
			var options = responses().includeUsage(includeUsage).build();
			assertThatThrownBy(() -> invoke(client, options, streaming)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("includeUsage");
		}
		var seeded = ChatClient.builder(model(defaults.mutate().includeUsage(null).seed(7).build())).build();
		assertThatThrownBy(() -> invoke(seeded, responses().seed(null).build(), streaming))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("seed");
		assertThat(this.requests).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void requestBuilderReplacesClientCustomizerButStillUsesModelDefaults(boolean streaming) {
		var defaults = OpenRouterChatOptions.builder().model("synthetic-model").build();
		var client = ChatClient.builder(model(defaults))
			.defaultOptions(OpenRouterChatOptions.builder().includeUsage(true).seed(7))
			.build();
		invoke(client, responses().build(), streaming);
		assertThat(this.paths).containsExactly("/responses");
		assertThat(this.requests.get(0).path("model").stringValue()).isEqualTo("synthetic-model");
		assertThat(this.requests.get(0).has("seed")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void modeNeutralDefaultsAllowBothEndpoints(boolean streaming) {
		var client = ChatClient.builder(model(OpenRouterChatOptions.builder().model("synthetic-model").build()))
			.build();
		invoke(client, OpenRouterChatOptions.builder().includeUsage(true).build(), streaming);
		invoke(client, responses().build(), streaming);
		assertThat(this.paths).containsExactly("/chat/completions", "/responses");
		assertThat(this.requests)
			.allSatisfy(request -> assertThat(request.path("model").stringValue()).isEqualTo("synthetic-model"));
		assertThat(this.requests.get(1).has("stream_options")).isFalse();
		assertThat(this.requests.get(1).has("seed")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rawPortableOptionsReplaceProviderDefaults(boolean streaming) {
		var model = model(responses().model("default-model").build());
		var prompt = new Prompt("synthetic", ChatOptions.builder().model("runtime-model").build());
		if (streaming) {
			model.stream(prompt).collectList().block(Duration.ofSeconds(10));
		}
		else {
			model.call(prompt);
		}
		assertThat(this.paths).containsExactly("/chat/completions");
		assertThat(this.requests.get(0).path("model").stringValue()).isEqualTo("runtime-model");
	}

	private OpenRouterChatModel model(OpenRouterChatOptions defaults) {
		return OpenRouterChatModel.builder().openRouterApi(this.api).defaultOptions(defaults).build();
	}

	private static OpenRouterChatOptions.Builder responses() {
		return OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES);
	}

	private static void invoke(ChatClient client, OpenRouterChatOptions options, boolean streaming) {
		var request = client.prompt().user("synthetic").options(options.mutate());
		if (streaming) {
			request.stream().chatResponse().collectList().block(Duration.ofSeconds(10));
		}
		else {
			request.call().chatResponse();
		}
	}

}
