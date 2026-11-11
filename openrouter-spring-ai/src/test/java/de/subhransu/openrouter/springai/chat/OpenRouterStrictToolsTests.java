package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import tools.jackson.databind.node.BooleanNode;
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
			.verify();
		verifyNoInteractions(api);
	}

	static Stream<Arguments> modesAndTransports() {
		return Stream.of(OpenRouterRequestMode.values())
			.flatMap(mode -> Stream.of(false, true)
				.map(stream -> Arguments.of(Named.of(mode.toString(), mode),
						Named.of(stream ? "streaming" : "blocking", stream))));
	}

	static Stream<Arguments> modesStrictAndTransports() {
		return Stream.of(false, true)
			.flatMap(strict -> modesAndTransports()
				.map(row -> Arguments.of(row.get()[0], Named.of("strict=" + strict, strict), row.get()[1])));
	}

	@ParameterizedTest(name = "{0}, {1}, {2}")
	@MethodSource("modesStrictAndTransports")
	void explicitStrictValuesReachTheWireWithTheSchemaUnchanged(OpenRouterRequestMode mode, boolean strict,
			boolean stream) {
		Exchange exchange = new Exchange(mode, stream, false);

		exchange.succeed(exchange.prompt(strict));

		assertThat(exchange.toolFunction().get("strict")).isEqualTo(BooleanNode.valueOf(strict));
		exchange.assertSchemaAndOtherFieldsUntouched();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("modesAndTransports")
	void unsetStrictIsOmittedFromTheWireWithTheSchemaUnchanged(OpenRouterRequestMode mode, boolean stream) {
		Exchange exchange = new Exchange(mode, stream, false);

		exchange.succeed(exchange.prompt(null));

		assertThat(exchange.toolFunction().has("strict")).isFalse();
		exchange.assertSchemaAndOtherFieldsUntouched();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("modesAndTransports")
	void surfacesProviderRejectionWithoutRetryingAsNonStrict(OpenRouterRequestMode mode, boolean stream) {
		Exchange exchange = new Exchange(mode, stream, true);

		exchange.failWithRejection(exchange.prompt(true));

		// The single rejected request was sent strict; the mock server accepts no second
		// (non-strict) attempt.
		assertThat(exchange.toolFunction().get("strict")).isEqualTo(BooleanNode.TRUE);
		exchange.assertSchemaAndOtherFieldsUntouched();
	}

	private static void assertStrictToolsRejection(Throwable error) {
		assertThat(error).isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, rejection -> {
			assertThat(rejection.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
			assertThat(rejection.getResponseBody()).contains("Strict tools unsupported");
		});
	}

	/** One request through a model whose transport records the outgoing body. */
	private final class Exchange {

		private final boolean responses;

		private final boolean stream;

		private final OpenRouterRequestMode mode;

		private final AtomicReference<String> body = new AtomicReference<>();

		private final MockRestServiceServer server;

		private final ToolCallback tool = mock(ToolCallback.class);

		private final OpenRouterChatModel model;

		Exchange(OpenRouterRequestMode mode, boolean stream, boolean reject) {
			this.mode = mode;
			this.stream = stream;
			this.responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
			String endpoint = "https://openrouter.test" + (this.responses ? "/responses" : "/chat/completions");
			HttpStatus status = reject ? HttpStatus.BAD_REQUEST : HttpStatus.OK;
			String responseBody = reject ? "{\"error\":{\"code\":400,\"message\":\"Strict tools unsupported\"}}"
					: RESULT;
			RestClient.Builder rest = RestClient.builder();
			this.server = MockRestServiceServer.bindTo(rest).build();
			if (!stream) {
				this.server.expect(requestTo(endpoint))
					.andExpect(request -> this.body.set(((MockClientHttpRequest) request).getBodyAsString()))
					.andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(responseBody));
			}
			var web = WebClient.builder().clientConnector((method, uri, callback) -> {
				assertThat(uri.toString()).isEqualTo(endpoint);
				var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
				var incoming = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(status);
				incoming.getHeaders().setContentType(reject ? MediaType.APPLICATION_JSON : MediaType.TEXT_EVENT_STREAM);
				String event = this.responses
						? "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
						: "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
				incoming.setBody(reject ? responseBody : event);
				return callback.apply(outgoing)
					.then(reactor.core.publisher.Mono.defer(outgoing::getBodyAsString))
					.doOnNext(this.body::set)
					.thenReturn(incoming);
			});
			var api = OpenRouterApi.builder()
				.apiKey("test-key")
				.baseUrl("https://openrouter.test")
				.restClientBuilder(rest)
				.webClientBuilder(web)
				.build();
			when(this.tool.getToolDefinition()).thenReturn(ToolDefinition.builder()
				.name("synthetic")
				.description("Synthetic tool")
				.inputSchema(SCHEMA)
				.build());
			this.model = OpenRouterChatModel.builder()
				.openRouterApi(api)
				.defaultOptions(OpenRouterChatOptions.builder().model("synthetic/model").requestMode(mode).build())
				.build();
		}

		Prompt prompt(@Nullable Boolean strict) {
			return new Prompt("Synthetic request",
					OpenRouterChatOptions.builder()
						.model("synthetic/model")
						.requestMode(this.mode)
						.toolCallbacks(this.tool)
						.toolStrict(strict)
						.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", false, SCHEMA))
						.build());
		}

		void succeed(Prompt prompt) {
			if (this.stream) {
				StepVerifier.create(this.model.stream(prompt)).thenConsumeWhile(value -> true).verifyComplete();
			}
			else {
				this.model.call(prompt);
			}
		}

		void failWithRejection(Prompt prompt) {
			if (this.stream) {
				StepVerifier.create(this.model.stream(prompt))
					.expectErrorSatisfies(OpenRouterStrictToolsTests::assertStrictToolsRejection)
					.verify();
			}
			else {
				assertThatThrownBy(() -> this.model.call(prompt))
					.satisfies(OpenRouterStrictToolsTests::assertStrictToolsRejection);
			}
		}

		JsonNode toolFunction() {
			JsonNode json = OpenRouterStrictToolsTests.this.mapper.readTree(this.body.get());
			return json.at(this.responses ? "/tools/0" : "/tools/0/function");
		}

		void assertSchemaAndOtherFieldsUntouched() {
			JsonNode json = OpenRouterStrictToolsTests.this.mapper.readTree(this.body.get());
			assertThat(toolFunction().path("parameters"))
				.isEqualTo(OpenRouterStrictToolsTests.this.mapper.readTree(SCHEMA));
			assertThat(
					json.at(this.responses ? "/text/format/strict" : "/response_format/json_schema/strict").asBoolean())
				.isFalse();
			assertThat(json.path("stream").asBoolean()).isEqualTo(this.stream);
			this.server.verify();
		}

	}

}
