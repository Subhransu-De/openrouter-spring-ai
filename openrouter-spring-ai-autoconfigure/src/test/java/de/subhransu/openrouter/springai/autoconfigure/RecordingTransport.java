package de.subhransu.openrouter.springai.autoconfigure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.assertj.ApplicationContextAssertProvider;
import org.springframework.boot.test.context.runner.AbstractApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Replaces both HTTP transports of the Boot-built {@code OpenRouterApi} with recorders
 * that answer from canned responses, so that a test asserts what reached the wire instead
 * of reading the API's private state. No socket is opened.
 */
final class RecordingTransport {

	static final String MINIMAL_CHAT_COMPLETION = """
			{"id":"synthetic","object":"chat.completion","model":"m","choices":[]}""";

	private final List<Exchange> exchanges = new CopyOnWriteArrayList<>();

	private volatile Response blocking = new Response(HttpStatus.OK, MediaType.APPLICATION_JSON,
			MINIMAL_CHAT_COMPLETION);

	private volatile Response streaming = new Response(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM, "data: [DONE]\n\n");

	private volatile boolean stalled;

	RecordingTransport blockingResponse(HttpStatus status, String body) {
		this.blocking = new Response(status, MediaType.APPLICATION_JSON, body);
		return this;
	}

	/**
	 * Answer streaming calls with a 200 whose body never emits an event.
	 * @return this transport
	 */
	RecordingTransport stallingStream() {
		this.stalled = true;
		return this;
	}

	RecordingTransport streamingResponse(HttpStatus status, MediaType contentType, String body) {
		this.streaming = new Response(status, contentType, body);
		return this;
	}

	/**
	 * Install both recorders on a runner as the Boot builders that the API clones.
	 * @param runner the runner to extend
	 * @return the extended runner
	 */
	<R extends AbstractApplicationContextRunner<R, C, A>, C extends ConfigurableApplicationContext, A extends ApplicationContextAssertProvider<C>> R installOn(
			R runner) {
		return runner.withBean("recordingRequestFactoryBuilder", ClientHttpRequestFactoryBuilder.class,
				() -> settings -> (uri, method) -> new MockClientHttpRequest(method, uri) {
					@Override
					protected ClientHttpResponse executeInternal() {
						RecordingTransport.this.exchanges
							.add(new Exchange(getURI(), new HttpHeaders(getHeaders()), getBodyAsString(), false));
						Response response = RecordingTransport.this.blocking;
						MockClientHttpResponse mock = new MockClientHttpResponse(
								response.body().getBytes(StandardCharsets.UTF_8), response.status());
						mock.getHeaders().setContentType(response.contentType());
						return mock;
					}
				})
			.withBean("recordingWebClientBuilder", WebClient.Builder.class,
					() -> WebClient.builder().clientConnector((method, uri, callback) -> {
						var request = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method,
								uri);
						Response canned = this.streaming;
						var response = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(
								canned.status());
						response.getHeaders().setContentType(canned.contentType());
						if (this.stalled) {
							response.setBody(Flux.<DataBuffer>never());
						}
						else {
							response.setBody(canned.body());
						}
						return callback.apply(request)
							.then(Mono.defer(request::getBodyAsString))
							.doOnNext(body -> this.exchanges
								.add(new Exchange(request.getURI(), new HttpHeaders(request.getHeaders()), body, true)))
							.thenReturn(response);
					}));
	}

	/**
	 * A request factory for a client that is not the OpenRouter one: it stores the
	 * headers of the last request and answers an empty JSON data list.
	 * @param headers receives the headers of each request
	 * @return the factory
	 */
	static ClientHttpRequestFactory headerCapturingFactory(AtomicReference<HttpHeaders> headers) {
		return (uri, method) -> new MockClientHttpRequest(method, uri) {
			@Override
			protected ClientHttpResponse executeInternal() {
				headers.set(new HttpHeaders(getHeaders()));
				MockClientHttpResponse response = new MockClientHttpResponse(
						"{\"data\":[]}".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
				return response;
			}
		};
	}

	List<Exchange> exchanges() {
		return List.copyOf(this.exchanges);
	}

	Exchange onlyExchange() {
		List<Exchange> recorded = exchanges();
		if (recorded.size() != 1) {
			throw new AssertionError("Expected exactly one recorded exchange but found " + recorded.size());
		}
		return recorded.get(0);
	}

	record Exchange(URI uri, HttpHeaders headers, String body, boolean streaming) {

	}

	private record Response(HttpStatus status, MediaType contentType, String body) {

	}

}
