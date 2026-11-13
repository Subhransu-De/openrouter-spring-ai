package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException.Kind;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.response")
class OpenRouterProtocolKindTests {

	private static final String CHAT_COMPLETIONS = "/chat/completions";

	private static final String BASE_URL = "https://openrouter.test/api/v1";

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void blockingBodyFailuresReportTheirKindAndEndpoint(boolean suppliedMapper) {
		assertBlocking(suppliedMapper, "", CHAT_COMPLETIONS, Kind.EMPTY_BODY);
		assertBlocking(suppliedMapper, "null", CHAT_COMPLETIONS, Kind.EMPTY_BODY);
		assertBlocking(suppliedMapper, "{\"id\":", CHAT_COMPLETIONS, Kind.DECODE);
	}

	@Test
	void chatCompletionChoiceWithoutMessageIsAMissingField() {
		assertModelCall(CHAT_COMPLETIONS, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
				"{\"id\":\"gen-1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"}]}",
				Kind.MISSING_FIELD);
	}

	@Test
	void responsesResultWithoutStatusIsAMissingField() {
		assertModelCall("/responses", OpenRouterRequestMode.OPENAI_RESPONSES,
				"{\"id\":\"resp-1\",\"object\":\"response\",\"model\":\"m\",\"output\":[]}", Kind.MISSING_FIELD);
	}

	@Test
	void embeddingCountMismatchIsAContractFailure() {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo(BASE_URL + "/embeddings"))
			.andRespond(withSuccess("{\"object\":\"list\",\"model\":\"m\",\"data\":[]}", MediaType.APPLICATION_JSON));
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).restClientBuilder(rest).build())
			.build();

		assertThatThrownBy(() -> model
			.call(new EmbeddingRequest(List.of("hello"), OpenRouterEmbeddingOptions.builder().model("m").build())))
			.satisfies(protocol("/embeddings", Kind.CONTRACT));
	}

	@Test
	void streamPayloadThatIsNotAnObjectIsADecodeFailure() {
		StepVerifier.create(streamingApi("data: [1,2]\n\n").chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(protocol(CHAT_COMPLETIONS, Kind.DECODE))
			.verify(Duration.ofSeconds(10));
	}

	@Test
	void streamChunkWithoutChoicesOrUsageIsAMissingField() {
		StepVerifier.create(streamingApi("data: {\"id\":\"gen-1\"}\n\n").chatCompletionStream(chatRequest()))
			.expectErrorSatisfies(protocol(CHAT_COMPLETIONS, Kind.MISSING_FIELD))
			.verify(Duration.ofSeconds(10));
	}

	@Test
	void legacyResponsesTerminalEventIsAContractFailure() {
		StepVerifier
			.create(streamingApi("data: {\"type\":\"response.done\"}\n\n")
				.responsesStream(ResponsesRequest.builder().model("m").input("hello").build()))
			.expectErrorSatisfies(protocol("/responses", Kind.CONTRACT))
			.verify(Duration.ofSeconds(10));
	}

	private static void assertBlocking(boolean suppliedMapper, String body, String endpoint, Kind kind) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo(BASE_URL + endpoint)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		OpenRouterApi.Builder builder = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl(BASE_URL)
			.restClientBuilder(rest);
		OpenRouterApi api = (suppliedMapper ? builder.objectMapper(new ObjectMapper()) : builder).build();

		assertThatThrownBy(() -> api.chatCompletion(chatRequest())).satisfies(protocol(endpoint, kind));
	}

	private static void assertModelCall(String endpoint, OpenRouterRequestMode mode, String body, Kind kind) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(requestTo(BASE_URL + endpoint)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(OpenRouterApi.builder().apiKey("test-key").baseUrl(BASE_URL).restClientBuilder(rest).build())
			.build();

		assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model("m").requestMode(mode).build())))
			.satisfies(protocol(endpoint, kind));
	}

	private static Consumer<Throwable> protocol(String endpoint, Kind kind) {
		return error -> assertThat(error).isInstanceOfSatisfying(OpenRouterProtocolException.class, protocol -> {
			assertThat(protocol.getKind()).isEqualTo(kind);
			assertThat(protocol.getEndpoint()).isEqualTo(endpoint);
		});
	}

	private static OpenRouterApi streamingApi(String body) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
					.body(Flux.just(new DefaultDataBufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8))))
					.build())))
			.build();
	}

	private static ChatCompletionRequest chatRequest() {
		return ChatCompletionRequest.builder()
			.model("m")
			.messages(List.of(new ChatMessage("user", "hello", null, null, null)))
			.build();
	}

}
