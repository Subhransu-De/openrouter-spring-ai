package de.subhransu.openrouter.springai.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class StreamRetriesTests {

	@ParameterizedTest
	@EnumSource(Mode.class)
	void rejectionRetriesAfterProviderDelayWithinOneObservation(Mode mode) {
		Fixture fixture = new Fixture(attempt -> Mono.just(attempt == 1 ? rejection(429, "3") : success(mode)));
		StepVerifier.withVirtualTime(() -> fixture.stream(mode, policy(2, 1000)))
			.expectSubscription()
			.expectNoEvent(Duration.ofSeconds(2))
			.then(() -> assertThat(fixture.attempts).hasValue(1))
			.thenAwait(Duration.ofSeconds(1))
			.expectNextCount(1)
			.expectComplete()
			.verify(Duration.ofSeconds(10));
		assertThat(fixture.attempts).hasValue(2);
		fixture.assertObservation(0);
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void exhaustionPreservesTypedErrorAndEachSubscriptionHasItsOwnAttempts(Mode mode) {
		Fixture fixture = new Fixture(attempt -> Mono.just(rejection(429, "0")));
		Flux<?> stream = fixture.stream(mode, policy(2, 0));
		for (int subscription = 1; subscription <= 2; subscription++) {
			StepVerifier.withVirtualTime(() -> stream).thenAwait(Duration.ofMillis(1)).expectErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(OpenRouterTransientApiException.class);
				assertThat(((OpenRouterHttpException) error).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
				assertThat(((OpenRouterHttpException) error).getRetryAfter().value()).isEqualTo("0");
			}).verify(Duration.ofSeconds(10));
			assertThat(fixture.attempts).hasValue(subscription * 3);
		}
		assertThat(fixture.stops).hasValue(2);
		assertThat(fixture.errors).hasValue(2);
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void nonEligibleHttpStatusesNeverRetryEvenWithPermissivePolicy(Mode mode) {
		for (int status : new int[] { 400, 401, 402, 403, 408, 500, 502, 503, 504 }) {
			Fixture fixture = new Fixture(attempt -> Mono.just(rejection(status, "0")));
			StepVerifier.create(fixture.stream(mode, policy(2, 0)))
				.expectErrorSatisfies(error -> assertThat(((OpenRouterHttpException) error).getStatusCode().value())
					.isEqualTo(status))
				.verify(Duration.ofSeconds(10));
			assertThat(fixture.attempts).hasValue(1);
			fixture.assertObservation(1);
		}
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void connectionFailureAndFailureAfterHeadersNeverRetry(Mode mode) {
		RuntimeException failure = new IllegalStateException("synthetic connection failure");
		for (boolean headersReceived : new boolean[] { false, true }) {
			Fixture fixture = new Fixture(
					attempt -> headersReceived ? Mono.just(sse(Flux.error(failure))) : Mono.error(failure));
			StepVerifier.create(fixture.stream(mode, policy(2, 0))).expectError().verify(Duration.ofSeconds(10));
			assertThat(fixture.attempts).hasValue(1);
			fixture.assertObservation(1);
		}
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void firstVisibleElementIsNeverReplayed(Mode mode) {
		RuntimeException failure = new IllegalStateException("synthetic body failure");
		Fixture fixture = new Fixture(
				attempt -> Mono.just(sse(Flux.concat(Mono.just(buffer(mode.first)), Mono.error(failure)))));
		StepVerifier.create(fixture.stream(mode, policy(2, 0)))
			.expectNextCount(1)
			.expectError()
			.verify(Duration.ofSeconds(10));
		assertThat(fixture.attempts).hasValue(1);
		fixture.assertObservation(1);
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void cancellationDuringBackoffStopsObservationAndDoesNotRetry(Mode mode) {
		Fixture fixture = new Fixture(attempt -> Mono.just(rejection(429, "3")));
		StepVerifier
			.withVirtualTime(() -> fixture.stream(mode, policy(2, 0))
				.take(Duration.ofSeconds(1))
				.then(Mono.delay(Duration.ofSeconds(5))))
			.expectSubscription()
			.thenAwait(Duration.ofSeconds(6))
			.expectNext(0L)
			.expectComplete()
			.verify(Duration.ofSeconds(10));
		assertThat(fixture.attempts).hasValue(1);
		fixture.assertObservation(0);
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void policyCanDenyRetriesAndTimeoutCanDeclineProviderDelay(Mode mode) {
		for (RetryPolicy policy : new RetryPolicy[] {
				RetryPolicy.builder().predicate(error -> false).backOff(new FixedBackOff(0, 2)).build(),
				RetryPolicy.builder().timeout(Duration.ofSeconds(2)).backOff(new FixedBackOff(0, 2)).build(),
				policy(0, 0) }) {
			Fixture fixture = new Fixture(attempt -> Mono.just(rejection(429, "3")));
			StepVerifier.create(fixture.stream(mode, policy))
				.expectError(OpenRouterTransientApiException.class)
				.verify(Duration.ofSeconds(10));
			assertThat(fixture.attempts).hasValue(1);
			fixture.assertObservation(1);
		}
	}

	@ParameterizedTest
	@EnumSource(Mode.class)
	void rejectedErrorBodyLimitsAndNonTransientCategoriesDoNotRetry(Mode mode) {
		for (String body : new String[] { "x".repeat(OpenRouterApi.DEFAULT_MAX_ERROR_BODY_BYTES + 1),
				"{\"error_type\":\"invalid_request_error\"}", "{\"error_type\":\"server_error\"}" }) {
			Fixture fixture = new Fixture(
					attempt -> Mono.just(ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS).body(body).build()));
			StepVerifier.create(fixture.stream(mode, policy(2, 0))).expectError().verify(Duration.ofSeconds(10));
			assertThat(fixture.attempts).hasValue(1);
		}
	}

	@Test
	void inBandRateLimitInAcceptedResponsesStreamDoesNotRetry() {
		Fixture fixture = new Fixture(attempt -> Mono.just(sse(Flux.just(buffer(
				"data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"rate_limit_exceeded\"}}}\n\n")))));
		StepVerifier.create(fixture.stream(Mode.RESPONSES, policy(2, 0)))
			.expectError(OpenRouterTransientApiException.class)
			.verify(Duration.ofSeconds(10));
		assertThat(fixture.attempts).hasValue(1);
		fixture.assertObservation(1);
	}

	@Test
	void evenAnOtherwiseEligibleFailureCannotReplayAnEmittedMetadataElement() {
		RuntimeException rejection = new OpenRouterHttpExceptionFactory(new ObjectMapper(), null).create("/responses",
				HttpStatus.TOO_MANY_REQUESTS, new HttpHeaders(), "{}");
		AtomicInteger attempts = new AtomicInteger();
		Flux<String> source = Flux.defer(() -> {
			attempts.incrementAndGet();
			return Flux.just("metadata").concatWith(StreamRetries.rejected(rejection).flatMapMany(Flux::error));
		});
		StepVerifier.create(StreamRetries.stream(source, new RetryTemplate(policy(2, 0))))
			.expectNext("metadata")
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(rejection))
			.verify(Duration.ofSeconds(10));
		assertThat(attempts).hasValue(1);
	}

	private static RetryPolicy policy(long retries, long delay) {
		return RetryPolicy.builder().backOff(new FixedBackOff(delay, retries)).build();
	}

	private static ClientResponse rejection(int status, String retryAfter) {
		return ClientResponse.create(HttpStatus.valueOf(status))
			.header(HttpHeaders.RETRY_AFTER, retryAfter)
			.body("{}")
			.build();
	}

	private static ClientResponse success(Mode mode) {
		return sse(Flux.just(buffer(mode.complete)));
	}

	private static ClientResponse sse(Flux<DataBuffer> body) {
		return ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(body)
			.build();
	}

	private static DataBuffer buffer(String value) {
		return DefaultDataBufferFactory.sharedInstance.wrap(value.getBytes(StandardCharsets.UTF_8));
	}

	private enum Mode {

		CHAT("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"}}]}\n\n",
				"data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
		RESPONSES("data: {\"type\":\"response.output_text.delta\",\"output_index\":0,\"delta\":\"hello\"}\n\n",
				"data: {\"type\":\"response.completed\",\"response\":{\"id\":\"synthetic\",\"status\":\"completed\",\"output\":[]}}\n\n"),
		IMAGE("data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"aW1hZ2U=\",\"partial_image_index\":0}\n\n",
				"data: {\"type\":\"image_generation.completed\",\"b64_json\":\"aW1hZ2U=\"}\n\ndata: [DONE]\n\n");

		private final String first;

		private final String complete;

		Mode(String first, String complete) {
			this.first = first;
			this.complete = complete;
		}

	}

	private static final class Fixture {

		private final AtomicInteger attempts = new AtomicInteger();

		private final AtomicInteger starts = new AtomicInteger();

		private final AtomicInteger stops = new AtomicInteger();

		private final AtomicInteger errors = new AtomicInteger();

		private final ObservationRegistry registry = ObservationRegistry.create();

		private final OpenRouterApi api;

		Fixture(IntFunction<Mono<ClientResponse>> transport) {
			this.api = OpenRouterApi.builder()
				.apiKey("synthetic-key")
				.webClientBuilder(WebClient.builder()
					.exchangeFunction(request -> Mono.defer(() -> transport.apply(this.attempts.incrementAndGet()))))
				.build();
			this.registry.observationConfig().observationHandler(new ObservationHandler<Observation.Context>() {
				@Override
				public boolean supportsContext(Observation.Context context) {
					return true;
				}

				@Override
				public void onStart(Observation.Context context) {
					Fixture.this.starts.incrementAndGet();
				}

				@Override
				public void onStop(Observation.Context context) {
					Fixture.this.stops.incrementAndGet();
				}

				@Override
				public void onError(Observation.Context context) {
					Fixture.this.errors.incrementAndGet();
				}
			});
		}

		Flux<?> stream(Mode mode, RetryPolicy policy) {
			if (mode == Mode.IMAGE) {
				return OpenRouterImageModel.builder()
					.openRouterApi(this.api)
					.retryTemplate(new RetryTemplate(policy))
					.observationRegistry(this.registry)
					.defaultOptions(OpenRouterImageOptions.builder().model("synthetic").build())
					.build()
					.stream(new ImagePrompt("synthetic"));
			}
			return OpenRouterChatModel.builder()
				.openRouterApi(this.api)
				.retryTemplate(new RetryTemplate(policy))
				.observationRegistry(this.registry)
				.defaultOptions(OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(mode == Mode.CHAT ? OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
							: OpenRouterRequestMode.OPENAI_RESPONSES)
					.build())
				.build()
				.stream(new Prompt("synthetic"));
		}

		void assertObservation(int expectedErrors) {
			assertThat(this.starts).hasValue(1);
			assertThat(this.stops).hasValue(1);
			assertThat(this.errors).hasValue(expectedErrors);
		}

	}

}
