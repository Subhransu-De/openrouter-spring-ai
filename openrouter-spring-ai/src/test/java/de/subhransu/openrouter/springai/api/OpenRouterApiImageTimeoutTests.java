package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterApiImageTimeoutTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

	private static final String JSON = """
			{"created":42,"data":[{"b64_json":"YQ=="},{"b64_json":"Yg=="}],"usage":{"total_tokens":2}}
			""";

	@ParameterizedTest(name = "{4}")
	@CsvSource(delimiter = '|', value = { "200|text/event-stream|20|0|SSE headers never arrive",
			"200|application/json|20|0|JSON headers never arrive",
			"400|application/json|20|0|error headers never arrive",
			"400|application/json|0|20|error body never arrives", "200|application/json|0|20|JSON body never arrives",
			"200|text/event-stream|0|20|SSE body never arrives",
			"200|application/json|6|6|JSON headers and body each slower than half the timeout",
			"200|text/event-stream|6|6|SSE headers and body each slower than half the timeout" })
	void timeoutCoversHeadersAndBody(int status, String contentType, int headerDelay, int bodyDelay, String scenario) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier.withVirtualTime(() -> {
			Flux<DataBuffer> body = Mono.delay(Duration.ofSeconds(bodyDelay))
				.flatMapMany(tick -> status == 200 && contentType.equals("application/json") ? Flux.just(buffer(JSON))
						: Flux.never())
				.doOnCancel(() -> cancelled.set(true));
			Mono<ClientResponse> response = Mono.delay(Duration.ofSeconds(headerDelay))
				.map(tick -> response(status, contentType, body))
				.doOnCancel(() -> cancelled.set(true));
			return stream(response, TIMEOUT);
		})
			.expectSubscription()
			.expectNoEvent(TIMEOUT.minusSeconds(1))
			.thenAwait(Duration.ofSeconds(1))
			.expectError(TimeoutException.class)
			.verify(VERIFY_TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("resettingEvents")
	void eachSseEventResetsTimeoutBeforeFiltering(String kind, String event, int emitted) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier.withVirtualTime(() -> {
			Flux<DataBuffer> body = Flux.interval(Duration.ofSeconds(6))
				.take(3)
				.map(tick -> buffer(event))
				.concatWith(Flux.never())
				.doOnCancel(() -> cancelled.set(true));
			return stream(Mono.just(response(200, "text/event-stream", body)), TIMEOUT);
		})
			.thenAwait(Duration.ofSeconds(18))
			.expectNextCount(emitted)
			.expectNoEvent(TIMEOUT.minusSeconds(1))
			.thenAwait(Duration.ofSeconds(1))
			.expectError(TimeoutException.class)
			.verify(VERIFY_TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	static Stream<Arguments> resettingEvents() {
		return Stream.of(Arguments.of("SSE comment is filtered but resets the timeout", ": keep-alive\n\n", 0),
				Arguments.of("partial image event resets the timeout and is emitted",
						"data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"YQ==\"}\n\n", 3));
	}

	@ParameterizedTest(name = "timeout {0}")
	@ValueSource(strings = { "disabled", "enabled" })
	void jsonFallbackCompletesAccordingToTheTimeoutSetting(String setting) {
		boolean timeoutEnabled = setting.equals("enabled");
		StepVerifier.withVirtualTime(() -> {
			Flux<DataBuffer> body = Mono.delay(Duration.ofSeconds(timeoutEnabled ? 2 : 20))
				.map(tick -> buffer(JSON))
				.flux();
			return stream(Mono.delay(Duration.ofSeconds(2)).map(tick -> response(200, "application/json", body)),
					timeoutEnabled ? TIMEOUT : null);
		})
			.thenAwait(Duration.ofSeconds(22))
			.assertNext(event -> assertThat(event.b64Json()).isEqualTo("YQ=="))
			.assertNext(event -> assertThat(event.b64Json()).isEqualTo("Yg=="))
			.expectComplete()
			.verify(VERIFY_TIMEOUT);
	}

	@ParameterizedTest(name = "{3}")
	@CsvSource(delimiter = '|',
			value = { "true|200|text/event-stream|cancelled while headers are pending",
					"false|200|text/event-stream|cancelled while the SSE body is pending",
					"false|200|application/json|cancelled while the JSON body is pending",
					"false|400|application/json|cancelled while the error body is pending" })
	void downstreamCancellationReachesPendingExchange(boolean pendingHeaders, int status, String contentType,
			String scenario) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier.withVirtualTime(() -> {
			Mono<ClientResponse> response = pendingHeaders
					? Mono.<ClientResponse>never().doOnCancel(() -> cancelled.set(true)) : Mono.just(response(status,
							contentType, Flux.<DataBuffer>never().doOnCancel(() -> cancelled.set(true))));
			return stream(response, TIMEOUT);
		}).thenAwait(Duration.ofSeconds(1)).thenCancel().verify(VERIFY_TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	private Flux<ImagesStreamEvent> stream(Mono<ClientResponse> response, Duration timeout) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(request -> response))
			.timeout(timeout)
			.build()
			.imagesStream(new ImagesRequest("test-model", "synthetic image", null, null, null, null, null, null, null,
					null, null, true, null, null));
	}

	private ClientResponse response(int status, String contentType, Flux<DataBuffer> body) {
		// HTTP bodies are single-use; cleanup must not replay the synthetic publisher.
		AtomicBoolean subscribed = new AtomicBoolean();
		return ClientResponse.create(HttpStatus.valueOf(status))
			.header(HttpHeaders.CONTENT_TYPE, contentType)
			.body(Flux.defer(() -> subscribed.compareAndSet(false, true) ? body : Flux.empty()))
			.build();
	}

	private DataBuffer buffer(String value) {
		return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
	}

}
