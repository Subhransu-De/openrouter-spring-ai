package de.subhransu.openrouter.springai.chat;

import org.assertj.core.api.Assertions;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.Mockito;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.http.HttpMethod;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import org.junit.jupiter.api.Tag;

import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.request")
class OpenRouterNativeStructuredOutputTests {

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void chatClientSendsNativeSchemaToTheSelectedEndpoint(OpenRouterRequestMode mode) {
		boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
		ObjectMapper mapper = new ObjectMapper();
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(rest)
			.build();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().model("test/model").requestMode(mode).build())
			.build();
		String body = responses
				? "{\"id\":\"r1\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"answer\\\":\\\"ok\\\"}\"}]}]}"
				: "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"answer\\\":\\\"ok\\\"}\"}}]}";
		server.expect(requestTo("https://openrouter.test" + (responses ? "/responses" : "/chat/completions")))
			.andExpect(request -> {
				var json = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
				var format = json.at(responses ? "/text/format" : "/response_format/json_schema");
				assertThat(format.path("schema"))
					.isEqualTo(mapper.readTree(new BeanOutputConverter<>(Answer.class).getJsonSchema()));
				assertThat(format.has("strict")).isFalse();
			})
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		Answer answer = ChatClient.builder(model)
			.build()
			.prompt()
			.user("Return an answer")
			.call()
			.entity(Answer.class, spec -> spec.useProviderStructuredOutput());
		assertThat(answer.answer()).isEqualTo("ok");
		server.verify();
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamingSendsTheSamePortableSchema(OpenRouterRequestMode mode) {
		boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
		ObjectMapper mapper = new ObjectMapper();
		String schema = new BeanOutputConverter<>(Answer.class).getJsonSchema();
		AtomicReference<String> body = new AtomicReference<>();
		var web = WebClient.builder().exchangeFunction(request -> {
			var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(HttpMethod.POST,
					request.url());
			String event = responses
					? "{\"type\":\"response.completed\",\"response\":{\"id\":\"r1\",\"status\":\"completed\",\"output\":[]}}"
					: "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}";
			return request.writeTo(outgoing, ExchangeStrategies.withDefaults())
				.then(reactor.core.publisher.Mono.defer(outgoing::getBodyAsString))
				.doOnNext(body::set)
				.map(ignored -> ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", MediaType.TEXT_EVENT_STREAM_VALUE)
					.body("data: " + event + "\n\ndata: [DONE]\n\n")
					.build());
		});
		OpenRouterApi api = OpenRouterApi.builder().apiKey("test-key").webClientBuilder(web).build();
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		ChatClient.builder(model)
			.build()
			.prompt()
			.user("Return an answer")
			.options(OpenRouterChatOptions.builder().model("test/model").requestMode(mode).outputSchema(schema))
			.stream()
			.chatResponse()
			.blockLast(Duration.ofSeconds(5));
		var json = mapper.readTree(body.get());
		assertThat(json.at(responses ? "/text/format/schema" : "/response_format/json_schema/schema"))
			.isEqualTo(mapper.readTree(schema));
		assertThat(json.path("stream").asBoolean()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void unsupportedOptionsFailBeforeCallingTheApi(boolean stream) {
		OpenRouterApi api = Mockito.mock(OpenRouterApi.class);
		var model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(
					OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES).seed(0).build())
			.build();
		var prompt = new Prompt("hi");
		Assertions.assertThatThrownBy(() -> {
			if (stream) {
				model.stream(prompt).blockLast(Duration.ofSeconds(5));
			}
			else {
				model.call(prompt);
			}
		}).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seed");
		Mockito.verifyNoInteractions(api);
	}

	record Answer(String answer) {
	}

}
