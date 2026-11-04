package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.mock.http.client.reactive.MockClientHttpRequest;
import org.springframework.mock.http.client.reactive.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Mono;

class OpenRouterApiBuilderIsolationTests {

	private static final String APP_REFERER = "https://app.test";

	private static final String CALLER_HEADER = "X-Caller";

	private static final String INTERCEPTOR_HEADER = "X-Caller-Interceptor";

	private static final List<String> PROVIDER_HEADERS = List.of(HttpHeaders.AUTHORIZATION, "HTTP-Referer",
			"X-OpenRouter-Title", "X-OpenRouter-Categories");

	private static final List<String> ATTRIBUTION_HEADERS = PROVIDER_HEADERS.subList(1, 4);

	@Test
	void attributionOverridesDefaultsAndRequestHeadersAfterUserInterceptors() {
		RestClient.Builder supplied = RestClient.builder()
			.defaultHeaders(headers -> ATTRIBUTION_HEADERS.forEach(name -> headers.set(name, "default")))
			.defaultRequest(request -> request
				.headers(headers -> ATTRIBUTION_HEADERS.forEach(name -> headers.set(name, "request"))))
			.requestInterceptor((request, body, execution) -> {
				ATTRIBUTION_HEADERS.forEach(name -> {
					assertThat(request.getHeaders().getFirst(name)).isEqualTo("request");
					request.getHeaders().set(name, "interceptor");
				});
				ClientHttpResponse response = execution.execute(request, body);
				assertThat(request.getHeaders().getFirst(ATTRIBUTION_HEADERS.get(0))).isEqualTo(APP_REFERER);
				return response;
			});
		MockRestServiceServer server = MockRestServiceServer.bindTo(supplied).build();
		OpenRouterApi api = api(supplied, null);
		server.expect(once(), requestTo("https://openrouter.test/api/v1/embeddings")).andExpect(request -> {
			assertThat(request.getHeaders().get(ATTRIBUTION_HEADERS.get(0))).containsExactly(APP_REFERER);
			assertThat(request.getHeaders().get(ATTRIBUTION_HEADERS.get(1))).containsExactly("Test App");
			assertThat(request.getHeaders().get(ATTRIBUTION_HEADERS.get(2))).containsExactly("test");
		}).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
		api.embeddings(new EmbeddingsRequest("model", List.of("input"), null, null, null, null));
		server.verify();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " \t")
	void blankAttributionPreservesUserInterceptorHeaders(String value) {
		AtomicInteger calls = new AtomicInteger();
		RestClient.Builder supplied = RestClient.builder()
			.defaultHeaders(headers -> ATTRIBUTION_HEADERS.forEach(name -> headers.set(name, "default")))
			.requestInterceptor((request, body, execution) -> {
				calls.incrementAndGet();
				ATTRIBUTION_HEADERS.forEach(name -> request.getHeaders().set(name, "caller"));
				return execution.execute(request, body);
			});
		MockRestServiceServer server = MockRestServiceServer.bindTo(supplied).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.restClientBuilder(supplied)
			.httpReferer(value)
			.applicationTitle(value)
			.applicationCategories(value)
			.build();
		server.expect(once(), requestTo(OpenRouterApi.DEFAULT_BASE_URL + "/embeddings"))
			.andExpect(request -> ATTRIBUTION_HEADERS
				.forEach(name -> assertThat(request.getHeaders().get(name)).containsExactly("caller")))
			.andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
		api.embeddings(new EmbeddingsRequest("model", List.of("input"), null, null, null, null));
		assertThat(calls).hasValue(1);
		server.verify();
	}

	@Test
	void isolatesAndPreservesSuppliedRestClientBuilder() {
		RestClient.Builder supplied = RestClient.builder()
			.baseUrl("https://unrelated.invalid")
			.defaultHeader(CALLER_HEADER, "rest")
			.requestInterceptor((request, body, execution) -> {
				request.getHeaders().set(INTERCEPTOR_HEADER, "rest");
				return execution.execute(request, body);
			});
		MockRestServiceServer server = MockRestServiceServer.bindTo(supplied).build();
		RestClient builtBefore = supplied.build();
		OpenRouterApi api = api(supplied, null);
		RestClient builtAfter = supplied.build();

		expectUnrelatedRestRequest(server);
		server.expect(once(), requestTo("https://openrouter.test/api/v1/embeddings")).andExpect(request -> {
			assertThat(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
			assertThat(request.getHeaders().getFirst("HTTP-Referer")).isEqualTo(APP_REFERER);
			assertThat(request.getHeaders().getFirst(CALLER_HEADER)).isEqualTo("rest");
			assertThat(request.getHeaders().getFirst(INTERCEPTOR_HEADER)).isEqualTo("rest");
		}).andRespond(withSuccess("{\"data\":[]}", MediaType.APPLICATION_JSON));
		expectUnrelatedRestRequest(server);

		builtBefore.get().uri("/resource").retrieve().toBodilessEntity();
		api.embeddings(new EmbeddingsRequest("model", List.of("input"), null, null, null, null));
		builtAfter.get().uri("/resource").retrieve().toBodilessEntity();
		server.verify();
	}

	@Test
	void isolatesAndPreservesSuppliedWebClientBuilder() {
		List<MockClientHttpRequest> requests = new ArrayList<>();
		AtomicInteger codecCustomizations = new AtomicInteger();
		ClientHttpConnector connector = (method, uri, callback) -> {
			MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
			requests.add(request);
			MockClientHttpResponse response = new MockClientHttpResponse(HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
			response.setBody("data: [DONE]\n\n");
			return callback.apply(request).then(Mono.just(response));
		};
		WebClient.Builder supplied = WebClient.builder()
			.baseUrl("https://unrelated.invalid")
			.defaultHeader(CALLER_HEADER, "web")
			.codecs(codecs -> codecCustomizations.incrementAndGet())
			.filter((request, next) -> next
				.exchange(ClientRequest.from(request).header(INTERCEPTOR_HEADER, "web").build()))
			.clientConnector(connector);
		WebClient builtBefore = supplied.build();
		OpenRouterApi api = api(null, supplied);
		WebClient builtAfter = supplied.build();

		builtBefore.get().uri("/resource").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));
		api.imagesStream(new ImagesRequest("model", "prompt", null, null, null, null, null, null, null, null, null,
				true, null, null))
			.blockLast(Duration.ofSeconds(5));
		builtAfter.get().uri("/resource").retrieve().toBodilessEntity().block(Duration.ofSeconds(5));

		assertThat(requests).hasSize(3);
		assertUnrelatedWebRequest(requests.get(0));
		assertUnrelatedWebRequest(requests.get(2));
		assertThat(requests.get(1).getURI().toString()).isEqualTo("https://openrouter.test/api/v1/images");
		assertThat(requests.get(1).getHeaders().getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-key");
		assertThat(requests.get(1).getHeaders().getFirst("HTTP-Referer")).isEqualTo(APP_REFERER);
		assertThat(requests.get(1).getHeaders().getFirst(CALLER_HEADER)).isEqualTo("web");
		assertThat(requests.get(1).getHeaders().getFirst(INTERCEPTOR_HEADER)).isEqualTo("web");
		assertThat(codecCustomizations).hasValue(3);
	}

	private OpenRouterApi api(RestClient.Builder restClientBuilder, WebClient.Builder webClientBuilder) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test/api/v1")
			.httpReferer(APP_REFERER)
			.applicationTitle("Test App")
			.applicationCategories("test")
			.restClientBuilder(restClientBuilder)
			.webClientBuilder(webClientBuilder)
			.build();
	}

	private void expectUnrelatedRestRequest(MockRestServiceServer server) {
		server.expect(once(), requestTo("https://unrelated.invalid/resource")).andExpect(request -> {
			assertThat(request.getHeaders().headerNames()).doesNotContainAnyElementsOf(PROVIDER_HEADERS);
			assertThat(request.getHeaders().getFirst(CALLER_HEADER)).isEqualTo("rest");
			assertThat(request.getHeaders().getFirst(INTERCEPTOR_HEADER)).isEqualTo("rest");
		}).andRespond(withSuccess());
	}

	private void assertUnrelatedWebRequest(MockClientHttpRequest request) {
		assertThat(request.getURI().toString()).isEqualTo("https://unrelated.invalid/resource");
		assertThat(request.getHeaders().headerNames()).doesNotContainAnyElementsOf(PROVIDER_HEADERS);
		assertThat(request.getHeaders().getFirst(CALLER_HEADER)).isEqualTo("web");
		assertThat(request.getHeaders().getFirst(INTERCEPTOR_HEADER)).isEqualTo("web");
	}

}
