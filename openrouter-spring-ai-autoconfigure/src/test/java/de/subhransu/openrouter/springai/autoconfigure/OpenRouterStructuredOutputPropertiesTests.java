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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.MissingNode;
import tools.jackson.databind.node.ObjectNode;

class OpenRouterStructuredOutputPropertiesTests {

	private static final String QUESTION = "Return a synthetic answer";

	private static final String PREFIX = "spring.ai.openrouter.chat.";

	private static final String SCHEMA = """
			{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
			"""
		.trim();

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=test-key", PREFIX + "model=test/model");

	static Stream<Arguments> toolStrictValues() {
		return Stream.of(Arguments.of(Named.of("omitted", (Boolean) null)), Arguments.of(Named.of("false", false)),
				Arguments.of(Named.of("true", true)));
	}

	@ParameterizedTest
	@MethodSource("toolStrictValues")
	void toolStrictPropertyRetainsOmittedFalseAndTrue(Boolean strict) {
		String[] properties = strict == null ? new String[0] : new String[] { PREFIX + "tool-strict=" + strict };
		this.runner.withPropertyValues(properties).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenRouterChatProperties.class).toOptions().getToolStrict()).isEqualTo(strict);
			var defaults = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class).getOptions();
			assertThat(defaults.getToolStrict()).isEqualTo(strict);
		});
	}

	static Stream<Arguments> formatScenarios() {
		List<FormatScenario> scenarios = new ArrayList<>();
		scenarios.add(new FormatScenario("no structured output", OpenRouterChatOptions.builder().build(),
				MissingNode.getInstance(), MissingNode.getInstance()));
		scenarios.add(new FormatScenario("output schema", OpenRouterChatOptions.builder().outputSchema(SCHEMA).build(),
				chatJsonSchema("response", null), responsesJsonSchema("response", null), "output-schema=" + SCHEMA));
		for (Boolean strict : new Boolean[] { null, true, false }) {
			List<String> properties = new ArrayList<>(List.of("response-format.type=json-schema",
					"response-format.name=answer", "response-format.schema=" + SCHEMA, "output-schema=invalid"));
			if (strict != null) {
				properties.add("response-format.strict=" + strict);
			}
			scenarios
				.add(new FormatScenario("json schema response format, strict " + (strict == null ? "omitted" : strict),
						OpenRouterChatOptions.builder()
							.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", strict, SCHEMA))
							.outputSchema("invalid")
							.build(),
						chatJsonSchema("answer", strict), responsesJsonSchema("answer", strict),
						properties.toArray(String[]::new)));
		}
		for (OpenRouterResponseFormat.Type type : List.of(OpenRouterResponseFormat.Type.TEXT,
				OpenRouterResponseFormat.Type.JSON_OBJECT)) {
			JsonNode simple = MAPPER.createObjectNode().put("type", type.name().toLowerCase(Locale.ROOT));
			scenarios.add(new FormatScenario(type + " response format overrides the output schema",
					OpenRouterChatOptions.builder()
						.responseFormat(new OpenRouterResponseFormat(type, null, null, null))
						.outputSchema(SCHEMA)
						.build(),
					simple, simple, "response-format.type=" + type, "output-schema=" + SCHEMA));
		}
		return Stream.of(OpenRouterRequestMode.values())
			.flatMap(mode -> scenarios.stream()
				.map(scenario -> Arguments.of(Named.of(mode.name(), mode), Named.of(scenario.name(), scenario))));
	}

	private static JsonNode chatJsonSchema(String name, Boolean strict) {
		ObjectNode jsonSchema = MAPPER.createObjectNode().put("name", name);
		jsonSchema.set("schema", MAPPER.readTree(SCHEMA));
		if (strict != null) {
			jsonSchema.put("strict", strict);
		}
		ObjectNode format = MAPPER.createObjectNode().put("type", "json_schema");
		format.set("json_schema", jsonSchema);
		return format;
	}

	private static JsonNode responsesJsonSchema(String name, Boolean strict) {
		ObjectNode format = MAPPER.createObjectNode().put("type", "json_schema").put("name", name);
		format.set("schema", MAPPER.readTree(SCHEMA));
		if (strict != null) {
			format.put("strict", strict);
		}
		return format;
	}

	@ParameterizedTest
	@MethodSource("formatScenarios")
	void configuredPropertiesBindTheSameOptionsAsJava(OpenRouterRequestMode mode, FormatScenario scenario) {
		run(mode, scenario, (model, transport) -> {
			var bound = (OpenRouterChatOptions) model.getOptions();
			assertThat(bound.getResponseFormat()).isEqualTo(scenario.javaOptions().getResponseFormat());
			assertThat(bound.getOutputSchema()).isEqualTo(scenario.javaOptions().getOutputSchema());
		});
	}

	@ParameterizedTest
	@MethodSource("formatScenarios")
	void configuredPropertiesSendTheExpectedFormatOnABlockingCall(OpenRouterRequestMode mode, FormatScenario scenario) {
		run(mode, scenario, (model, transport) -> {
			model.call(new Prompt(QUESTION));

			assertThat(sentBody(transport).at(formatPath(mode))).isEqualTo(scenario.expectedFormat(mode));
		});
	}

	@ParameterizedTest
	@MethodSource("formatScenarios")
	void javaOptionsSendTheSameBodyAsConfiguredProperties(OpenRouterRequestMode mode, FormatScenario scenario) {
		run(mode, scenario, (model, transport) -> {
			model.call(new Prompt(QUESTION));
			JsonNode configured = sentBody(transport);

			model.call(new Prompt(QUESTION,
					scenario.javaOptions().mutate().model("test/model").requestMode(mode).build()));

			assertThat(sentBody(transport)).isEqualTo(configured);
		});
	}

	@ParameterizedTest
	@MethodSource("formatScenarios")
	void configuredPropertiesSendTheExpectedFormatOnAStreamingCall(OpenRouterRequestMode mode,
			FormatScenario scenario) {
		run(mode, scenario, (model, transport) -> {
			// The synthetic stream is finite, so the bound only keeps a broken stream
			// from hanging the build.
			model.stream(new Prompt(QUESTION)).blockLast(Duration.ofSeconds(5));

			JsonNode streaming = sentBody(transport);
			assertThat(streaming.path("stream").asBoolean()).isTrue();
			assertThat(streaming.at(formatPath(mode))).isEqualTo(scenario.expectedFormat(mode));
		});
	}

	@ParameterizedTest
	@MethodSource("formatScenarios")
	void aTextFormatInTheRequestOverridesTheConfiguredFormat(OpenRouterRequestMode mode, FormatScenario scenario) {
		run(mode, scenario, (model, transport) -> {
			model.call(new Prompt(QUESTION,
					OpenRouterChatOptions.builder()
						.requestMode(mode)
						.responseFormat(OpenRouterResponseFormat.text())
						.build()));

			assertThat(sentBody(transport).at(formatPath(mode)))
				.isEqualTo(MAPPER.createObjectNode().put("type", "text"));
		});
	}

	private void run(OpenRouterRequestMode mode, FormatScenario scenario,
			BiConsumer<OpenRouterChatModel, RecordingTransport> test) {
		RecordingTransport transport = new RecordingTransport().blockingResponse(HttpStatus.OK, """
				{"id":"synthetic","status":"completed","output":[],
				"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"ok"}}]}
				""").streamingResponse(HttpStatus.OK, MediaType.TEXT_EVENT_STREAM, streamingBody(mode));
		transport
			.installOn(this.runner.withPropertyValues(PREFIX + "request-mode=" + mode)
				.withPropertyValues(
						Arrays.stream(scenario.properties()).map(value -> PREFIX + value).toArray(String[]::new)))
			.run(context -> {
				assertThat(context).hasNotFailed();
				test.accept(context.getBean(OpenRouterChatModel.class), transport);
			});
	}

	private static String streamingBody(OpenRouterRequestMode mode) {
		return switch (mode) {
			case OPENAI_RESPONSES ->
				"data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n";
			case OPENAI_CHAT_COMPLETIONS -> "data: [DONE]\n\n";
		};
	}

	private static JsonNode sentBody(RecordingTransport transport) {
		List<RecordingTransport.Exchange> exchanges = transport.exchanges();
		return MAPPER.readTree(exchanges.get(exchanges.size() - 1).body());
	}

	private static String formatPath(OpenRouterRequestMode mode) {
		return mode == OpenRouterRequestMode.OPENAI_RESPONSES ? "/text/format" : "/response_format";
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|',
			value = { "response-format.type=json-schema | requires a schema",
					"response-format.schema={} | type must not be null",
					"response-format.type=unsupported | spring.ai.openrouter.chat.response-format.type" })
	void rejectsIncompleteOrUnknownFormats(String property, String reason) {
		runner.withPropertyValues(PREFIX + property).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining(reason);
		});
	}

	static Stream<Arguments> malformedSchemas() {
		List<Arguments> cases = List.of(
				Arguments.of(Named.of("malformed output schema", new String[] { PREFIX + "output-schema={invalid" })),
				Arguments.of(Named.of("malformed response-format schema",
						new String[] { PREFIX + "response-format.type=json-schema",
								PREFIX + "response-format.schema={invalid" })),
				Arguments.of(Named.of("blank response-format schema", new String[] {
						PREFIX + "response-format.type=json-schema", PREFIX + "response-format.schema=" })));
		return Stream.of(OpenRouterRequestMode.values())
			.flatMap(mode -> cases.stream()
				.map(malformed -> Arguments.of(Named.of(mode.name(), mode), malformed.get()[0])));
	}

	@ParameterizedTest
	@MethodSource("malformedSchemas")
	void malformedJsonFailsBeforeSendingRequests(OpenRouterRequestMode mode, String[] properties) {
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
				assertThatThrownBy(() -> model.stream(new Prompt("Synthetic input")).blockLast(Duration.ofSeconds(5)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("Invalid JSON schema");
				verifyNoInteractions(api);
			});
	}

	/**
	 * A structured-output configuration and the response format subtree that must reach
	 * the wire in each mode: a missing node when none is sent.
	 */
	record FormatScenario(String name, OpenRouterChatOptions javaOptions, JsonNode chatFormat, JsonNode responsesFormat,
			String... properties) {

		JsonNode expectedFormat(OpenRouterRequestMode mode) {
			return mode == OpenRouterRequestMode.OPENAI_RESPONSES ? this.responsesFormat : this.chatFormat;
		}

	}

}
