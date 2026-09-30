package de.subhransu.openrouter.springai.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Starter-level transport coverage. It starts a local HTTP endpoint so Boot's managed
 * builders, ordinary builder customizations, default request factories/connectors,
 * codecs, and OpenRouter's final client configuration all participate in real blocking
 * and streaming exchanges.
 */
class OpenRouterHttpClientStarterTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private static final String REST_CUSTOMIZER_HEADER = "X-Rest-Customizer";

	private static final String WEB_CUSTOMIZER_HEADER = "X-Web-Customizer";

	private static final String USER_BUILDER_HEADER = "X-User-Builder";

	private static final String LARGE_STREAM_CONTENT = "x".repeat(300 * 1024);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class)
		.withPropertyValues(API_KEY_PROPERTY);

	@Test
	void minimalStarterContextProvidesBootManagedPrototypeBuilders() {
		this.contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(RestClient.Builder.class);
			assertThat(context).hasSingleBean(WebClient.Builder.class);
			assertThat(context.getBean(RestClient.Builder.class))
				.isNotSameAs(context.getBean(RestClient.Builder.class));
			assertThat(context.getBean(WebClient.Builder.class)).isNotSameAs(context.getBean(WebClient.Builder.class));
		});
	}

	@Test
	void ordinaryBootBuilderCustomizationsReachBlockingAndStreamingTransports() throws IOException {
		try (RecordingServer server = new RecordingServer()) {
			this.contextRunner
				.withBean(RestClientCustomizer.class,
						() -> builder -> builder.defaultHeader(REST_CUSTOMIZER_HEADER, "applied"))
				.withBean(WebClientCustomizer.class,
						() -> builder -> builder.defaultHeader(WEB_CUSTOMIZER_HEADER, "applied"))
				.withPropertyValues(serverProperties(server))
				.run(context -> {
					assertThat(context).hasNotFailed();
					OpenRouterApi api = context.getBean(OpenRouterApi.class);

					assertThat(api.chatCompletion(chatRequest(false)).id()).isEqualTo("blocking-response");
					List<ChatCompletionChunk> chunks = api.chatCompletionStream(chatRequest(true))
						.collectList()
						.block(Duration.ofSeconds(5));

					assertThat(chunks).singleElement()
						.extracting(chunk -> chunk.choices().get(0).delta().content())
						.asString()
						.hasSize(LARGE_STREAM_CONTENT.length());
					assertThat(ReflectionTestUtils.getField(api, "timeout")).isEqualTo(Duration.ofSeconds(3));
					assertBlockingRequest(server.blockingRequest(), REST_CUSTOMIZER_HEADER);
					assertStreamingRequest(server.streamingRequest(), WEB_CUSTOMIZER_HEADER);
					assertThat(server.failure()).isNull();
				});
		}
	}

	@Test
	void explicitlySuppliedBuildersReplaceBootManagedBuildersAndReachBothTransports() throws IOException {
		try (RecordingServer server = new RecordingServer()) {
			RestClient.Builder restClientBuilder = RestClient.builder().defaultHeader(USER_BUILDER_HEADER, "rest");
			WebClient.Builder webClientBuilder = WebClient.builder().defaultHeader(USER_BUILDER_HEADER, "web");

			this.contextRunner.withBean("userRestClientBuilder", RestClient.Builder.class, () -> restClientBuilder)
				.withBean("userWebClientBuilder", WebClient.Builder.class, () -> webClientBuilder)
				.withPropertyValues(serverProperties(server))
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(RestClient.Builder.class);
					assertThat(context.getBean(RestClient.Builder.class)).isSameAs(restClientBuilder);
					assertThat(context).hasSingleBean(WebClient.Builder.class);
					assertThat(context.getBean(WebClient.Builder.class)).isSameAs(webClientBuilder);

					OpenRouterApi api = context.getBean(OpenRouterApi.class);
					api.chatCompletion(chatRequest(false));
					api.chatCompletionStream(chatRequest(true)).blockLast(Duration.ofSeconds(5));

					assertBlockingRequest(server.blockingRequest(), USER_BUILDER_HEADER);
					assertThat(server.blockingRequest().headers().getFirst(USER_BUILDER_HEADER)).isEqualTo("rest");
					assertStreamingRequest(server.streamingRequest(), USER_BUILDER_HEADER);
					assertThat(server.streamingRequest().headers().getFirst(USER_BUILDER_HEADER)).isEqualTo("web");
					assertThat(server.failure()).isNull();
				});
		}
	}

	@Test
	void userSuppliedOpenRouterApiBacksOffAutoConfiguration() {
		OpenRouterApi userApi = mock(OpenRouterApi.class);
		this.contextRunner.withBean("userOpenRouterApi", OpenRouterApi.class, () -> userApi).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context.getBean(OpenRouterApi.class)).isSameAs(userApi);
			assertThat(context).hasSingleBean(RestClient.Builder.class);
			assertThat(context).hasSingleBean(WebClient.Builder.class);
		});
	}

	private static String[] serverProperties(RecordingServer server) {
		return new String[] { "spring.ai.openrouter.base-url=" + server.baseUrl(),
				"spring.ai.openrouter.connection.timeout=3s",
				"spring.ai.openrouter.app.http-referer=https://app.example",
				"spring.ai.openrouter.app.title=Starter Test", "spring.ai.openrouter.app.categories[0]=testing",
				"spring.ai.openrouter.app.categories[1]=integration" };
	}

	private static ChatCompletionRequest chatRequest(boolean stream) {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(stream)
			.build();
	}

	private static void assertBlockingRequest(CapturedRequest request, String customHeader) {
		assertThat(request).isNotNull();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/api/v1/chat/completions");
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(request.headers().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(request.headers().getFirst(HttpHeaders.ACCEPT)).contains(MediaType.APPLICATION_JSON_VALUE);
		assertThat(request.headers().getFirst("HTTP-Referer")).isEqualTo("https://app.example");
		assertThat(request.headers().getFirst("X-OpenRouter-Title")).isEqualTo("Starter Test");
		assertThat(request.headers().getFirst("X-OpenRouter-Categories")).isEqualTo("testing,integration");
		assertThat(request.headers().getFirst(customHeader)).isNotBlank();
	}

	private static void assertStreamingRequest(CapturedRequest request, String customHeader) {
		assertThat(request).isNotNull();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/api/v1/chat/completions");
		assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(request.headers().getFirst(HttpHeaders.CONTENT_TYPE)).startsWith(MediaType.APPLICATION_JSON_VALUE);
		assertThat(request.headers().getFirst(HttpHeaders.ACCEPT)).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
		assertThat(request.headers().getFirst("HTTP-Referer")).isEqualTo("https://app.example");
		assertThat(request.headers().getFirst("X-OpenRouter-Title")).isEqualTo("Starter Test");
		assertThat(request.headers().getFirst("X-OpenRouter-Categories")).isEqualTo("testing,integration");
		assertThat(request.headers().getFirst(customHeader)).isNotBlank();
		assertThat(request.body()).contains("\"stream\":true");
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	static class TestApplication {

	}

	private record CapturedRequest(String method, String path, HttpHeaders headers, String body) {

	}

	private static final class RecordingServer implements AutoCloseable {

		private final AtomicReference<CapturedRequest> blockingRequest = new AtomicReference<>();

		private final AtomicReference<CapturedRequest> streamingRequest = new AtomicReference<>();

		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private final ExecutorService executor = Executors.newCachedThreadPool();

		private final HttpServer server;

		RecordingServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/api/v1/chat/completions", this::handle);
			this.server.setExecutor(this.executor);
			this.server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort() + "/api/v1";
		}

		CapturedRequest blockingRequest() {
			return this.blockingRequest.get();
		}

		CapturedRequest streamingRequest() {
			return this.streamingRequest.get();
		}

		Throwable failure() {
			return this.failure.get();
		}

		private void handle(HttpExchange exchange) {
			try {
				CapturedRequest request = capture(exchange);
				if (request.headers().getAccept().contains(MediaType.TEXT_EVENT_STREAM)) {
					this.streamingRequest.set(request);
					write(exchange, MediaType.TEXT_EVENT_STREAM_VALUE, streamingResponse());
				}
				else {
					this.blockingRequest.set(request);
					write(exchange, MediaType.APPLICATION_JSON_VALUE, blockingResponse());
				}
			}
			catch (Throwable ex) {
				this.failure.compareAndSet(null, ex);
				exchange.close();
			}
		}

		private CapturedRequest capture(HttpExchange exchange) throws IOException {
			HttpHeaders headers = new HttpHeaders();
			exchange.getRequestHeaders().forEach(headers::put);
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			return new CapturedRequest(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
		}

		private void write(HttpExchange exchange, String contentType, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
			exchange.sendResponseHeaders(200, bytes.length);
			try (var responseBody = exchange.getResponseBody()) {
				responseBody.write(bytes);
			}
		}

		private String blockingResponse() {
			return """
					{
					  "id": "blocking-response",
					  "object": "chat.completion",
					  "created": 123,
					  "model": "openai/gpt-5.4-mini",
					  "choices": []
					}
					""";
		}

		private String streamingResponse() {
			return """
					data: {"id":"streaming-response","object":"chat.completion.chunk","created":123,"model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"%s"}}]}

					data: [DONE]

					"""
				.formatted(LARGE_STREAM_CONTENT);
		}

		@Override
		public void close() {
			this.server.stop(0);
			this.executor.shutdownNow();
		}

	}

}
