package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterTransportCustomizationTests {

	private static final String REQUEST_HEADER = "X-Synthetic-Request";

	private static final String REMAINING_HEADER = "X-Synthetic-Remaining";

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	@SuppressWarnings("PMD.CloseResource") // Java 17 has no ExecutorService.close();
											// finally shuts it down.
	void blockingInterceptorIsolatesConcurrentHeadersAndReadsDiagnostics(boolean error) throws Exception {
		ThreadLocal<String> correlation = new ThreadLocal<>();
		var diagnostics = new ConcurrentHashMap<String, String>();
		CountDownLatch entered = new CountDownLatch(2);
		RestClient.Builder transport = RestClient.builder().requestInterceptor((request, body, execution) -> {
			request.getHeaders().set(REQUEST_HEADER, correlation.get());
			var response = execution.execute(request, body);
			diagnostics.put(correlation.get(), response.getHeaders().getFirst(REMAINING_HEADER));
			return response;
		}).requestFactory((uri, method) -> new MockClientHttpRequest(method, uri) {
			@Override
			protected MockClientHttpResponse executeInternal() {
				entered.countDown();
				try {
					assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
				String body = error ? "{\"error\":{\"message\":\"synthetic limit\",\"code\":429}}" : "{\"data\":[]}";
				var response = new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8),
						error ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK);
				response.getHeaders().set(REMAINING_HEADER, getHeaders().getFirst(REQUEST_HEADER));
				response.getHeaders().set("Retry-After", "7");
				return response;
			}
		});
		OpenRouterApi api = OpenRouterApi.builder().apiKey("synthetic-key").restClientBuilder(transport).build();
		var executor = Executors.newFixedThreadPool(2);
		try {
			var tasks = List.of("one", "two").stream().map(id -> executor.submit(() -> {
				correlation.set(id);
				try {
					var request = new EmbeddingsRequest("synthetic", List.of("input"), null, null, null, null);
					if (error) {
						assertThatThrownBy(() -> api.embeddings(request)).isInstanceOfSatisfying(
								OpenRouterTransientApiException.class,
								failure -> assertThat(failure.getRetryAfter().delay())
									.isEqualTo(Duration.ofSeconds(7)));
					}
					else {
						assertThat(api.embeddings(request).data()).isEmpty();
					}
				}
				finally {
					correlation.remove();
				}
			})).toList();
			for (var task : tasks) {
				task.get(10, TimeUnit.SECONDS);
			}
			assertThat(diagnostics).containsEntry("one", "one").containsEntry("two", "two");
		}
		finally {
			executor.shutdownNow();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void reactiveFilterIsolatesSubscriptionHeadersAndReadsDiagnostics(boolean error) {
		var diagnostics = new ConcurrentHashMap<String, String>();
		WebClient.Builder transport = WebClient.builder().filter((request, next) -> Mono.deferContextual(context -> {
			String id = context.get("synthetic-request");
			return next.exchange(ClientRequest.from(request).header(REQUEST_HEADER, id).build())
				.doOnNext(response -> diagnostics.put(id, response.headers().header(REMAINING_HEADER).get(0)));
		}))
			.exchangeFunction(request -> Mono.delay(Duration.ofSeconds(1))
				.map(ignored -> ClientResponse.create(error ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.OK)
					.header(REMAINING_HEADER, request.headers().getFirst(REQUEST_HEADER))
					.header("Retry-After", "7")
					.header("Content-Type",
							error ? MediaType.APPLICATION_JSON_VALUE : MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(error ? "{\"error\":{\"code\":429,\"message\":\"synthetic limit\"}}" : "data: [DONE]\n\n")
					.build()));
		OpenRouterApi api = OpenRouterApi.builder().apiKey("synthetic-key").webClientBuilder(transport).build();
		StepVerifier.withVirtualTime(() -> Flux.merge(
				List.of("one", "two").stream().map(id -> api.imagesStream(imageRequest()).then(Mono.fromSupplier(() -> {
					assertThat(error).isFalse();
					return id;
				})).onErrorResume(OpenRouterTransientApiException.class, failure -> {
					assertThat(error).isTrue();
					assertThat(failure.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(7));
					return Mono.just(id);
				}).contextWrite(context -> context.put("synthetic-request", id))).toList()))
			.thenAwait(Duration.ofSeconds(1))
			.expectNextCount(2)
			.verifyComplete();
		assertThat(diagnostics).containsEntry("one", "one").containsEntry("two", "two");
	}

	@Test
	void subscriptionDeadlineDoesNotChangeAnotherSubscriptionsTimeout() {
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.timeout(Duration.ofSeconds(10))
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.delay(Duration.ofSeconds(3))
					.map(ignored -> ClientResponse.create(HttpStatus.OK)
						.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
						.body("data: [DONE]\n\n")
						.build())))
			.build();
		StepVerifier
			.withVirtualTime(() -> Flux.merge(
					api.imagesStream(imageRequest())
						.timeout(Duration.ofSeconds(1))
						.then(Mono.just("unexpected"))
						.onErrorReturn(TimeoutException.class, "deadline"),
					api.imagesStream(imageRequest()).then(Mono.just("completed"))))
			.thenAwait(Duration.ofSeconds(1))
			.expectNext("deadline")
			.thenAwait(Duration.ofSeconds(2))
			.expectNext("completed")
			.verifyComplete();
	}

	private ImagesRequest imageRequest() {
		return new ImagesRequest("synthetic", "input", null, null, null, null, null, null, null, null, null, true, null,
				null);
	}

}
