package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterTransportException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Transport failures surface as {@link OpenRouterTransportException}, while the retry
 * policy keeps deciding on the original exception, so retries are unchanged.
 */
@Tag("http")
@Tag("http.retry")
class OpenRouterTransportFailureRetryTests {

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	@ParameterizedTest
	@EnumSource(value = OpenRouterRequestMode.class, names = { "OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES" })
	void blockingTimeoutIsStillRetriedBySpringAiDefaultPolicyShape(OpenRouterRequestMode mode) {
		AtomicInteger attempts = new AtomicInteger();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(failingBlockingApi(attempts, () -> new SocketTimeoutException("synthetic read timeout")))
			.retryTemplate(retryTemplate(
					RetryPolicy.builder().includes(TransientAiException.class).includes(ResourceAccessException.class)))
			.build();

		assertThatThrownBy(() -> model.call(prompt(mode)))
			.isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
				assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.TIMEOUT);
				assertThat(transport.getEndpoint())
					.isEqualTo(mode == OpenRouterRequestMode.OPENAI_RESPONSES ? "/responses" : "/chat/completions");
				assertThat(transport.getCategory()).isEqualTo(OpenRouterErrorCategory.TIMEOUT);
				assertThat(transport.isTransient()).isTrue();
				assertThat(transport.getMessage()).doesNotContain(BASE_URL);
			})
			.hasCauseInstanceOf(ResourceAccessException.class)
			.hasRootCauseInstanceOf(SocketTimeoutException.class);
		assertThat(attempts).hasValue(3);
	}

	@Test
	void blockingTransportFailureIsStillNotRetriedWhenThePolicyOmitsResourceAccessException() {
		AtomicInteger attempts = new AtomicInteger();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(failingBlockingApi(attempts, () -> new SocketTimeoutException("synthetic read timeout")))
			.retryTemplate(retryTemplate(RetryPolicy.builder().includes(TransientAiException.class)))
			.build();

		assertThatThrownBy(() -> model.call(prompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)))
			.isInstanceOf(OpenRouterTransportException.class);
		assertThat(attempts).hasValue(1);
	}

	@Test
	void retryPredicateStillSeesTheOriginalTransportException() {
		AtomicInteger attempts = new AtomicInteger();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(failingBlockingApi(attempts, () -> new SocketTimeoutException("synthetic read timeout")))
			.retryTemplate(retryTemplate(
					RetryPolicy.builder().predicate(failure -> failure.getClass() == ResourceAccessException.class)))
			.build();

		assertThatThrownBy(() -> model.call(prompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)))
			.isInstanceOf(OpenRouterTransportException.class);
		assertThat(attempts).hasValue(3);
	}

	@Test
	void blockingConnectFailureIsRetriedAndReportedAsConnect() {
		AtomicInteger attempts = new AtomicInteger();
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(failingBlockingApi(attempts, () -> new ConnectException("synthetic connection refused")))
			.retryTemplate(retryTemplate(
					RetryPolicy.builder().includes(TransientAiException.class).includes(ResourceAccessException.class)))
			.build();

		assertThatThrownBy(() -> model.call(new EmbeddingRequest(List.of("hello"),
				OpenRouterEmbeddingOptions.builder().model("openai/text-embedding-3-small").build())))
			.isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
				assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.CONNECT);
				assertThat(transport.getEndpoint()).isEqualTo("/embeddings");
				assertThat(transport.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
			});
		assertThat(attempts).hasValue(3);
	}

	@Test
	void streamIdleTimeoutIsNotRetriedAndKeepsTheReactorTimeoutAsCause() {
		AtomicInteger exchanges = new AtomicInteger();
		DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
		Flux<DataBuffer> stalled = Flux.<DataBuffer>just(buffers
			.wrap("""
					data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

					"""
				.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.timeout(Duration.ofMillis(300))
			.webClientBuilder(WebClient.builder().exchangeFunction(request -> {
				exchanges.incrementAndGet();
				return Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(stalled)
					.build());
			}))
			.build();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.retryTemplate(retryTemplate(RetryPolicy.builder()))
			.build();

		StepVerifier.create(model.stream(prompt(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("Hi"))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterTransportException.class,
						transport -> assertThat(transport.getKind())
							.isEqualTo(OpenRouterTransportException.Kind.TIMEOUT))
				.hasCauseInstanceOf(TimeoutException.class))
			.verify(Duration.ofSeconds(30));
		assertThat(exchanges).hasValue(1);
	}

	@ParameterizedTest
	@EnumSource(value = OpenRouterRequestMode.class, names = { "OPENAI_CHAT_COMPLETIONS", "OPENAI_RESPONSES" })
	void streamConnectFailureIsNotRetried(OpenRouterRequestMode mode) {
		AtomicInteger exchanges = new AtomicInteger();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(request -> {
				exchanges.incrementAndGet();
				return Mono.error(new WebClientRequestException(new ConnectException("synthetic connection refused"),
						HttpMethod.POST, URI.create(BASE_URL), HttpHeaders.EMPTY));
			}))
			.build();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.retryTemplate(retryTemplate(RetryPolicy.builder()))
			.build();

		StepVerifier.create(model.stream(prompt(mode)))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
						assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.CONNECT);
						assertThat(transport).isInstanceOf(OpenRouterFailure.class);
					}).hasCauseInstanceOf(WebClientRequestException.class))
			.verify(Duration.ofSeconds(30));
		assertThat(exchanges).hasValue(1);
	}

	private static OpenRouterApi failingBlockingApi(AtomicInteger attempts, Supplier<IOException> failure) {
		RestClient.Builder rest = RestClient.builder()
			.requestFactory((URI uri, HttpMethod method) -> new MockClientHttpRequest(method, uri) {

				@Override
				protected ClientHttpResponse executeInternal() throws IOException {
					attempts.incrementAndGet();
					throw failure.get();
				}

			});
		return OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).restClientBuilder(rest).build();
	}

	private static RetryTemplate retryTemplate(RetryPolicy.Builder policy) {
		return new RetryTemplate(policy.maxRetries(2).delay(Duration.ZERO).build());
	}

	private static Prompt prompt(OpenRouterRequestMode mode) {
		return new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini").requestMode(mode).build());
	}

}
