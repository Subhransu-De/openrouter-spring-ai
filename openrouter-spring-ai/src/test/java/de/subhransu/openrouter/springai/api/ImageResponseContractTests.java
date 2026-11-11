package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.image.ImageResponse;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.nio.charset.StandardCharsets;

class ImageResponseContractTests {

	private static final String USAGE_KEY = "openrouter.usage";

	private static final ImagePrompt PROMPT = new ImagePrompt("synthetic image");

	private final TestObservationRegistry registry = TestObservationRegistry.create();

	private static final String NO_DATA = "OpenRouter image response requires data";

	private static final String NO_CONTENT = "OpenRouter image data requires image content";

	// The model and API never hand the validator a null response, so only a direct call
	// reaches this guard; it is the last line of defence for a caller-supplied response.
	@Test
	void validatorRejectsNullWithAProtocolError() {
		assertThatThrownBy(
				() -> OpenRouterImageResponseValidator.validate(null, OpenRouterDiagnosticSanitizer.keyless()))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("Null OpenRouter image response");
	}

	static Stream<Arguments> invalidJsonBodies() {
		return Stream.of(
				argumentSet("empty body", "", "Empty OpenRouter /images response",
						"Invalid OpenRouter image response JSON"),
				argumentSet("JSON null", "null", "Null OpenRouter /images response", "Empty OpenRouter image response"),
				argumentSet("no data field", "{}", NO_DATA, NO_DATA),
				argumentSet("null data", "{\"data\":null}", NO_DATA, NO_DATA),
				argumentSet("null entry", "{\"data\":[null]}", NO_CONTENT, NO_CONTENT),
				argumentSet("entry without content", "{\"data\":[{}]}", NO_CONTENT, NO_CONTENT),
				argumentSet("blank content", "{\"data\":[{\"b64_json\":\" \",\"url\":\"\"}]}", NO_CONTENT, NO_CONTENT),
				argumentSet("valid entry followed by an empty one", "{\"data\":[{\"b64_json\":\"aW1hZ2U=\"},{}]}",
						NO_CONTENT, NO_CONTENT));
	}

	@ParameterizedTest
	@MethodSource("invalidJsonBodies")
	void invalidJsonFailsSyncAndFallbackAndStopsObservations(String body, String syncMessage, String streamMessage) {
		OpenRouterImageModel model = model(body, MediaType.APPLICATION_JSON);
		assertThatThrownBy(() -> model.call(PROMPT)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(syncMessage);
		assertStoppedWithError(OpenRouterProtocolException.class);
		this.registry.clear();
		StepVerifier.create(model.stream(PROMPT))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage(streamMessage))
			.verify();
		assertStoppedWithError(OpenRouterProtocolException.class);
	}

	static Stream<Arguments> errorsBesideData() {
		return Stream.of(argumentSet("error only", ""),
				argumentSet("error next to an empty data array", ",\"data\":[]"),
				argumentSet("error next to a valid image", ",\"data\":[{\"b64_json\":\"aW1hZ2U=\"}]"));
	}

	@ParameterizedTest
	@MethodSource("errorsBesideData")
	void jsonErrorsTakePrecedenceAndPreserveDiagnostics(String data) {
		String body = "{\"error\":{\"code\":\"429\",\"message\":\"synthetic rate limit\","
				+ "\"error_type\":\"rate_limit_error\",\"metadata\":{\"provider_code\":\"synthetic_limit\"}}" + data
				+ "}";
		OpenRouterImageModel model = model(body, MediaType.APPLICATION_JSON);
		assertThatThrownBy(() -> model.call(PROMPT)).satisfies(this::assertTransientProviderError);
		assertStoppedWithError(OpenRouterTransientApiException.class);
		this.registry.clear();
		StepVerifier.create(model.stream(PROMPT)).expectErrorSatisfies(this::assertTransientProviderError).verify();
		assertStoppedWithError(OpenRouterTransientApiException.class);
	}

	static Stream<Arguments> completedEventContents() {
		return Stream.of(argumentSet("no b64_json field", ""), argumentSet("null b64_json", ",\"b64_json\":null"),
				argumentSet("blank b64_json", ",\"b64_json\":\" \""));
	}

	@ParameterizedTest
	@MethodSource("completedEventContents")
	void completedEventsRequireContent(String content) {
		OpenRouterImageModel model = model(sse("{\"type\":\"image_generation.completed\"" + content + "}"),
				MediaType.TEXT_EVENT_STREAM);
		StepVerifier.create(model.stream(PROMPT)).expectError(OpenRouterProtocolException.class).verify();
		assertStoppedWithError(OpenRouterProtocolException.class);
	}

	static Stream<Arguments> errorEventTypes() {
		return Stream.of(argumentSet("error event without a type", ""),
				argumentSet("error event", "\"type\":\"error\","),
				argumentSet("completed event carrying an error", "\"type\":\"image_generation.completed\","));
	}

	@ParameterizedTest
	@MethodSource("errorEventTypes")
	void sseErrorsTakePrecedenceOverMissingContent(String type) {
		String event = "{" + type + "\"error\":{\"code\":429,\"message\":\"synthetic rate limit\","
				+ "\"error_type\":\"rate_limit_error\",\"metadata\":{\"provider_code\":\"synthetic_limit\"}}}";
		StepVerifier.create(model(sse(event), MediaType.TEXT_EVENT_STREAM).stream(PROMPT))
			.expectErrorSatisfies(this::assertProviderError)
			.verify();
		assertStoppedWithError(OpenRouterApiException.class);
	}

	@Test
	void emptyImageArraysRemainValidForBlockingCallsButDoNotCompleteAStream() {
		OpenRouterImageModel model = model("{\"data\":[]}", MediaType.APPLICATION_JSON);
		assertThat(model.call(PROMPT).getResults()).isEmpty();
		this.registry.clear();
		StepVerifier.create(model.stream(PROMPT)).expectError(OpenRouterTruncatedResponseException.class).verify();
		assertStoppedWithError(OpenRouterTruncatedResponseException.class);
	}

	@Test
	void jsonFallbackPreservesBytesUrlsOrderAndUsage() {
		OpenRouterImageModel model = model("""
				{"data":[{"b64_json":"aW1hZ2U="},{"url":"https://example.test/image.png"}],
				 "usage":{"total_tokens":3},"unknown_field":true}
				""", MediaType.APPLICATION_JSON);
		ImageResponse response = model.call(PROMPT);
		assertThat(response.getResults()).hasSize(2);
		assertThat(response.getMetadata().<Usage>get(USAGE_KEY).getTotalTokens()).isEqualTo(3);
		assertThat(response.getResults().get(0).getOutput().getB64Json()).isEqualTo("aW1hZ2U=");
		assertThat(response.getResults().get(1).getOutput().getUrl()).isEqualTo("https://example.test/image.png");
		StepVerifier.create(model.stream(PROMPT)).assertNext(first -> {
			assertThat(first.getResult().getOutput().getB64Json()).isEqualTo("aW1hZ2U=");
			assertThat(first.getMetadata().containsKey(USAGE_KEY)).isFalse();
		}).assertNext(last -> {
			assertThat(last.getResult().getOutput().getUrl()).isEqualTo("https://example.test/image.png");
			assertThat(last.getMetadata().<Usage>get(USAGE_KEY).getTotalTokens()).isEqualTo(3);
			assertThat(last.getMetadata().<Object>get(USAGE_KEY)).isEqualTo(response.getMetadata().get(USAGE_KEY));
		}).verifyComplete();
	}

	@Test
	void cancellationReleasesBodyAndStopsObservation() {
		AtomicBoolean cancelled = new AtomicBoolean();
		byte[] partial = "data: {\"type\":\"image_generation.partial_image\",\"b64_json\":\"cGFydA==\"}\n\n"
			.getBytes(StandardCharsets.UTF_8);
		WebClient.Builder builder = WebClient.builder()
			.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
				.body(Flux
					.<DataBuffer>concat(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(partial)), Flux.never())
					.doOnCancel(() -> cancelled.set(true)))
				.build()));
		OpenRouterApi api = OpenRouterApi.builder().apiKey("test-key").webClientBuilder(builder).build();
		StepVerifier.create(model(api).stream(PROMPT)).expectNextCount(1).thenCancel().verify();
		assertThat(cancelled).isTrue();
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(this.registry)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.doesNotHaveError();
	}

	private void assertProviderError(Throwable throwable) {
		assertThat(throwable).isInstanceOfSatisfying(OpenRouterApiException.class, error -> {
			assertThat(error.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
			assertThat(error.getStatusCode().value()).isEqualTo(429);
			assertProviderDetails(error.getErrorDetails());
		});
	}

	private void assertTransientProviderError(Throwable throwable) {
		assertThat(throwable).isInstanceOfSatisfying(OpenRouterTransientApiException.class, error -> {
			assertThat(error.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
			assertThat(error.getStatusCode().value()).isEqualTo(429);
			assertThat(error.getEndpoint()).isEqualTo("/images");
			assertProviderDetails(error.getErrorDetails());
		});
	}

	private void assertProviderDetails(OpenRouterErrorDetails details) {
		assertThat(details.message()).isEqualTo("synthetic rate limit");
		assertThat(details.errorType()).isEqualTo("rate_limit_error");
		assertThat(details.providerCode()).isEqualTo("synthetic_limit");
	}

	private void assertStoppedWithError(Class<? extends Throwable> type) {
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(this.registry)
			.hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(type);
	}

	private String sse(String event) {
		return "data: " + event + "\n\ndata: [DONE]\n\n";
	}

	private OpenRouterImageModel model(String body, MediaType contentType) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo("https://openrouter.test/images")).andRespond(withSuccess(body, contentType));
		WebClient.Builder web = WebClient.builder()
			.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
				.header("Content-Type", contentType.toString())
				.body(body)
				.build()));
		return model(OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(rest)
			.webClientBuilder(web)
			.build());
	}

	private OpenRouterImageModel model(OpenRouterApi api) {
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.defaultOptions(OpenRouterImageOptions.builder().model("test-image-model").build())
			.observationRegistry(this.registry)
			.build();
	}

}
