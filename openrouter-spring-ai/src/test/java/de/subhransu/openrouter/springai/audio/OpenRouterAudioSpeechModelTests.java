package de.subhransu.openrouter.springai.audio;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransportException;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("multimodal")
@Tag("multimodal.speech-model")
class OpenRouterAudioSpeechModelTests {

	private static final String MODEL = "synthetic/speech";

	private static final byte[] MP3 = { (byte) 0xFF, (byte) 0xF3, 0x44, (byte) 0xC4, 0x00, 0x01 };

	private static final byte[] PCM = { 0x00, 0x00, (byte) 0xFF, 0x7F, 0x00, (byte) 0x80 };

	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	private final ObjectMapper json = new ObjectMapper();

	private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

	private final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (this.server != null) {
			this.server.stop(0);
		}
	}

	@ParameterizedTest
	@CsvSource({ "mp3, audio/mpeg", "pcm, audio/pcm" })
	void callReturnsTheCompleteAudioInTheRequestedFormat(String format, String contentType) {
		byte[] audio = "mp3".equals(format) ? MP3 : PCM;
		OpenRouterAudioSpeechModel model = model(exchange -> respond(exchange, 200, contentType, audio));

		TextToSpeechResponse response = model.call(new TextToSpeechPrompt("The brake pads are worn.",
				OpenRouterAudioSpeechOptions.builder().format(format).build()));

		assertThat(response.getResults()).singleElement()
			.satisfies(speech -> assertThat(speech.getOutput()).containsExactly(audio));
		assertThat(this.requests).singleElement().satisfies(request -> {
			assertThat(request.path("response_format").asString()).isEqualTo(format);
			assertThat(request.path("input").asString()).isEqualTo("The brake pads are worn.");
		});
	}

	@Test
	void defaultsAndRequestOptionsReachTheWire() {
		OpenRouterAudioSpeechOptions defaults = OpenRouterAudioSpeechOptions.builder()
			.model(MODEL)
			.voice("default-voice")
			.speed(1.0)
			.instructions("Calm and clear.")
			.user("synthetic-user")
			.sessionId("default-session")
			.trace(OpenRouterTrace.builder().traceName("garage").metadata(Map.of("bay", "3")).build())
			.provider(OpenRouterProviderPreferences.builder()
				.order(List.of("provider-a"))
				.allowFallbacks(false)
				.dataCollection("deny")
				.ignore(List.of("provider-b"))
				.build())
			.build();
		OpenRouterAudioSpeechModel model = model(exchange -> respond(exchange, 200, "audio/mpeg", MP3), defaults);

		model.call(new TextToSpeechPrompt("Ready for pickup.",
				OpenRouterAudioSpeechOptions.builder()
					.voice("request-voice")
					.format("mp3")
					.sessionId("request-session")
					.trace(OpenRouterTrace.builder().traceId("run-7").build())
					.build()));

		JsonNode request = this.requests.get(0);
		assertThat(request.path("model").asString()).isEqualTo(MODEL);
		assertThat(request.path("voice").asString()).isEqualTo("request-voice");
		assertThat(request.path("speed").asDouble()).isEqualTo(1.0);
		assertThat(request.path("instructions").asString()).isEqualTo("Calm and clear.");
		assertThat(request.path("user").asString()).isEqualTo("synthetic-user");
		assertThat(request.path("session_id").asString()).isEqualTo("request-session");
		// A request trace replaces the default trace as a whole.
		assertThat(request.path("trace")).isEqualTo(this.json.readTree("{\"trace_id\":\"run-7\"}"));
		assertThat(request.path("provider"))
			.isEqualTo(this.json.readTree("{\"allow_fallbacks\":false,\"data_collection\":\"deny\","
					+ "\"order\":[\"provider-a\"],\"ignore\":[\"provider-b\"]}"));
		assertThat(request.has("input_references")).isFalse();
	}

	@Test
	void unsetOptionalFieldsAreLeftOutOfTheRequest() {
		model(exchange -> respond(exchange, 200, "audio/pcm", PCM)).call(new TextToSpeechPrompt("Hello."));

		assertThat(this.requests.get(0).propertyNames()).containsExactlyInAnyOrder("model", "input");
	}

	@Test
	void portableOptionsCarryOverAndAnUndocumentedFormatIsRejected() {
		OpenRouterAudioSpeechOptions converted = OpenRouterAudioSpeechOptions.fromOptions(
				TextToSpeechOptions.builder().model("portable/model").voice("alloy").format("mp3").speed(1.25).build());

		assertThat(converted.getModel()).isEqualTo("portable/model");
		assertThat(converted.getVoice()).isEqualTo("alloy");
		assertThat(converted.getFormat()).isEqualTo("mp3");
		assertThat(converted.getSpeed()).isEqualTo(1.25);
		assertThatThrownBy(
				() -> OpenRouterAudioSpeechOptions.fromOptions(TextToSpeechOptions.builder().format("wav").build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("speech format \"wav\" is not supported; use mp3 or pcm");
		assertThatThrownBy(() -> OpenRouterAudioSpeechOptions.builder().speed(0.0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpenRouterAudioSpeechOptions.builder().voice(""))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> OpenRouterAudioSpeechOptions.builder().sessionId("x".repeat(257)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sessionId");
	}

	@Test
	void invalidRequestsFailBeforeAnyRequestIsSent() {
		OpenRouterAudioSpeechModel model = model(exchange -> respond(exchange, 200, "audio/pcm", PCM),
				OpenRouterAudioSpeechOptions.builder().build());

		assertThatThrownBy(() -> model.call(new TextToSpeechPrompt("Hello."))).hasMessageContaining("Speech model");
		assertThatThrownBy(() -> model
			.call(new TextToSpeechPrompt(" ", OpenRouterAudioSpeechOptions.builder().model(MODEL).build())))
			.hasMessageContaining("Speech text");
		assertThatThrownBy(() -> model.call(new TextToSpeechPrompt("Hello.",
				OpenRouterAudioSpeechOptions.builder()
					.model(MODEL)
					.provider(OpenRouterProviderPreferences.builder().sort("price").requireParameters(true).build())
					.build())))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("does not apply provider requireParameters, sort");
		assertThat(this.requests).isEmpty();
	}

	@Test
	void streamEmitsEachChunkBeforeTheNextArrives() {
		CountDownLatch firstChunkSeen = new CountDownLatch(1);
		OpenRouterAudioSpeechModel model = model(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
			exchange.sendResponseHeaders(200, 0);
			exchange.getResponseBody().write(new byte[] { 1, 2, 3 });
			exchange.getResponseBody().flush();
			// The second chunk waits until the client has seen the first; a client
			// that buffered the whole body would never see it, and the test times out.
			await(firstChunkSeen);
			exchange.getResponseBody().write(new byte[] { 4, 5 });
			exchange.close();
		});

		StepVerifier.create(model.stream(new TextToSpeechPrompt("Stream the note.")))
			.assertNext(response -> assertThat(response.getResult().getOutput()).containsExactly(1, 2, 3))
			.then(firstChunkSeen::countDown)
			.assertNext(response -> assertThat(response.getResult().getOutput()).containsExactly(4, 5))
			.expectComplete()
			.verify(TIMEOUT);
		assertThat(this.requests).singleElement()
			.satisfies(request -> assertThat(request.path("input").asString()).isEqualTo("Stream the note."));
		// A compressing server could hold chunks back, so the stream asks for none.
		assertThat(this.acceptEncodings).containsExactly("identity");
	}

	@Test
	void aStreamThatBreaksAfterTheFirstChunkFails() {
		OpenRouterAudioSpeechModel model = model(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "audio/mpeg");
			// Promise more bytes than are sent, then close: the connection ends early.
			exchange.sendResponseHeaders(200, 100);
			exchange.getResponseBody().write(new byte[] { 1, 2, 3 });
			exchange.getResponseBody().flush();
			exchange.close();
		});

		StepVerifier.create(model.stream(new TextToSpeechPrompt("Stream the note.")))
			.assertNext(response -> assertThat(response.getResult().getOutput()).containsExactly(1, 2, 3))
			.expectError(OpenRouterTransportException.class)
			.verify(TIMEOUT);
	}

	@Test
	void cancellingTheStreamCancelsThePendingBody() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<DataBuffer> body = Flux.<DataBuffer>just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[] { 9 }))
			.concatWith(Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, "audio/mpeg")
					.body(body)
					.build())))
			.build();
		OpenRouterAudioSpeechModel model = OpenRouterAudioSpeechModel.builder()
			.openRouterApi(api)
			.options(OpenRouterAudioSpeechOptions.builder().model(MODEL).build())
			.build();

		StepVerifier.create(model.stream(new TextToSpeechPrompt("Stream the note.")))
			.assertNext(response -> assertThat(response.getResult().getOutput()).containsExactly(9))
			.thenCancel()
			.verify(TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@Test
	void providerErrorsAreClassifiedInCallsAndStreams() {
		String error = "{\"error\":{\"code\":400,\"message\":\"Voice not found\"}}";
		OpenRouterAudioSpeechModel model = model(
				exchange -> respond(exchange, 400, "application/json", error.getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> model.call(new TextToSpeechPrompt("Hello.")))
			.isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, failure -> {
				assertThat(failure.getStatusCode().value()).isEqualTo(400);
				assertThat(failure.getErrorDetails().message()).isEqualTo("Voice not found");
			});
		StepVerifier.create(model.stream(new TextToSpeechPrompt("Hello.")))
			.expectErrorSatisfies(failure -> assertThat(failure).isInstanceOf(OpenRouterNonTransientApiException.class)
				.satisfies(http -> assertThat(((OpenRouterHttpFailure) http).getStatusCode().value()).isEqualTo(400)))
			.verify(TIMEOUT);
	}

	@Test
	void aRateLimitIsRetriedBeforeTheAudioArrives() {
		int[] attempts = { 0 };
		OpenRouterAudioSpeechModel model = model(exchange -> {
			if (attempts[0]++ == 0) {
				respond(exchange, 429, "application/json",
						"{\"error\":{\"code\":429,\"message\":\"Rate limited\"}}".getBytes(StandardCharsets.UTF_8));
			}
			else {
				respond(exchange, 200, "audio/mpeg", MP3);
			}
		});

		assertThat(model.call(new TextToSpeechPrompt("Hello.")).getResult().getOutput()).containsExactly(MP3);
		assertThat(this.requests).hasSize(2);
	}

	@Test
	void aJsonOrEmptySuccessBodyIsNotReturnedAsAudio() {
		OpenRouterAudioSpeechModel json = model(
				exchange -> respond(exchange, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8)));

		assertThatThrownBy(() -> json.call(new TextToSpeechPrompt("Hello."))).isInstanceOfSatisfying(
				OpenRouterProtocolException.class,
				failure -> assertThat(failure.getKind()).isEqualTo(OpenRouterProtocolException.Kind.CONTRACT));
		StepVerifier.create(json.stream(new TextToSpeechPrompt("Hello.")))
			.expectError(OpenRouterProtocolException.class)
			.verify(TIMEOUT);
		stopServer();

		OpenRouterAudioSpeechModel empty = model(exchange -> respond(exchange, 200, "audio/mpeg", new byte[0]));
		assertThatThrownBy(() -> empty.call(new TextToSpeechPrompt("Hello."))).isInstanceOfSatisfying(
				OpenRouterProtocolException.class,
				failure -> assertThat(failure.getKind()).isEqualTo(OpenRouterProtocolException.Kind.EMPTY_BODY));
		StepVerifier.create(empty.stream(new TextToSpeechPrompt("Hello.")))
			.expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(
					OpenRouterProtocolException.class,
					protocol -> assertThat(protocol.getKind()).isEqualTo(OpenRouterProtocolException.Kind.EMPTY_BODY)))
			.verify(TIMEOUT);
	}

	@Tag("observability")
	@Tag("observability.observations")
	@Test
	void callsAndStreamsAreObservedAsTextToSpeechOperations() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterAudioSpeechModel model = OpenRouterAudioSpeechModel.builder()
			.openRouterApi(api(exchange -> respond(exchange, 200, "audio/mpeg", MP3)))
			.options(OpenRouterAudioSpeechOptions.builder().model(MODEL).voice("alloy").format("mp3").build())
			.observationRegistry(registry)
			.build();

		model.call(new TextToSpeechPrompt("Hello."));
		model.stream(new TextToSpeechPrompt("Hello.")).blockLast(TIMEOUT);

		assertThat(registry).hasNumberOfObservationsWithNameEqualTo("gen_ai.client.operation", 2)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "text_to_speech")
			.hasLowCardinalityKeyValue("gen_ai.system", "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasHighCardinalityKeyValue("gen_ai.request.audio.voice", "alloy")
			.hasBeenStopped();
	}

	private OpenRouterAudioSpeechModel model(Handler handler) {
		return model(handler, OpenRouterAudioSpeechOptions.builder().model(MODEL).build());
	}

	private OpenRouterAudioSpeechModel model(Handler handler, OpenRouterAudioSpeechOptions defaults) {
		return OpenRouterAudioSpeechModel.builder()
			.openRouterApi(api(handler))
			.options(defaults)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder()
				.maxRetries(1)
				.delay(Duration.ofMillis(1))
				.includes(org.springframework.ai.retry.TransientAiException.class)
				.build()))
			.build();
	}

	private OpenRouterApi api(Handler handler) {
		try {
			this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		this.server.createContext("/api/v1/audio/speech", exchange -> {
			this.requests.add(this.json.readTree(exchange.getRequestBody().readAllBytes()));
			this.acceptEncodings.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
			handler.handle(exchange);
		});
		this.server.start();
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("http://127.0.0.1:" + this.server.getAddress().getPort() + "/api/v1")
			.build();
	}

	private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static void await(CountDownLatch latch) throws IOException {
		try {
			if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IOException("the client never received the first chunk");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException(ex);
		}
	}

	@FunctionalInterface
	private interface Handler {

		void handle(HttpExchange exchange) throws IOException;

	}

}
