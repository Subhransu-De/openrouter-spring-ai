package example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.Function;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesInputMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.Tool;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/** Executable native consumer. All traffic and data are synthetic and loopback-only. */
@SpringBootConfiguration
@ImportAutoConfiguration(OpenRouterApiAutoConfiguration.class)
public class NativeSmoke {

	private static final Duration DEADLINE = Duration.ofSeconds(10);

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String CHAT = """
			{"id":"synthetic","choices":[{"index":0,"message":{"role":"assistant","content":"hello"},"finish_reason":"stop"}],
			 "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3},"future_field":true}
			""";

	private static final String RESPONSE = """
			{"id":"synthetic","status":"completed","output":[{"type":"message","role":"assistant",
			 "content":[{"type":"output_text","text":"hello"}],"future_field":true}],
			 "usage":{"input_tokens":2,"output_tokens":1,"total_tokens":3}}
			""";

	private static final String IMAGE = """
			{"created":1,"data":[{"b64_json":"c3ludGhldGlj","media_type":"image/png"}],"future_field":true}
			""";

	private static final String TEXT_DELTA = """
			{"choices":[{"index":0,"delta":{"content":"hello"}}],"future_field":true}
			""";

	public static void main(String[] args) throws Exception {
		try (Fixture fixture = new Fixture()) {
			SpringApplication app = new SpringApplication(NativeSmoke.class);
			try (var context = app.run("--spring.ai.openrouter.base-url=" + fixture.baseUrl())) {
				OpenRouterApi api = context.getBean(OpenRouterApi.class);
				blocking(api, fixture);
				streaming(api, fixture);
				errors(api, fixture);
				cancellation(api, fixture);
				fixture.verify();
			}
		}
		System.out.println("Native JSON/SSE smoke checks passed");
	}

	private static ChatCompletionRequest chat(boolean stream) {
		return new ChatCompletionRequest("synthetic", null,
				List.of(new ChatMessage("user",
						List.of(ContentPart.text("hello"), ContentPart.image("data:image/png;base64,c3ludGhldGlj")),
						null, null, null)),
				null, null, null, null, null, null, null, null, 32, null, null, null, null, stream, null,
				List.of(new Tool("function",
						new Function("echo", "Synthetic tool", JSON.readTree("{\"type\":\"object\"}")))),
				null, null, null, null, null, null, null, null, null, null);
	}

	private static ResponsesRequest responses(boolean stream) {
		return new ResponsesRequest("synthetic", null,
				List.of(new ResponsesInputMessage("message", "user",
						List.of(new ResponsesContent("input_text", "hello"))),
						new ResponsesFunctionCall("function_call", "call-1", "echo", "{}"),
						new ResponsesFunctionCallOutput(null, "function_call_output", "call-1", "ok")),
				null, 32, stream, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null);
	}

	private static EmbeddingsRequest embedding() {
		return new EmbeddingsRequest("synthetic", List.of("hello"), "float", 2, null, null);
	}

	private static ImagesRequest image(boolean stream) {
		return new ImagesRequest("synthetic", "hello", 1, "1024x1024", null, null, null, "png", null, null, null,
				stream, List.of(ContentPart.image("data:image/png;base64,c3ludGhldGlj")), null);
	}

	private static void blocking(OpenRouterApi api, Fixture fixture) {
		fixture.expect("/chat/completions", false, CHAT);
		var chat = api.chatCompletion(chat(false));
		check("hello".equals(chat.choices().get(0).message().content()), "chat content");
		check(chat.usage().totalTokens() == 3, "chat usage");
		check(Boolean.TRUE.equals(chat.extensions().get("future_field")), "chat extension preservation");
		fixture.expect("/responses", false, RESPONSE);
		var response = api.responses(responses(false));
		check("hello".equals(response.output().get(0).content().get(0).text()), "Responses content");
		check(response.output().get(0).rawItem().path("future_field").asBoolean(), "opaque output replay");
		check(response.usage().promptTokens() == 2, "Responses usage alias");
		fixture.expect("/embeddings", false,
				"""
						{"data":[{"object":"embedding","index":0,"embedding":[0.25,-0.5]}],"usage":{"prompt_tokens":2},"future_field":true}
						""");
		check(api.embeddings(embedding()).data().get(0).embedding()[1] == -0.5f, "embedding vector");
		fixture.expect("/images", false, IMAGE);
		check("c3ludGhldGlj".equals(api.images(image(false)).data().get(0).b64Json()), "image data");
	}

	private static void streaming(OpenRouterApi api, Fixture fixture) {
		fixture.expect("/chat/completions", true,
				sse(TEXT_DELTA,
						"""
								{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"echo","arguments":"{"}}]}}]}
								""",
						"""
								{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]},"finish_reason":"tool_calls"}]}
								""",
						"[DONE]", "{invalid trailing data"));
		var chunks = collect(api.chatCompletionStream(chat(true)));
		check(chunks.size() == 3 && "hello".equals(chunks.get(0).choices().get(0).delta().content()),
				"SSE chat text/termination");
		check(Boolean.TRUE.equals(chunks.get(0).extensions().get("future_field")), "SSE extension preservation");
		check("echo".equals(chunks.get(1).choices().get(0).delta().toolCalls().get(0).function().name()),
				"SSE tool name");
		check("}".equals(chunks.get(2).choices().get(0).delta().toolCalls().get(0).function().arguments()),
				"fragmented tool arguments");
		fixture.expect("/responses", true, sse("{\"type\":\"response.output_text.delta\",\"delta\":\"hello\"}",
				"{\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"echo\",\"arguments\":\"\"}}",
				"{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{}\"}",
				"{\"type\":\"response.completed\",\"response\":" + RESPONSE + "}", "{invalid trailing data"));
		var events = collect(api.responsesStream(responses(true)));
		check(events.size() == 4 && "hello".equals(events.get(0).delta()), "Responses text/termination");
		check("call-1".equals(events.get(1).item().callId()) && "{}".equals(events.get(2).delta()), "Responses tool");
		check(events.get(3).response().usage().totalTokens() == 3, "terminal response decoding");
		fixture.expect("/images", true, sse(
				"{\"type\":\"image_generation.partial_image\",\"partial_image_index\":0,\"b64_json\":\"c3lu\"}",
				"{\"type\":\"image_generation.completed\",\"b64_json\":\"c3ludGhldGlj\",\"media_type\":\"image/png\"}",
				"{\"type\":\"image_generation.completed\",\"b64_json\":\"c2Vjb25k\",\"media_type\":\"image/png\"}", "[DONE]"));
		var images = collect(api.imagesStream(image(true)));
		check(images.size() == 3 && images.get(0).partialImageIndex() == 0, "partial image");
		check("c3ludGhldGlj".equals(images.get(1).b64Json()), "completed image");
		check("c2Vjb25k".equals(images.get(2).b64Json()), "second completed image");
		fixture.add(new Exchange("/images", true, 200, "application/json", IMAGE, false));
		check(collect(api.imagesStream(image(true))).get(0).type().equals(ImagesStreamEvent.COMPLETED),
				"image JSON fallback");
		fixture.expect("/chat/completions", true, sse(TEXT_DELTA));
		try {
			collect(api.chatCompletionStream(chat(true)));
			throw new AssertionError("Missing terminal event accepted");
		}
		catch (OpenRouterTruncatedResponseException expected) {
		}
	}

	private static void errors(OpenRouterApi api, Fixture fixture) {
		Map<String, Runnable> blocking = Map.of("/chat/completions", () -> api.chatCompletion(chat(false)),
				"/responses", () -> api.responses(responses(false)), "/embeddings", () -> api.embeddings(embedding()),
				"/images", () -> api.images(image(false)));
		blocking.forEach((path, call) -> httpError(fixture, path, false, call));
		httpError(fixture, "/chat/completions", true, () -> collect(api.chatCompletionStream(chat(true))));
		httpError(fixture, "/responses", true, () -> collect(api.responsesStream(responses(true))));
		httpError(fixture, "/images", true, () -> collect(api.imagesStream(image(true))));
		String error = "{\"code\":429,\"message\":\"synthetic failure\",\"metadata\":{\"provider_name\":\"synthetic\"}}";
		fixture.expect("/chat/completions", true, sse("{\"error\":" + error + "}"));
		check(collect(api.chatCompletionStream(chat(true))).get(0).error().code().equals("429"), "chat stream error");
		fixture.expect("/responses", true,
				sse("{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":" + error + "}}"));
		check(collect(api.responsesStream(responses(true))).get(0).response().error().code().equals("429"),
				"Responses failure");
		fixture.expect("/images", true, sse("{\"type\":\"error\",\"error\":" + error + "}"));
		check(collect(api.imagesStream(image(true))).get(0).error().code().equals("429"), "image stream error");
	}

	private static void httpError(Fixture fixture, String path, boolean stream, Runnable call) {
		fixture.add(new Exchange(path, stream, 400, "application/json",
				"""
						{"error":{"code":"synthetic_code","message":"synthetic failure","metadata":{"provider_name":"synthetic"}},"error_type":"invalid_request"}
						""",
				false));
		try {
			call.run();
			throw new AssertionError("HTTP error accepted: " + path);
		}
		catch (RuntimeException ex) {
			check(ex instanceof OpenRouterHttpException, "typed HTTP error");
			var error = (OpenRouterHttpException) ex;
			check(error.getStatusCode().value() == 400 && path.equals(error.getEndpoint()), "HTTP status/endpoint");
			check("synthetic_code".equals(error.getErrorDetails().code()), "HTTP error body decoding");
		}
	}

	private static void cancellation(OpenRouterApi api, Fixture fixture) throws InterruptedException {
		fixture.add(new Exchange("/chat/completions", true, 200, "text/event-stream", sse(TEXT_DELTA), true));
		check(collect(api.chatCompletionStream(chat(true)).take(1)).size() == 1, "downstream cancellation");
		check(fixture.disconnected.await(10, TimeUnit.SECONDS), "cancelled connection closed");
		// A subsequent request must still succeed after cancellation.
		fixture.expect("/chat/completions", false, CHAT);
		check(api.chatCompletion(chat(false)).choices().size() == 1, "request after cancellation");
	}

	private static <T> List<T> collect(Flux<T> stream) {
		return stream.collectList().block(DEADLINE);
	}

	private static String sse(String... events) {
		var body = new StringBuilder(": synthetic heartbeat\n\n");
		for (String event : events) {
			body.append("data: ").append(event.replace("\n", "")).append("\n\n");
		}
		return body.toString();
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}

	private record Exchange(String path, boolean stream, int status, String contentType, String body,
			boolean holdOpen) {
	}

	private static final class Fixture implements AutoCloseable {

		private final BlockingQueue<Exchange> exchanges = new LinkedBlockingQueue<>();

		private final AtomicReference<Throwable> failure = new AtomicReference<>();

		private final CountDownLatch disconnected = new CountDownLatch(1);

		private final ExecutorService executor = Executors.newCachedThreadPool();

		private final HttpServer server;

		Fixture() throws IOException {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			this.server.setExecutor(this.executor);
			this.server.createContext("/", this::handle);
			this.server.start();
		}

		String baseUrl() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		void add(Exchange exchange) {
			this.exchanges.add(exchange);
		}

		void expect(String path, boolean stream, String body) {
			add(new Exchange(path, stream, 200, stream ? "text/event-stream" : "application/json", body, false));
		}

		private void handle(HttpExchange http) throws IOException {
			try {
				Exchange exchange = this.exchanges.remove();
				check(http.getRequestMethod().equals("POST") && http.getRequestURI().getPath().equals(exchange.path()),
						"request route");
				var request = JSON.readTree(http.getRequestBody().readAllBytes());
				check("synthetic".equals(request.path("model").asString()), "model serialization");
				check(request.path("stream").asBoolean(false) == exchange.stream(), "stream serialization");
				switch (exchange.path()) {
					case "/chat/completions" -> {
						check(request.at("/messages/0/content/0/text").asString().equals("hello"),
								"typed chat content serialization");
						check(request.at("/messages/0/content/1/image_url/url").asString().startsWith("data:"),
								"nested content serialization");
						check(request.at("/tools/0/function/name").asString().equals("echo"), "tool serialization");
						check(request.path("max_tokens").asInt() == 32, "snake-case option serialization");
					}
					case "/responses" -> {
						check(request.at("/input/0/content/0/text").asString().equals("hello"),
								"typed Responses input serialization");
						check(request.at("/input/1/call_id").asString().equals("call-1"),
								"function call serialization");
						check(request.at("/input/2/output").asString().equals("ok"), "tool output serialization");
					}
					case "/embeddings" -> check(request.path("dimensions").asInt() == 2
							&& request.at("/input/0").asString().equals("hello"), "embedding serialization");
					case "/images" -> check(
							request.path("prompt").asString().equals("hello")
									&& request.at("/input_references/0/image_url/url").asString().startsWith("data:"),
							"image serialization");
					default -> throw new AssertionError("Unexpected endpoint");
				}
				http.getResponseHeaders().set("Content-Type", exchange.contentType());
				byte[] bytes = exchange.body().getBytes(StandardCharsets.UTF_8);
				http.sendResponseHeaders(exchange.status(), exchange.holdOpen() ? 0 : bytes.length);
				http.getResponseBody().write(bytes);
				http.getResponseBody().flush();
				if (exchange.holdOpen()) {
					try {
						while (!Thread.currentThread().isInterrupted()) {
							Thread.sleep(20);
							http.getResponseBody().write(bytes);
							http.getResponseBody().flush();
						}
					}
					catch (IOException expected) {
						this.disconnected.countDown();
					}
				}
			}
			catch (Throwable ex) {
				this.failure.compareAndSet(null, ex);
			}
			finally {
				http.close();
			}
		}

		void verify() {
			check(this.exchanges.isEmpty(), "unconsumed fixtures");
			if (this.failure.get() != null) {
				throw new AssertionError("Fixture failed", this.failure.get());
			}
		}

		@Override
		public void close() {
			this.server.stop(0);
			this.executor.shutdownNow();
		}

	}

}
