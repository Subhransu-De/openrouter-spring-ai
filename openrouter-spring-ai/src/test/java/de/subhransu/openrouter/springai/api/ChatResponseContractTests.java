package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.springframework.ai.chat.prompt.Prompt;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatResponseMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingResponseMapper;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ChatResponseContractTests {

	private final ChatCompletionRequest request = new ObjectMapper().readValue("{}", ChatCompletionRequest.class);

	@ParameterizedTest
	@ValueSource(strings = { "", ",\"message\":null" })
	void completedChoicesRequireMessageObjects(String message) {
		String body = "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"" + message + "}]}";
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(blocking(body, HttpStatus.OK))
			.defaultOptions(OpenRouterChatOptions.builder().model("test-model").build())
			.observationRegistry(registry)
			.build();
		assertThatThrownBy(() -> model.call(new Prompt("synthetic prompt")))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("requires a message");
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"content\":\"\"", "\"refusal\":\"synthetic refusal\"",
			"\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"test\",\"arguments\":\"{}\"}}]",
			"\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aW1hZ2U=\"}}]" })
	void emptyTextMessagesRemainValid(String content) {
		String finish = content.contains("tool_calls") ? "tool_calls" : "stop";
		String body = "{\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"" + finish
				+ "\",\"message\":{\"role\":\"assistant\"," + content + "}}]}";
		assertThat(new OpenRouterChatResponseMapper().map(blocking(body, HttpStatus.OK).chatCompletion(this.request))
			.getResults()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "503", "\"503\"", "\"provider_unavailable\"", "null", "\"rate_limit_exceeded\"" })
	void topLevelErrorsTakePrecedenceOverChoices(String code) {
		String body = "{\"error\":{\"code\":" + code + ",\"message\":\"synthetic failure\"},"
				+ "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ignored\"}}]}";
		assertThatThrownBy(() -> new OpenRouterChatResponseMapper()
			.map(blocking(body, HttpStatus.OK).chatCompletion(this.request)))
			.isInstanceOfSatisfying(OpenRouterApiException.class, ex -> {
				assertThat(ex.getErrorDetails().message()).isEqualTo("synthetic failure");
				assertThat(ex.getStatusCode().value())
					.isEqualTo(code.contains("503") ? 503 : code.contains("rate_limit") ? 429 : 500);
			});
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper().map(streaming(body).chatCompletionStream(this.request)))
			.expectError(OpenRouterApiException.class)
			.verify();
	}

	@Test
	void errorEnvelopeWithoutChoicesFails() {
		String body = "{\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}";
		assertThatThrownBy(() -> new OpenRouterChatResponseMapper()
			.map(blocking(body, HttpStatus.OK).chatCompletion(this.request)))
			.isInstanceOf(OpenRouterApiException.class);
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper().map(streaming(body).chatCompletionStream(this.request)))
			.expectError(OpenRouterApiException.class)
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"choices\":null}", "{\"choices\":[]}", "{\"choices\":[null]}" })
	void rejectsMissingOrInvalidChoices(String body) {
		assertThatThrownBy(() -> new OpenRouterChatResponseMapper()
			.map(blocking(body, HttpStatus.OK).chatCompletion(this.request)))
			.isInstanceOf(OpenRouterProtocolException.class);
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper().map(streaming(body).chatCompletionStream(this.request)))
			.expectError(OpenRouterProtocolException.class)
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "null" })
	void rejectsEmptyAndNullBlockingBodies(String body) {
		assertThatThrownBy(() -> blocking(body, HttpStatus.OK).chatCompletion(this.request))
			.isInstanceOf(OpenRouterProtocolException.class);
		assertThatThrownBy(() -> blocking(body, HttpStatus.NO_CONTENT).chatCompletion(this.request))
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "\"choices\":[],", "\"choices\":null," })
	void usageOnlyIsValidOnlyInStreams(String choices) {
		String body = "{" + choices + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
		assertThatThrownBy(() -> new OpenRouterChatResponseMapper()
			.map(blocking(body, HttpStatus.OK).chatCompletion(this.request)))
			.isInstanceOf(OpenRouterProtocolException.class);
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper().map(streaming(body).chatCompletionStream(this.request)))
			.assertNext(response -> {
				assertThat(response.getResults()).isEmpty();
				assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(3);
			})
			.verifyComplete();
	}

	private OpenRouterApi blocking(String body, HttpStatus status) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		server.expect(requestTo("https://openrouter.test/chat/completions"))
			.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(builder)
			.build();
	}

	private OpenRouterApi streaming(String body) {
		return OpenRouterApi.builder()
			.apiKey("test-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
					.body("data: " + body + "\n\ndata: [DONE]\n\n")
					.build())))
			.build();
	}

}
