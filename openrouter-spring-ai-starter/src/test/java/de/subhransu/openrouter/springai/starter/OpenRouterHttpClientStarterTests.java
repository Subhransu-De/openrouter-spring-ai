package de.subhransu.openrouter.springai.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
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

	private static final String RATE_LIMIT_BODY = "{\"error\":{\"message\":\"rate limited\",\"code\":429}}";

	private static final String LARGE_STREAM_CONTENT = "x".repeat(300 * 1024);

	// The first call in a JVM starts Netty and the local server. On a heavily loaded
	// machine that took more than ten seconds, so its limit is generous. A warm call
	// ends in about a second at most.
	private static final Duration WARM_UP_LIMIT = Duration.ofSeconds(25);

	private static final Duration CALL_LIMIT = Duration.ofSeconds(15);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class)
		.withPropertyValues(API_KEY_PROPERTY);

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
	void providerErrorsReachBothTransportsAsTypedExceptionsWithTheirRetryAdvice() throws IOException {
		try (RecordingServer server = new RecordingServer().failingWith(429, RATE_LIMIT_BODY)) {
			this.contextRunner.withPropertyValues(serverProperties(server)).run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterApi api = context.getBean(OpenRouterApi.class);

				assertThatThrownBy(() -> api.chatCompletion(chatRequest(false)))
					.isInstanceOfSatisfying(OpenRouterTransientApiException.class, this::assertRateLimit);
				assertThatThrownBy(() -> api.chatCompletionStream(chatRequest(true)).blockLast(Duration.ofSeconds(5)))
					.isInstanceOfSatisfying(OpenRouterTransientApiException.class, this::assertRateLimit);
				assertThat(server.failure()).isNull();
			});
		}
	}

	@Test
	void embeddingsAndImagesReachTheServerThroughTheStarterWithOpenRouterHeaders() throws IOException {
		try (RecordingServer server = new RecordingServer()) {
			this.contextRunner.withPropertyValues(serverProperties(server)).run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterApi api = context.getBean(OpenRouterApi.class);

				var embeddings = api
					.embeddings(new EmbeddingsRequest("synthetic/embedding", List.of("hello"), null, null, null, null));
				var images = api.images(new ImagesRequest("synthetic/image", "a red panda", null, null, null, null,
						null, null, null, null, null, null, null, null));

				assertThat(embeddings.data()).singleElement()
					.satisfies(data -> assertThat(data.embedding()).containsExactly(0.25f, -0.5f));
				assertThat(images.data()).singleElement()
					.satisfies(data -> assertThat(data.b64Json()).isEqualTo("aW1hZ2Ux"));
				CapturedRequest embeddingRequest = server.request("/api/v1/embeddings");
				CapturedRequest imageRequest = server.request("/api/v1/images");
				for (CapturedRequest request : List.of(embeddingRequest, imageRequest)) {
					assertThat(request.method()).isEqualTo("POST");
					assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
					assertThat(request.headers().getFirst("HTTP-Referer")).isEqualTo("https://app.example");
					assertThat(request.headers().getFirst("X-OpenRouter-Title")).isEqualTo("Starter Test");
				}
				assertThat(embeddingRequest.body()).contains("\"model\":\"synthetic/embedding\"").contains("hello");
				assertThat(imageRequest.body()).contains("\"model\":\"synthetic/image\"").contains("a red panda");
				assertThat(server.failure()).isNull();
			});
		}
	}

	// A real socket is the only way to see Boot's connect and read timeouts reach the
	// HTTP client: the server accepts the request and then never answers. The timeout
	// keeps this test short. The warm-up call and the bounded call make a client that
	// ignores the setting fail with a diagnostic instead of hanging.
	@Test
	@Timeout(60)
	void connectionTimeoutEndsABlockingCallToAServerThatNeverAnswers() throws IOException {
		try (RecordingServer server = new RecordingServer()) {
			this.contextRunner.withPropertyValues(serverProperties(server))
				.withPropertyValues("spring.ai.openrouter.connection.timeout=1s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					OpenRouterApi api = context.getBean(OpenRouterApi.class);
					warmUp(api);
					server.stalling();

					assertThatThrownBy(() -> bounded(CALL_LIMIT, () -> api.chatCompletion(chatRequest(false))))
						.isInstanceOf(ResourceAccessException.class)
						.rootCause()
						// Reactor Netty reports a read timeout as its own type; the JDK
						// and
						// Apache request factories use a socket timeout.
						.isInstanceOfAny(ReadTimeoutException.class, SocketTimeoutException.class);
					// The client gives up when its timeout ends, which a loaded machine
					// can
					// reach before the server thread has counted the request.
					awaitStalledRequests(server, 1);
				});
		}
	}

	@Test
	void embeddingsServerErrorsReachTheCallerAsTransientExceptionsWithTheirEndpoint() throws IOException {
		try (RecordingServer server = new RecordingServer().failingWith(503,
				"{\"error\":{\"message\":\"no provider available\",\"code\":503}}")) {
			this.contextRunner.withPropertyValues(serverProperties(server)).run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterApi api = context.getBean(OpenRouterApi.class);

				assertThatThrownBy(() -> api
					.embeddings(new EmbeddingsRequest("synthetic/embedding", List.of("hello"), null, null, null, null)))
					.isInstanceOfSatisfying(OpenRouterTransientApiException.class, failure -> {
						assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
						assertThat(failure.getEndpoint()).isEqualTo("/embeddings");
						assertThat(failure.getResponseBody()).contains("no provider available");
					});
				assertThat(server.failure()).isNull();
			});
		}
	}

	// Real time is unavoidable here: a read timeout only fires while a real socket stays
	// silent. The server holds back the whole reply, so each of the three attempts of the
	// retry template waits one timeout. A silent socket cannot fail early, so a loaded
	// machine only makes the test slower, never wrong. The warm-up call keeps the first
	// use of the HTTP client out of the timed attempts, and the bounded call ends the
	// test with a diagnostic if the setting is ignored.
	@Test
	@Timeout(60)
	void aServerSlowerThanTheConnectionTimeoutFailsTheChatModelAfterTheRetryBudget() throws IOException {
		try (RecordingServer server = new RecordingServer()) {
			this.contextRunner.withPropertyValues(serverProperties(server))
				.withPropertyValues("spring.ai.openrouter.connection.timeout=300ms")
				.withBean(RetryTemplate.class,
						() -> new RetryTemplate(RetryPolicy.builder()
							.maxRetries(2)
							.delay(Duration.ofMillis(10))
							.includes(TransientAiException.class, ResourceAccessException.class)
							.build()))
				.run(context -> {
					assertThat(context).hasNotFailed();
					ChatModel model = context.getBean(ChatModel.class);
					warmUp(context.getBean(OpenRouterApi.class));
					server.stalling();

					assertThatThrownBy(() -> bounded(CALL_LIMIT,
							() -> model.call(new Prompt("Is the pickup ready?",
									OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini").build()))))
						.rootCause()
						.isInstanceOfAny(ReadTimeoutException.class, SocketTimeoutException.class);
					// The last attempt can time out before its server thread counts it.
					awaitStalledRequests(server, 3);
				});
		}
	}

	// Real time is unavoidable here as well: only a live socket shows that each
	// keep-alive
	// read resets the timeout. OpenRouter sends the headers at once and then whitespace
	// until the JSON is ready. The server dribbles the reply over longer than the
	// timeout, so success proves that each read resets the timer. The gap is a twentieth
	// of the timeout, so a server thread that a loaded machine delays by most of a second
	// still stays inside it. The warm-up call keeps the one-time start of the HTTP client
	// and of the server, which took seconds on a loaded machine, out of the timed call.
	@Test
	@Timeout(60)
	void whitespaceKeepalivesKeepASlowBlockingReplyAliveBeyondTheConnectionTimeout() throws IOException {
		Duration timeout = Duration.ofSeconds(1);
		try (RecordingServer server = new RecordingServer()) {
			this.contextRunner.withPropertyValues(serverProperties(server))
				.withPropertyValues("spring.ai.openrouter.connection.timeout=" + timeout.toMillis() + "ms")
				.run(context -> {
					assertThat(context).hasNotFailed();
					OpenRouterApi api = context.getBean(OpenRouterApi.class);
					warmUp(api);
					server.dribbling(Duration.ofMillis(50), 24);
					assertThat(server.dribbleDuration()).as("a reply slower than the timeout").isGreaterThan(timeout);

					long start = System.nanoTime();
					assertThat(bounded(CALL_LIMIT, () -> api.chatCompletion(chatRequest(false))).id())
						.isEqualTo("blocking-response");
					assertThat(Duration.ofNanos(System.nanoTime() - start)).as("the call outlasted the timeout")
						.isGreaterThan(timeout);
					assertThat(server.failure()).isNull();
				});
		}
	}

	/**
	 * Makes the one-time start of the HTTP client and of the local server happen before a
	 * test starts a short timeout.
	 */
	private static void warmUp(OpenRouterApi api) {
		assertThat(bounded(WARM_UP_LIMIT, () -> api.chatCompletion(chatRequest(false))).id())
			.isEqualTo("blocking-response");
	}

	private static void awaitStalledRequests(RecordingServer server, int expected) {
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(server.stalledRequests()).isEqualTo(expected));
	}

	/**
	 * Runs a blocking call on its own thread so that a hang fails the test with the stack
	 * of the stuck call instead of waiting for the class-wide timeout.
	 */
	private static <T> T bounded(Duration limit, Callable<T> call) {
		CompletableFuture<Outcome<T>> result = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				result.complete(new Outcome<>(call.call(), null));
			}
			catch (Throwable ex) {
				result.complete(new Outcome<>(null, ex));
			}
		}, "bounded-blocking-call");
		worker.setDaemon(true);
		worker.start();
		Outcome<T> outcome;
		try {
			outcome = result.get(limit.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			StringBuilder stack = new StringBuilder();
			for (StackTraceElement frame : worker.getStackTrace()) {
				stack.append("\n\tat ").append(frame);
			}
			return fail("The blocking call was still running after " + limit + ". Its stack:" + stack, ex);
		}
		catch (InterruptedException | ExecutionException ex) {
			Thread.currentThread().interrupt();
			return fail("Could not wait for the blocking call", ex);
		}
		if (outcome.failure() instanceof RuntimeException runtime) {
			throw runtime;
		}
		if (outcome.failure() instanceof Error error) {
			throw error;
		}
		if (outcome.failure() != null) {
			throw new IllegalStateException(outcome.failure());
		}
		return outcome.value();
	}

	private void assertRateLimit(OpenRouterTransientApiException thrownByCall) {
		assertThat(thrownByCall.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(thrownByCall.getEndpoint()).isEqualTo("/chat/completions");
		assertThat(thrownByCall.getResponseBody()).contains("rate limited");
		assertThat(thrownByCall.getRetryAfter()).isNotNull();
		assertThat(thrownByCall.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(7));
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

	private record Outcome<T>(T value, Throwable failure) {

	}

	private record CapturedRequest(String method, String path, HttpHeaders headers, String body) {

	}

	private static final class RecordingServer implements AutoCloseable {

		private final AtomicReference<CapturedRequest> blockingRequest = new AtomicReference<>();

		private final AtomicReference<CapturedRequest> streamingRequest = new AtomicReference<>();

		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private final ExecutorService executor = Executors.newCachedThreadPool();

		private final HttpServer server;

		private final ConcurrentMap<String, CapturedRequest> requests = new ConcurrentHashMap<>();

		private final CountDownLatch release = new CountDownLatch(1);

		private final AtomicInteger stalledRequests = new AtomicInteger();

		private volatile boolean stalling;

		private volatile int dribbleChunks;

		private volatile Duration dribbleGap = Duration.ZERO;

		private volatile int failureStatus;

		private volatile String failureBody = "";

		RecordingServer() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.createContext("/api/v1/chat/completions", this::handle);
			this.server.createContext("/api/v1/embeddings", this::handleSimple);
			this.server.createContext("/api/v1/images", this::handleSimple);
			this.server.setExecutor(this.executor);
			this.server.start();
		}

		RecordingServer failingWith(int status, String body) {
			this.failureStatus = status;
			this.failureBody = body;
			return this;
		}

		RecordingServer dribbling(Duration gap, int chunks) {
			this.dribbleGap = gap;
			this.dribbleChunks = chunks;
			return this;
		}

		Duration dribbleDuration() {
			return this.dribbleGap.multipliedBy(this.dribbleChunks);
		}

		RecordingServer stalling() {
			this.stalling = true;
			return this;
		}

		CapturedRequest request(String path) {
			return this.requests.get(path);
		}

		int stalledRequests() {
			return this.stalledRequests.get();
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

		private void handleSimple(HttpExchange exchange) {
			try {
				CapturedRequest request = capture(exchange);
				this.requests.put(request.path(), request);
				if (this.failureStatus != 0) {
					write(exchange, this.failureStatus, MediaType.APPLICATION_JSON_VALUE, this.failureBody);
					return;
				}
				String body = request.path().endsWith("/embeddings")
						? "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[0.25,-0.5]}],"
								+ "\"model\":\"synthetic/embedding\",\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4}}"
						: "{\"created\":1750000000,\"data\":[{\"b64_json\":\"aW1hZ2Ux\",\"media_type\":\"image/png\"}]}";
				write(exchange, 200, MediaType.APPLICATION_JSON_VALUE, body);
			}
			catch (Throwable ex) {
				this.failure.compareAndSet(null, ex);
				exchange.close();
			}
		}

		private void handle(HttpExchange exchange) {
			try {
				CapturedRequest request = capture(exchange);
				if (this.stalling) {
					this.stalledRequests.incrementAndGet();
					this.release.await();
					exchange.close();
				}
				else if (this.failureStatus != 0) {
					exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, "7");
					write(exchange, this.failureStatus, MediaType.APPLICATION_JSON_VALUE, this.failureBody);
				}
				else if (this.dribbleChunks > 0) {
					this.blockingRequest.set(request);
					dribble(exchange);
				}
				else if (request.headers().getAccept().contains(MediaType.TEXT_EVENT_STREAM)) {
					this.streamingRequest.set(request);
					write(exchange, 200, MediaType.TEXT_EVENT_STREAM_VALUE, streamingResponse());
				}
				else {
					this.blockingRequest.set(request);
					write(exchange, 200, MediaType.APPLICATION_JSON_VALUE, blockingResponse());
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

		private void write(HttpExchange exchange, int status, String contentType, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, contentType);
			exchange.sendResponseHeaders(status, bytes.length);
			try (var responseBody = exchange.getResponseBody()) {
				responseBody.write(bytes);
			}
		}

		/** Sends the headers, then a whitespace byte per gap, then the JSON. */
		private void dribble(HttpExchange exchange) throws IOException, InterruptedException {
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
			exchange.sendResponseHeaders(200, 0);
			try (var responseBody = exchange.getResponseBody()) {
				for (int i = 0; i < this.dribbleChunks; i++) {
					responseBody.write(' ');
					responseBody.flush();
					Thread.sleep(this.dribbleGap.toMillis());
				}
				responseBody.write(blockingResponse().getBytes(StandardCharsets.UTF_8));
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
			this.release.countDown();
			this.server.stop(0);
			this.executor.shutdownNow();
		}

	}

}
