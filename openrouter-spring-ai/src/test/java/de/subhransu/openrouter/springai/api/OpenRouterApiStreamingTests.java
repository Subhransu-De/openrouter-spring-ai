package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterApiStreamingTests {

	private OpenRouterApi apiRespondingWith(HttpStatus status, String contentType, String body) {
		ExchangeFunction exchange = request -> Mono
			.just(ClientResponse.create(status).header(HttpHeaders.CONTENT_TYPE, contentType).body(body).build());
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
	}

	private ChatCompletionRequest chatRequest() {
		return ChatCompletionRequest.builder()
			.model("openai/gpt-5.4-mini")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.stream(true)
			.build();
	}

	@Test
	void eofBeforeDoneIsTypedTruncation() {
		for (String body : List.of("", "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")) {
			OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, body);
			StepVerifier.create(api.chatCompletionStream(chatRequest()))
				.thenConsumeWhile(chunk -> true)
				.expectError(OpenRouterTruncatedResponseException.class)
				.verify();
		}
	}

	@Test
	void doneTerminatesWithoutWaitingForConnectionClose() {
		DefaultDataBufferFactory buffers = new DefaultDataBufferFactory();
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(Flux.<DataBuffer>just(buffers.wrap("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)))
				.concatWith(Flux.never()))
			.build());
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.build();
		StepVerifier.create(api.chatCompletionStream(chatRequest())).expectComplete().verify(Duration.ofSeconds(5));
	}

	@Test
	void parsesChunksAndFiltersDoneMarker() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}

				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}

				data: [DONE]

				""";
		OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hel"))
			.assertNext(chunk -> {
				assertThat(chunk.choices().get(0).delta().content()).isEqualTo("lo");
				assertThat(chunk.choices().get(0).finishReason()).isEqualTo("stop");
			})
			.verifyComplete();
	}

	@Test
	void toleratesUnknownFieldsInStreamChunks() {
		String sse = """
				data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-oss-120b","choices":[{"index":0,"delta":{"content":"Hi","reasoning_details":[{"type":"reasoning.text","text":"thinking","format":"unknown","index":0}]},"brand_new_field":true}]}

				data: [DONE]

				""";
		OpenRouterApi api = apiRespondingWith(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM_VALUE, sse);

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.verifyComplete();
	}

	@Test
	void failsStalledStreamWhenTimeoutIsConfigured() {
		// The body emits one chunk, then stalls forever. With a short timeout the gap
		// between
		// elements exceeds it, so the stream errors instead of hanging.
		DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
		Flux<DataBuffer> stalledBody = Flux.<DataBuffer>just(bufferFactory
			.wrap("""
					data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"}}]}

					"""
				.getBytes(StandardCharsets.UTF_8)))
			.concatWith(Flux.never());
		ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
			.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
			.body(stalledBody)
			.build());
		// A second-scale timeout leaves a wide margin for the immediate first chunk to
		// arrive under
		// CI load, while the infinite stall still trips it. The verifier returns as soon
		// as it fires.
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
			.timeout(Duration.ofSeconds(1))
			.build();

		StepVerifier.create(api.chatCompletionStream(chatRequest()))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.expectError(TimeoutException.class)
			.verify(Duration.ofSeconds(30));
	}

	@Test
	void keepAliveCommentsResetTheStreamTimeout() {
		// OpenRouter emits ": OPENROUTER PROCESSING" SSE comments as keep-alives during
		// slow generations. The parser filters them out, but the timeout sits on the SSE
		// event stream, so comments spaced under the timeout must NOT trip it before the
		// real chunk arrives. Virtual time makes the 100ms-interval-vs-250ms-timeout
		// relationship deterministic instead of racing real CI wall-clock.
		StepVerifier.withVirtualTime(() -> {
			DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();
			Flux<DataBuffer> body = Flux.interval(Duration.ofMillis(100))
				.take(15)
				.map(tick -> bufferFactory.wrap(": OPENROUTER PROCESSING\n\n".getBytes(StandardCharsets.UTF_8)))
				.concatWith(Flux.just(bufferFactory
					.wrap("""
							data: {"id":"gen-1","object":"chat.completion.chunk","model":"openai/gpt-5.4-mini","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}]}

							data: [DONE]

							"""
						.getBytes(StandardCharsets.UTF_8))))
				.cast(DataBuffer.class);
			ExchangeFunction exchange = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
				.body(body)
				.build());
			OpenRouterApi api = OpenRouterApi.builder()
				.apiKey("test-key")
				.webClientBuilder(WebClient.builder().exchangeFunction(exchange))
				.timeout(Duration.ofMillis(250))
				.build();
			return api.chatCompletionStream(chatRequest());
		})
			.thenAwait(Duration.ofSeconds(2))
			.assertNext(chunk -> assertThat(chunk.choices().get(0).delta().content()).isEqualTo("Hi"))
			.verifyComplete();
	}

	@Test
	void emitsApiExceptionOnErrorStatus() {
		OpenRouterApi api = apiRespondingWith(HttpStatus.UNAUTHORIZED, MediaType.APPLICATION_JSON_VALUE,
				"{\"error\":{\"message\":\"bad key\"}}");

		StepVerifier.create(api.chatCompletionStream(chatRequest())).expectErrorSatisfies(error -> {
			assertThat(error).isInstanceOf(OpenRouterNonTransientApiException.class);
			OpenRouterHttpException apiException = (OpenRouterHttpException) error;
			assertThat(apiException.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(apiException.getResponseBody()).contains("bad key");
		}).verify();
	}

}
