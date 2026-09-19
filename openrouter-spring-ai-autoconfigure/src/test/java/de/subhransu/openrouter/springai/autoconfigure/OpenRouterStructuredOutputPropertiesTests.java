package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenRouterStructuredOutputPropertiesTests {

	private static final String PREFIX = "spring.ai.openrouter.chat.";

	private static final String SCHEMA = """
			{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
			"""
		.trim();

	private final ObjectMapper mapper = new ObjectMapper();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=test-key", PREFIX + "model=test/model");

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void propertiesMatchJavaOptionsOnBothTransports(OpenRouterRequestMode mode) {
		assertRequests(mode, OpenRouterChatOptions.builder().build());
		assertRequests(mode, OpenRouterChatOptions.builder().outputSchema(SCHEMA).build(), "output-schema=" + SCHEMA);
		for (Boolean strict : new Boolean[] { null, true, false }) {
			List<String> properties = new ArrayList<>(List.of("response-format.type=json-schema",
					"response-format.name=answer", "response-format.schema=" + SCHEMA, "output-schema=invalid"));
			if (strict != null) {
				properties.add("response-format.strict=" + strict);
			}
			assertRequests(mode,
					OpenRouterChatOptions.builder()
						.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", strict, SCHEMA))
						.outputSchema("invalid")
						.build(),
					properties.toArray(String[]::new));
		}
		for (OpenRouterResponseFormat.Type type : List.of(OpenRouterResponseFormat.Type.TEXT,
				OpenRouterResponseFormat.Type.JSON_OBJECT)) {
			assertRequests(mode,
					OpenRouterChatOptions.builder()
						.responseFormat(new OpenRouterResponseFormat(type, null, null, null))
						.outputSchema(SCHEMA)
						.build(),
					"response-format.type=" + type, "output-schema=" + SCHEMA);
		}
	}

	private void assertRequests(OpenRouterRequestMode mode, OpenRouterChatOptions javaOptions, String... properties) {
		AtomicReference<String> body = new AtomicReference<>();
		runner.withPropertyValues(PREFIX + "request-mode=" + mode)
			.withPropertyValues(java.util.Arrays.stream(properties).map(value -> PREFIX + value).toArray(String[]::new))
			.withBean("recordingFactory", ClientHttpRequestFactoryBuilder.class,
					() -> settings -> (uri, method) -> new MockClientHttpRequest(method, uri) {
						@Override
						protected org.springframework.http.client.ClientHttpResponse executeInternal() {
							body.set(getBodyAsString());
							MockClientHttpResponse response = new MockClientHttpResponse(
									"""
											{"id":"synthetic","status":"completed","output":[],
											"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
											"""
										.getBytes(StandardCharsets.UTF_8),
									HttpStatus.OK);
							response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
							return response;
						}
					})
			.withBean(WebClient.Builder.class, () -> WebClient.builder().clientConnector((method, uri, callback) -> {
				var request = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
				var response = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(HttpStatus.OK);
				response.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
				response.setBody(mode == OpenRouterRequestMode.OPENAI_RESPONSES
						? "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
						: "data: [DONE]\n\n");
				return callback.apply(request)
					.then(Mono.defer(request::getBodyAsString))
					.doOnNext(body::set)
					.thenReturn(response);
			}))
			.run(context -> {
				assertThat(context).hasNotFailed();
				var model = context.getBean(OpenRouterChatModel.class);
				var bound = (OpenRouterChatOptions) model.getOptions();
				assertThat(bound.getResponseFormat()).isEqualTo(javaOptions.getResponseFormat());
				assertThat(bound.getOutputSchema()).isEqualTo(javaOptions.getOutputSchema());
				var prompt = new Prompt("Return a synthetic answer");
				model.call(prompt);
				JsonNode configured = mapper.readTree(body.get());
				assertFormat(configured, mode, javaOptions);
				model.call(new Prompt("Return a synthetic answer",
						javaOptions.mutate().model("test/model").requestMode(mode).build()));
				assertThat(mapper.readTree(body.get())).isEqualTo(configured);
				model.stream(prompt).blockLast(Duration.ofSeconds(5));
				JsonNode streaming = mapper.readTree(body.get());
				assertThat(streaming.path("stream").asBoolean()).isTrue();
				assertFormat(streaming, mode, javaOptions);
				model.call(new Prompt("Return a synthetic answer",
						OpenRouterChatOptions.builder()
							.requestMode(mode)
							.responseFormat(OpenRouterResponseFormat.text())
							.build()));
				assertThat(mapper.readTree(body.get()).at(formatPath(mode)).path("type").asString()).isEqualTo("text");
			});
	}

	private void assertFormat(JsonNode request, OpenRouterRequestMode mode, OpenRouterChatOptions options) {
		JsonNode format = request.at(formatPath(mode));
		var explicit = options.getResponseFormat();
		if (explicit == null && options.getOutputSchema() == null) {
			assertThat(format.isMissingNode() || format.isNull()).isTrue();
			return;
		}
		String type = explicit == null ? "json_schema" : explicit.type().name().toLowerCase(java.util.Locale.ROOT);
		assertThat(format.path("type").asString()).isEqualTo(type);
		if ("json_schema".equals(type)) {
			JsonNode schema = mode == OpenRouterRequestMode.OPENAI_RESPONSES ? format : format.path("json_schema");
			assertThat(schema.path("schema")).isEqualTo(mapper.readTree(SCHEMA));
			assertThat(schema.path("name").asString()).isEqualTo(explicit == null ? "response" : "answer");
			if (explicit == null || explicit.strict() == null) {
				assertThat(schema.has("strict")).isFalse();
			}
			else {
				assertThat(schema.path("strict").asBoolean()).isEqualTo(explicit.strict());
			}
		}
	}

	private String formatPath(OpenRouterRequestMode mode) {
		return mode == OpenRouterRequestMode.OPENAI_RESPONSES ? "/text/format" : "/response_format";
	}

	@ParameterizedTest
	@ValueSource(strings = { "response-format.type=json-schema", "response-format.schema={}",
			"response-format.type=unsupported" })
	void rejectsIncompleteOrUnknownFormats(String property) {
		runner.withPropertyValues(PREFIX + property).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void malformedJsonFailsBeforeSendingRequests(OpenRouterRequestMode mode) {
		for (String[] properties : List.of(new String[] { PREFIX + "output-schema={invalid" }, new String[] {
				PREFIX + "response-format.type=json-schema", PREFIX + "response-format.schema={invalid" })) {
			OpenRouterApi api = mock(OpenRouterApi.class);
			runner.withBean(OpenRouterApi.class, () -> api)
				.withPropertyValues(PREFIX + "request-mode=" + mode)
				.withPropertyValues(properties)
				.run(context -> {
					assertThat(context).hasNotFailed();
					var model = context.getBean(OpenRouterChatModel.class);
					assertThatThrownBy(() -> model.call(new Prompt("Synthetic input")))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("Invalid JSON schema");
					assertThatThrownBy(
							() -> model.stream(new Prompt("Synthetic input")).blockLast(Duration.ofSeconds(5)))
						.isInstanceOf(IllegalArgumentException.class)
						.hasMessageContaining("Invalid JSON schema");
					verifyNoInteractions(api);
				});
		}
	}

	@Test
	void publishesNestedConfigurationMetadata() throws Exception {
		try (var input = getClass().getClassLoader()
			.getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
			assertThat(input).isNotNull();
			List<String> names = new ArrayList<>();
			mapper.readTree(input).path("properties").forEach(property -> names.add(property.path("name").asString()));
			assertThat(names).contains(PREFIX + "output-schema", PREFIX + "response-format.type",
					PREFIX + "response-format.name", PREFIX + "response-format.strict",
					PREFIX + "response-format.schema");
		}
	}

}
