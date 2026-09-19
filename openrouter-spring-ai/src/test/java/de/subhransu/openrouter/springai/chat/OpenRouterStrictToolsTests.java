package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenRouterStrictToolsTests {

	private static final String SCHEMA = """
			{"type":"object","properties":{
			 "rows":{"type":"array","items":{"$ref":"#/$defs/row"}},
			 "note":{"type":["string","null"]}},
			 "required":["rows","note"],"additionalProperties":false,
			 "$defs":{"row":{"type":"object","properties":{"value":{"type":"integer"}},
			 "required":["value"],"additionalProperties":false}}}
			""";

	private static final String RESULT = """
			{"id":"synthetic","status":"completed","output":[],
			 "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
			""";

	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void invalidStrictSchemasFailBeforeHttp(OpenRouterRequestMode mode) {
		var api = mock(OpenRouterApi.class);
		var model = OpenRouterChatModel.builder().openRouterApi(api).build();
		ToolCallback tool = mock(ToolCallback.class);
		when(tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
			.name("synthetic")
			.description("Synthetic tool")
			.inputSchema("{\"type\":\"object\"}")
			.build());
		var prompt = new Prompt("Synthetic request",
				OpenRouterChatOptions.builder().requestMode(mode).toolStrict(true).toolCallbacks(tool).build());
		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("additionalProperties");
		StepVerifier.create(model.stream(prompt))
			.expectErrorMatches(error -> error instanceof IllegalArgumentException
					&& error.getMessage().contains("additionalProperties"))
			.verify(Duration.ofSeconds(5));
		org.mockito.Mockito.verifyNoInteractions(api);
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void serializesTriStateAndUnchangedSchemaOnBothTransports(OpenRouterRequestMode mode) {
		for (Boolean strict : new Boolean[] { null, false, true }) {
			for (boolean stream : new boolean[] { false, true }) {
				assertRequest(mode, strict, stream, false);
			}
		}
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void surfacesProviderRejectionWithoutRetryingAsNonStrict(OpenRouterRequestMode mode) {
		for (boolean stream : new boolean[] { false, true }) {
			assertRequest(mode, true, stream, true);
		}
	}

	private void assertRequest(OpenRouterRequestMode mode, Boolean strict, boolean stream, boolean reject) {
		boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
		String endpoint = "https://openrouter.test" + (responses ? "/responses" : "/chat/completions");
		AtomicReference<String> body = new AtomicReference<>();
		HttpStatus status = reject ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
		String responseBody = reject ? "{\"error\":{\"code\":400,\"message\":\"Strict tools unsupported\"}}" : RESULT;
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		if (!stream) {
			server.expect(requestTo(endpoint))
				.andExpect(request -> body.set(((MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(responseBody));
		}
		var web = WebClient.builder().clientConnector((method, uri, callback) -> {
			assertThat(uri.toString()).isEqualTo(endpoint);
			var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
			var incoming = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(status);
			incoming.getHeaders().setContentType(reject ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM);
			String event = responses
					? "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
					: "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
			incoming.setBody(reject ? responseBody : event);
			return callback.apply(outgoing)
				.then(reactor.core.publisher.Mono.defer(outgoing::getBodyAsString))
				.doOnNext(body::set)
				.thenReturn(incoming);
		});
		var api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(rest)
			.webClientBuilder(web)
			.build();
		ToolCallback tool = mock(ToolCallback.class);
		when(tool.getToolDefinition()).thenReturn(
				ToolDefinition.builder().name("synthetic").description("Synthetic tool").inputSchema(SCHEMA).build());
		var model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().model("synthetic/model").requestMode(mode).build())
			.build();
		var prompt = new Prompt("Synthetic request",
				OpenRouterChatOptions.builder()
					.model("synthetic/model")
					.requestMode(mode)
					.toolCallbacks(tool)
					.toolStrict(strict)
					.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", false, SCHEMA))
					.build());
		if (stream) {
			var verifier = StepVerifier.create(model.stream(prompt));
			if (reject) {
				verifier.expectError(OpenRouterNonTransientApiException.class).verify(Duration.ofSeconds(5));
			}
			else {
				verifier.thenConsumeWhile(value -> true).verifyComplete();
			}
		}
		else if (reject) {
			assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(OpenRouterNonTransientApiException.class);
		}
		else {
			model.call(prompt);
		}
		JsonNode json = this.mapper.readTree(body.get());
		JsonNode function = json.at(responses ? "/tools/0" : "/tools/0/function");
		assertThat(function.has("strict")).isEqualTo(strict != null);
		if (strict != null) {
			assertThat(function.path("strict").asBoolean()).isEqualTo(strict);
		}
		assertThat(function.path("parameters")).isEqualTo(this.mapper.readTree(SCHEMA));
		assertThat(json.at(responses ? "/text/format/strict" : "/response_format/json_schema/strict").asBoolean())
			.isFalse();
		assertThat(json.path("stream").asBoolean()).isEqualTo(stream);
		assertThat(tool.getToolDefinition().inputSchema()).isEqualTo(SCHEMA);
		verify(tool, never()).call(anyString());
		server.verify();
	}

}
