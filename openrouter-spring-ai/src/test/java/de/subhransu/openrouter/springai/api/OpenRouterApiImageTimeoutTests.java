package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterTransportException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
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

@Tag("multimodal")
@Tag("multimodal.image-model")
class OpenRouterApiImageTimeoutTests {

	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

	private static final String JSON = """
			{"created":42,"data":[{"b64_json":"YQ=="},{"b64_json":"Yg=="}],"usage":{"total_tokens":2}}
			""";

	@ParameterizedTest
	@CsvSource({ "200,text/event-stream,20,0", "200,application/json,20,0", "400,application/json,20,0",
			"400,application/json,0,20", "200,application/json,0,20", "200,text/event-stream,0,20",
			"200,application/json,6,6", "200,text/event-stream,6,6" })
	void timeoutCoversHeadersAndBody(int status, String contentType, int headerDelay, int bodyDelay) {
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
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
						assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.TIMEOUT);
						assertThat(transport.getEndpoint()).isEqualTo("/images");
						assertThat(transport.getCategory()).isEqualTo(OpenRouterErrorCategory.TIMEOUT);
						assertThat(transport).hasCauseInstanceOf(TimeoutException.class);
					}))
			.verify(VERIFY_TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { ": keep-alive\n\n",
			"data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"YQ==\"}\n\n" })
	void eachSseEventResetsTimeoutBeforeFiltering(String event) {
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
			.expectNextCount(event.startsWith(":") ? 0 : 3)
			.expectNoEvent(TIMEOUT.minusSeconds(1))
			.thenAwait(Duration.ofSeconds(1))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
						assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.TIMEOUT);
						assertThat(transport.getEndpoint()).isEqualTo("/images");
						assertThat(transport.getCategory()).isEqualTo(OpenRouterErrorCategory.TIMEOUT);
						assertThat(transport).hasCauseInstanceOf(TimeoutException.class);
					}))
			.verify(VERIFY_TIMEOUT);
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void jsonFallbackPreservesAllImagesAndFinalUsage(boolean timeoutEnabled) {
		StepVerifier.withVirtualTime(() -> {
			Flux<DataBuffer> body = Mono.delay(Duration.ofSeconds(timeoutEnabled ? 2 : 20))
				.map(tick -> buffer(JSON))
				.flux();
			return stream(Mono.delay(Duration.ofSeconds(2)).map(tick -> response(200, "application/json", body)),
					timeoutEnabled ? TIMEOUT : null);
		}).thenAwait(Duration.ofSeconds(22)).assertNext(event -> {
			assertThat(event.type()).isEqualTo(ImagesStreamEvent.COMPLETED);
			assertThat(event.b64Json()).isEqualTo("YQ==");
			assertThat(event.created()).isEqualTo(42);
			assertThat(event.usage()).isNull();
		}).assertNext(event -> {
			assertThat(event.b64Json()).isEqualTo("Yg==");
			assertThat(event.usage().totalTokens()).isEqualTo(2);
		}).expectComplete().verify(VERIFY_TIMEOUT);
	}

	@ParameterizedTest
	@CsvSource({ "true,200,text/event-stream", "false,200,text/event-stream", "false,200,application/json",
			"false,400,application/json" })
	void downstreamCancellationReachesPendingExchange(boolean pendingHeaders, int status, String contentType) {
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
