package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.util.stream.Stream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.ParameterizedTest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.List;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * JSON-boundary serialization tests. Every public OpenRouter chat option is mapped to a
 * request DTO and serialized through the configured {@link ObjectMapper}, then asserted
 * against the OpenRouter wire field name and value shape. Object-equality mapper tests
 * cannot catch a wrong {@code @JsonProperty} name, an omitted field, or a NON_EMPTY
 * regression -- these can. Spring property binding and provider response mapping are out
 * of scope (separate issues).
 */
class OpenRouterChatRequestSerializationTests {

	private static final String MAX_OUTPUT_TOKENS = "max_output_tokens";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenRouterChatRequestMapper chatMapper = new OpenRouterChatRequestMapper(this.objectMapper);

	private final OpenRouterResponsesRequestMapper responsesMapper = new OpenRouterResponsesRequestMapper(
			this.objectMapper);

	private JsonNode serializeChat(OpenRouterChatOptions options, List<Message> messages, List<ToolDefinition> tools) {
		ChatCompletionRequest request = this.chatMapper.map(messages, options, false, tools);
		return this.objectMapper.valueToTree(request);
	}

	private JsonNode serializeChat(OpenRouterChatOptions options) {
		return serializeChat(options, List.of(new UserMessage("hello")), List.of());
	}

	private JsonNode serializeResponses(OpenRouterChatOptions options, List<Message> messages,
			List<ToolDefinition> tools) {
		ResponsesRequest request = this.responsesMapper.map(messages, options, false, tools);
		return this.objectMapper.valueToTree(request);
	}

	private JsonNode serialize(Wire wire, OpenRouterChatOptions options) {
		return serialize(wire, options, List.of());
	}

	private JsonNode serialize(Wire wire, OpenRouterChatOptions options, List<ToolDefinition> tools) {
		List<Message> messages = List.of(new UserMessage("hello"));
		return wire == Wire.CHAT ? serializeChat(options, messages, tools)
				: serializeResponses(options, messages, tools);
	}

	static Stream<Arguments> wires() {
		return Stream.of(Arguments.of(Named.of("chat completions", Wire.CHAT)),
				Arguments.of(Named.of("responses", Wire.RESPONSES)));
	}

	private OpenRouterChatOptions.Builder base() {
		return OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini");
	}

	private ToolDefinition weatherTool() {
		return ToolDefinition.builder().name("get_weather").description("Look up the weather").inputSchema("""
				{
				  "type": "object",
				  "properties": {
				    "city": {
				      "type": "string"
				    }
				  }
				}
				""").build();
	}

	// ---------------------------------------------------------------------
	// Response format / structured output
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("wires")
	void malformedToolSchemaThrowsTypedException(Wire wire) {
		ToolDefinition badTool = ToolDefinition.builder()
			.name("broken")
			.description("broken schema")
			.inputSchema("{not valid json")
			.build();

		assertThatThrownBy(() -> serialize(wire, base().build(), List.of(badTool)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
	}

	// ---------------------------------------------------------------------
	// Tools
	// ---------------------------------------------------------------------

	@Test
	void chatToolsUseTheNestedFunctionShape() {
		JsonNode tool = serializeChat(base().build(), List.of(new UserMessage("hi")), List.of(weatherTool()))
			.at("/tools/0");

		assertThat(tool.path("type").stringValue()).isEqualTo("function");
		assertThat(tool.path("function").path("name").stringValue()).isEqualTo("get_weather");
		assertThat(tool.path("function").path("parameters").path("type").stringValue()).isEqualTo("object");
	}

	// ---------------------------------------------------------------------
	// Provider routing, reasoning, service tier, metadata, route
	// ---------------------------------------------------------------------

	@ParameterizedTest
	@MethodSource("wires")
	void serializesProviderRoutingPreferences(Wire wire) {
		OpenRouterProviderPreferences provider = new OpenRouterProviderPreferences(true, false, "deny",
				List.of("openai", "anthropic"), List.of("azure"), List.of("fp16", "int8"), "throughput");
		JsonNode json = serialize(wire, base().provider(provider).build()).path("provider");

		assertThat(json.path("allow_fallbacks").asBoolean()).isTrue();
		assertThat(json.path("require_parameters").asBoolean()).isFalse();
		assertThat(json.path("data_collection").stringValue()).isEqualTo("deny");
		assertThat(json.path("order").get(0).stringValue()).isEqualTo("openai");
		assertThat(json.path("ignore").get(0).stringValue()).isEqualTo("azure");
		assertThat(json.path("quantizations").get(1).stringValue()).isEqualTo("int8");
		assertThat(json.path("sort").stringValue()).isEqualTo("throughput");
	}

	private static final String OBJECT_SCHEMA = """
			{"type":"object","properties":{"answer":{"type":"string"}}}
			""";

	private static String formatPath(Wire wire) {
		return wire == Wire.CHAT ? "/response_format" : "/text/format";
	}

	static Stream<Arguments> wiresWithStreaming() {
		return Stream.of(Arguments.of(Named.of("chat completions", Wire.CHAT), Named.of("blocking", false)),
				Arguments.of(Named.of("chat completions", Wire.CHAT), Named.of("streaming", true)),
				Arguments.of(Named.of("responses", Wire.RESPONSES), Named.of("blocking", false)),
				Arguments.of(Named.of("responses", Wire.RESPONSES), Named.of("streaming", true)));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("wiresWithStreaming")
	void serializesTheStreamFlagOnBothWires(Wire wire, boolean stream) {
		var messages = List.<Message>of(new UserMessage("hi"));
		var options = base().build();
		Object request = wire == Wire.CHAT ? this.chatMapper.map(messages, options, stream, List.of())
				: this.responsesMapper.map(messages, options, stream, List.of());
		assertThat(this.objectMapper.valueToTree(request).path("stream").asBoolean()).isEqualTo(stream);
	}

	@ParameterizedTest(name = "includeUsage={0}")
	@ValueSource(booleans = { false, true })
	void chatRequestsCarryTheUsageIncludeFlag(boolean include) {
		var request = this.chatMapper.map(List.of(new UserMessage("hi")), base().includeUsage(include).build(), true,
				List.of());
		assertThat(this.objectMapper.valueToTree(request).at("/usage/include"))
			.isEqualTo(this.objectMapper.valueToTree(include));
	}

	@ParameterizedTest
	@MethodSource("wires")
	void outputSchemaBecomesAJsonSchemaFormatNamedResponse(Wire wire) {
		JsonNode format = serialize(wire, base().outputSchema(OBJECT_SCHEMA).build()).at(formatPath(wire));
		JsonNode schema = wire == Wire.CHAT ? format.path("json_schema") : format;
		assertThat(format.path("type").stringValue()).isEqualTo("json_schema");
		assertThat(schema.path("name").stringValue()).isEqualTo("response");
		assertThat(schema.path("schema")).isEqualTo(this.objectMapper.readTree(OBJECT_SCHEMA));
	}

	@ParameterizedTest
	@MethodSource("wires")
	void explicitResponseFormatTakesPrecedenceOverOutputSchema(Wire wire) {
		JsonNode json = serialize(wire,
				base().responseFormat(OpenRouterResponseFormat.jsonObject()).outputSchema(OBJECT_SCHEMA).build());
		assertThat(json.at(formatPath(wire))).isEqualTo(this.objectMapper.readTree("{\"type\":\"json_object\"}"));
	}

	@ParameterizedTest
	@MethodSource("wires")
	void malformedOutputSchemaThrowsTypedExceptionWithUsefulMessage(Wire wire) {
		assertThatThrownBy(() -> serialize(wire, base().outputSchema("{not valid json").build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
	}

	static Stream<Arguments> reasoningControls() {
		return wires().map(wire -> (Named<?>) wire.get()[0])
			.flatMap(wire -> Stream.of(
					Arguments.of(wire, true, false, "{\"effort\":\"high\",\"exclude\":true,\"enabled\":false}"),
					Arguments.of(wire, false, true, "{\"effort\":\"high\",\"exclude\":false,\"enabled\":true}"),
					Arguments.of(wire, null, null, "{\"effort\":\"high\"}")));
	}

	@ParameterizedTest(name = "{0}: exclude={1}, enabled={2}")
	@MethodSource("reasoningControls")
	void effortAndTheExcludeAndEnabledSwitchesReachTheWire(Wire wire, Boolean exclude, Boolean enabled,
			String expected) {
		JsonNode json = serialize(wire,
				base().reasoning(new OpenRouterReasoningOptions("high", null, exclude, enabled)).build());
		assertThat(json.path("reasoning")).isEqualTo(this.objectMapper.readTree(expected));
	}

	static Stream<Arguments> serviceTiers() {
		var tiers = Map.of(OpenRouterServiceTier.AUTO, "auto", OpenRouterServiceTier.DEFAULT, "default",
				OpenRouterServiceTier.FLEX, "flex", OpenRouterServiceTier.PRIORITY, "priority");
		return wires().map(wire -> (Named<?>) wire.get()[0])
			.flatMap(wire -> tiers.entrySet().stream().map(tier -> Arguments.of(wire, tier.getKey(), tier.getValue())));
	}

	@ParameterizedTest(name = "{0}: {1} -> {2}")
	@MethodSource("serviceTiers")
	void serviceTierSerializesAsItsLowercaseWireValue(Wire wire, OpenRouterServiceTier tier, String expected) {
		JsonNode json = serialize(wire, base().serviceTier(tier).build());
		assertThat(json.path("service_tier").stringValue()).isEqualTo(expected);
	}

	@ParameterizedTest
	@MethodSource("wires")
	void unsetOptionsAreOmittedFromTheWire(Wire wire) {
		JsonNode json = serialize(wire, base().build());
		assertThat(json.propertyNames()).doesNotContain("temperature", "tools", "provider", "reasoning",
				"response_format", "text", "metadata", "usage", "service_tier", "max_tokens", MAX_OUTPUT_TOKENS);
	}

	@Test
	void responsesToolsUseTheFlatFunctionShape() {
		JsonNode tool = serializeResponses(base().build(), List.of(new UserMessage("hi")), List.of(weatherTool()))
			.at("/tools/0");
		assertThat(tool.path("type").stringValue()).isEqualTo("function");
		assertThat(tool.path("name").stringValue()).isEqualTo("get_weather");
		assertThat(tool.path("parameters").path("type").stringValue()).isEqualTo("object");
		assertThat(tool.has("function")).isFalse();
	}

	@ParameterizedTest
	@MethodSource("wires")
	void writesNonEnglishContentAsUtf8ThroughTheRealTransport(Wire wire) {
		String text = "こんにちは世界 🌍 Köln";
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		AtomicReference<byte[]> sent = new AtomicReference<>();
		String path = wire == Wire.CHAT ? "/chat/completions" : "/responses";
		String reply = wire == Wire.CHAT
				? "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
				: "{\"id\":\"resp-1\",\"status\":\"completed\",\"output\":[]}";
		server.expect(requestTo("https://openrouter.test" + path))
			.andExpect(request -> sent.set(((MockClientHttpRequest) request).getBodyAsBytes()))
			.andRespond(withSuccess(reply, MediaType.APPLICATION_JSON));
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("test-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(builder)
			.build();
		var messages = List.<Message>of(new UserMessage(text));
		var options = base().build();
		if (wire == Wire.CHAT) {
			api.chatCompletion(this.chatMapper.map(messages, options, false, List.of()));
		}
		else {
			api.responses(this.responsesMapper.map(messages, options, false, List.of()));
		}
		server.verify();
		byte[] body = sent.get();
		assertThat(body).containsSequence(text.getBytes(StandardCharsets.UTF_8));
		assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain("\\u");
		assertThat(this.objectMapper.readTree(body).toString()).contains(text);
	}

	@ParameterizedTest
	@MethodSource("wires")
	void omitsExplicitlyEmptyCollections(Wire wire) {
		// NON_EMPTY must drop empty lists/maps: OpenRouter rejects some empty arrays
		// (e.g. models and tools) and an empty metadata object is noise.
		JsonNode json = serialize(wire, base().models(List.of()).metadata(Map.of()).build(), List.of());

		assertThat(json.propertyNames()).doesNotContain("models", "metadata", "tools");
	}

	@Test
	void omitsAnExplicitlyEmptyStopListFromTheChatWire() {
		JsonNode json = serializeChat(base().stopSequences(List.of()).build());

		assertThat(json.propertyNames()).doesNotContain("stop");
	}

	@Test
	void omitsNullProviderPreferenceFields() {
		// ProviderPreferences serializes NON_NULL: a preferences object with only one
		// field set must not spray nulls over the provider block.
		OpenRouterProviderPreferences provider = new OpenRouterProviderPreferences(true, null, null, null, null, null,
				null);
		JsonNode json = serializeChat(base().provider(provider).build()).path("provider");

		assertThat(json.path("allow_fallbacks").asBoolean()).isTrue();
		assertThat(json.has("require_parameters")).isFalse();
		assertThat(json.has("data_collection")).isFalse();
		assertThat(json.has("order")).isFalse();
		assertThat(json.has("ignore")).isFalse();
		assertThat(json.has("quantizations")).isFalse();
		assertThat(json.has("sort")).isFalse();
	}

	// ---------------------------------------------------------------------
	// Message shapes (chat-completions)
	// ---------------------------------------------------------------------

	@Test
	void serializesUserSystemAssistantAndToolMessages() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("calling tool")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"X\"}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "\"sunny\"")))
			.build();
		List<Message> messages = List.of(new SystemMessage("be terse"), new UserMessage("weather?"), assistant,
				toolResponse);
		JsonNode json = serializeChat(base().build(), messages, List.of()).path("messages");

		assertThat(json).hasSize(4);
		assertThat(json.get(0).path("role").stringValue()).isEqualTo("system");
		assertThat(json.get(1).path("role").stringValue()).isEqualTo("user");
		assertThat(json.get(2).path("role").stringValue()).isEqualTo("assistant");
		assertThat(json.get(2).path("tool_calls")).hasSize(1);
		assertThat(json.get(2).path("tool_calls").get(0).path("function").path("name").stringValue())
			.isEqualTo("get_weather");
		assertThat(json.get(2).path("tool_calls").get(0).path("function").path("arguments").stringValue())
			.isEqualTo("{\"city\":\"X\"}");
		assertThat(json.get(3).path("role").stringValue()).isEqualTo("tool");
		assertThat(json.get(3).path("tool_call_id").stringValue()).isEqualTo("call-1");
	}

	@Test
	void serializesResponsesInputForUserAssistantToolCallAndToolResponseMessages() {
		AssistantMessage assistant = AssistantMessage.builder()
			.content("calling tool")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"X\"}")))
			.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "\"sunny\"")))
			.build();
		List<Message> messages = List.of(new SystemMessage("be terse"), new UserMessage("weather?"), assistant,
				toolResponse);

		JsonNode json = serializeResponses(base().build(), messages, List.of());

		assertThat(json.path("instructions").stringValue()).isEqualTo("be terse");
		assertThat(json.path("input")).isEqualTo(this.objectMapper.readTree(EXPECTED_RESPONSES_INPUT));
	}

	@Test
	void serializesToolResponseMessageWithMultipleResponses() {
		ToolResponseMessage multi = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "a", "\"r1\""),
					new ToolResponseMessage.ToolResponse("call-2", "b", "\"r2\"")))
			.build();
		JsonNode messages = serializeChat(base().build(), List.of(new UserMessage("hi"), multi), List.of())
			.path("messages");

		// One tool message per response, in order.
		List<JsonNode> toolMessages = messages.findValues("tool_call_id");
		assertThat(toolMessages).extracting(JsonNode::stringValue).containsExactly("call-1", "call-2");
	}

	// ---------------------------------------------------------------------
	// Responses-mode option fields (wire names + camelCase guards)
	// ---------------------------------------------------------------------

	@Test
	void responsesMaxOutputTokensPrefersMaxCompletionTokensOverMaxTokens() {
		JsonNode fallback = serializeResponses(base().maxTokens(256).build(), List.of(new UserMessage("hi")),
				List.of());
		JsonNode preferred = serializeResponses(base().maxTokens(256).maxCompletionTokens(512).build(),
				List.of(new UserMessage("hi")), List.of());

		assertThat(fallback.path(MAX_OUTPUT_TOKENS).asInt()).isEqualTo(256);
		assertThat(preferred.path(MAX_OUTPUT_TOKENS).asInt()).isEqualTo(512);
	}

	private static final String EXPECTED_RESPONSES_INPUT = "[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"weather?\"}]},{\"type\":\"message\",\"status\":\"completed\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"calling tool\"}]},{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\\\"X\\\"}\"},{\"id\":\"fc_output_call-1\",\"type\":\"function_call_output\",\"call_id\":\"call-1\",\"output\":\"\\\"sunny\\\"\"}]";

	private static final String PORTABLE_SCHEMA = "{\"type\":\"object\"}";

	static Stream<Arguments> strictnessWithStreaming() {
		String schema = "\"name\":\"answer\",\"schema\":{\"type\":\"object\"}";
		return Stream
			.of(Arguments.of(Named.of("strict unset", (Boolean) null), "{" + schema + "}",
					"{\"type\":\"json_schema\"," + schema + "}"),
					Arguments.of(Named.of("strict false", false), "{" + schema + ",\"strict\":false}",
							"{\"type\":\"json_schema\"," + schema + ",\"strict\":false}"),
					Arguments.of(Named.of("strict true", true), "{" + schema + ",\"strict\":true}",
							"{\"type\":\"json_schema\"," + schema + ",\"strict\":true}"))
			.flatMap(row -> Stream.of(
					Arguments.of(row.get()[0], row.get()[1], row.get()[2], Named.of("blocking", false)),
					Arguments.of(row.get()[0], row.get()[1], row.get()[2], Named.of("streaming", true))));
	}

	@ParameterizedTest(name = "{0}, {3}")
	@MethodSource("strictnessWithStreaming")
	void jsonSchemaFormatAndNamedToolAreEquivalentOnBothWires(Boolean strict, String chatFormat, String responsesFormat,
			boolean stream) {
		var options = base().outputSchema("invalid but overridden")
			.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", strict, PORTABLE_SCHEMA))
			.toolChoice(Map.of("type", "function", "function", Map.of("name", "get_weather")))
			.build();
		var messages = List.<Message>of(new UserMessage("hi"));
		JsonNode chat = this.objectMapper
			.valueToTree(this.chatMapper.map(messages, options, stream, List.of(weatherTool())));
		JsonNode responses = this.objectMapper
			.valueToTree(this.responsesMapper.map(messages, options, stream, List.of(weatherTool())));

		assertThat(chat.at("/response_format/json_schema")).isEqualTo(this.objectMapper.readTree(chatFormat));
		assertThat(responses.at("/text/format")).isEqualTo(this.objectMapper.readTree(responsesFormat));
		assertThat(responses.at("/tool_choice/name").stringValue()).isEqualTo("get_weather");
		assertThat(chat.at("/tool_choice/function/name").stringValue()).isEqualTo("get_weather");
		assertThat(responses.at("/tools/0").has("strict")).isFalse();
		assertThat(chat.at("/tools/0/function").has("strict")).isFalse();
	}

	static Stream<Arguments> plainFormatsWithStreaming() {
		return Stream
			.of(Arguments.of(Named.of("text format", OpenRouterResponseFormat.text()), "{\"type\":\"text\"}"),
					Arguments.of(Named.of("json_object format", OpenRouterResponseFormat.jsonObject()),
							"{\"type\":\"json_object\"}"))
			.flatMap(row -> Stream.of(Arguments.of(row.get()[0], row.get()[1], Named.of("blocking", false)),
					Arguments.of(row.get()[0], row.get()[1], Named.of("streaming", true))));
	}

	@ParameterizedTest(name = "{0}, {2}")
	@MethodSource("plainFormatsWithStreaming")
	void textAndJsonObjectFormatsMatchOnBothWires(OpenRouterResponseFormat format, String expected, boolean stream) {
		var options = base().responseFormat(format).build();
		var messages = List.<Message>of(new UserMessage("hi"));
		JsonNode chat = this.objectMapper.valueToTree(this.chatMapper.map(messages, options, stream, List.of()));
		JsonNode responses = this.objectMapper
			.valueToTree(this.responsesMapper.map(messages, options, stream, List.of()));
		JsonNode expectedFormat = this.objectMapper.readTree(expected);
		assertThat(chat.path("response_format")).isEqualTo(expectedFormat);
		assertThat(responses.at("/text/format")).isEqualTo(expectedFormat);
	}

	@ParameterizedTest(name = "stream={0}")
	@ValueSource(booleans = { false, true })
	void outputSchemaIsPortableWithoutStrict(boolean stream) {
		var options = base().outputSchema(PORTABLE_SCHEMA).build();
		JsonNode portable = this.objectMapper
			.valueToTree(this.responsesMapper.map(List.of(new UserMessage("hi")), options, stream, List.of()));
		assertThat(portable.at("/text/format/schema")).isEqualTo(this.objectMapper.readTree(PORTABLE_SCHEMA));
		assertThat(portable.at("/text/format").has("strict")).isFalse();
	}

	static Stream<Arguments> namedToolChoices() {
		return Stream
			.of(Named.of("flat shape", "{\"type\":\"function\",\"name\":\"get_weather\"}"),
					Named.of("nested shape", "{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}"))
			.flatMap(choice -> Stream.of(Arguments.of(choice, Named.of("blocking", false)),
					Arguments.of(choice, Named.of("streaming", true))));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("namedToolChoices")
	void namedToolChoicesSerializeTypeFirst(String choice, boolean stream) {
		var options = base().toolChoice(this.objectMapper.readTree(choice)).build();
		var messages = List.<Message>of(new UserMessage("hi"));
		assertThat(this.objectMapper.writeValueAsString(this.chatMapper.map(messages, options, stream, List.of())))
			.contains("\"tool_choice\":{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}");
		assertThat(this.objectMapper.writeValueAsString(this.responsesMapper.map(messages, options, stream, List.of())))
			.contains("\"tool_choice\":{\"type\":\"function\",\"name\":\"get_weather\"}");
	}

	@ParameterizedTest(name = "{0} -> \"{1}\"")
	@CsvSource(delimiter = '|', value = { "auto|auto", "none|none", "required|required", "{\"type\":\"auto\"}|auto",
			"{\"type\":\"none\"}|none", "{\"type\":\"required\"}|required" })
	void stringAndTypeOnlyToolChoicesSerializeAsTheSameStringOnBothWires(String choice, String expected) {
		Object value = choice.startsWith("{") ? this.objectMapper.readTree(choice) : choice;
		var options = base().toolChoice(value).build();
		JsonNode chat = serializeChat(options).path("tool_choice");
		JsonNode responses = serializeResponses(options, List.of(new UserMessage("hi")), List.of()).path("tool_choice");
		assertThat(chat.stringValue()).isEqualTo(expected);
		assertThat(responses).isEqualTo(chat);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"unknown\"", "{}", "{\"type\":\"unknown\"}", "{\"type\":\"function\",\"name\":\" \"}",
			"{\"type\":\"function\",\"name\":1}", "{\"type\":\"function\",\"function\":{}}" })
	void rejectsInvalidToolChoices(String choice) {
		var options = base().toolChoice(this.objectMapper.readTree(choice)).build();
		assertThatThrownBy(() -> serializeChat(options)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("toolChoice");
		assertThatThrownBy(() -> serializeResponses(options, List.of(new UserMessage("hi")), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("toolChoice");
	}

	private static Stream<Arguments> withStreaming(Stream<OptionRow> rows) {
		return rows.flatMap(row -> Stream.of(Arguments.of(Named.of(row.name(), row), Named.of("blocking", false)),
				Arguments.of(Named.of(row.name(), row), Named.of("streaming", true))));
	}

	static Stream<Arguments> chatMappedOptions() {
		return withStreaming(optionRows().stream().filter(row -> row.isMapped(row.chatPath())));
	}

	static Stream<Arguments> responsesMappedOptions() {
		return withStreaming(optionRows().stream().filter(row -> row.isMapped(row.responsesPath())));
	}

	static Stream<Arguments> clientOnlyOptions() {
		return withStreaming(
				optionRows().stream().filter(row -> row.chatPath() == null && row.responsesPath() == null));
	}

	static Stream<Arguments> responsesRejectedOptions() {
		// audio is rejected earlier with its own reason; see
		// audioOptionAloneIsRejected...
		return withStreaming(optionRows().stream()
			.filter(row -> "rejected".equals(row.responsesPath()) && !"audio".equals(row.name())));
	}

	private static OptionRow row(String name) {
		return optionRows().stream()
			.filter(candidate -> candidate.name().equals(name))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no option row named " + name));
	}

	private OpenRouterChatOptions optionsWith(OptionRow row) throws Exception {
		var builder = OpenRouterChatOptions.builder();
		var method = Arrays.stream(builder.getClass().getMethods())
			.filter(candidate -> candidate.getName().equals(row.name()) && candidate.getParameterCount() == 1
					&& candidate.getParameterTypes()[0].isInstance(row.value()))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no builder method accepting the value of " + row.name()));
		method.invoke(builder, row.value());
		return builder.build();
	}

	private List<ToolDefinition> toolsFor(OptionRow row) {
		return "toolCallbacks".equals(row.name()) || "toolStrict".equals(row.name()) ? List.of(weatherTool())
				: List.of();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("chatMappedOptions")
	void mappedOptionsReachTheChatWireUnderTheirWireName(OptionRow row, boolean stream) throws Exception {
		JsonNode chat = this.objectMapper
			.valueToTree(this.chatMapper.map(List.of(new UserMessage("hi")), optionsWith(row), stream, toolsFor(row)));

		assertThat(chat.at(row.chatPath())).isEqualTo(this.objectMapper.valueToTree(row.expected()));
		assertThat(
				chat.propertyNames().stream().filter(row.camelCaseNamesAbsentFrom(row.chatPath())::contains).toList())
			.as("camelCase names on the chat wire")
			.isEmpty();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("responsesMappedOptions")
	void mappedOptionsReachTheResponsesWireUnderTheirWireName(OptionRow row, boolean stream) throws Exception {
		JsonNode responses = this.objectMapper.valueToTree(
				this.responsesMapper.map(List.of(new UserMessage("hi")), optionsWith(row), stream, toolsFor(row)));

		assertThat(responses.at(row.responsesPath())).isEqualTo(this.objectMapper.valueToTree(row.expected()));
		assertThat(responses.propertyNames()
			.stream()
			.filter(row.camelCaseNamesAbsentFrom(row.responsesPath())::contains)
			.toList()).as("camelCase names on the responses wire").isEmpty();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("clientOnlyOptions")
	void clientOnlyOptionsReachNeitherWire(OptionRow row, boolean stream) throws Exception {
		var options = optionsWith(row);
		var messages = List.<Message>of(new UserMessage("hi"));

		assertThat(this.objectMapper.valueToTree(this.chatMapper.map(messages, options, stream, List.of()))
			.has(row.name())).isFalse();
		assertThat(this.objectMapper.valueToTree(this.responsesMapper.map(messages, options, stream, List.of()))
			.has(row.name())).isFalse();
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("responsesRejectedOptions")
	void responsesModeRejectsOptionsItCannotSend(OptionRow row, boolean stream) throws Exception {
		var options = optionsWith(row);
		var messages = List.<Message>of(new UserMessage("hi"));

		assertThatThrownBy(() -> this.responsesMapper.map(messages, options, stream, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("OPENAI_RESPONSES does not support %s; unset it or use OPENAI_CHAT_COMPLETIONS", row.name());
	}

	@ParameterizedTest(name = "stream={0}")
	@ValueSource(booleans = { false, true })
	void audioOptionAloneIsRejectedWithTheModeSpecificReason(boolean stream) throws Exception {
		var options = optionsWith(row("audio"));
		var messages = List.<Message>of(new UserMessage("hi"));
		String chatReason = stream ? "Audio output requires both audio options and the audio modality"
				: "Audio output requires streaming Chat Completions";

		assertThatThrownBy(() -> this.chatMapper.map(messages, options, stream, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(chatReason);
		assertThatThrownBy(() -> this.responsesMapper.map(messages, options, stream, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Audio output requires streaming Chat Completions");
	}

	static List<OptionRow> optionRows() {
		Object[][] rows = { { "model", "test/model", "/model", "/model" },
				{ "models", List.of("test/fallback"), "/models", "/models" },
				{ "requestMode", OpenRouterRequestMode.OPENAI_RESPONSES, null, null },
				{ "frequencyPenalty", 0.0, "/frequency_penalty", "/frequency_penalty" },
				{ "maxTokens", 12, "/max_tokens", "/max_output_tokens" },
				{ "maxCompletionTokens", 24, "/max_completion_tokens", "/max_output_tokens" },
				{ "presencePenalty", 0.0, "/presence_penalty", "/presence_penalty" },
				{ "temperature", 0.0, "/temperature", "/temperature" }, { "topK", 10, "/top_k", "/top_k" },
				{ "topP", 0.5, "/top_p", "/top_p" }, { "user", "synthetic-user", "/user", "/user" },
				{ "responseFormat", OpenRouterResponseFormat.jsonObject(), "/response_format", "/text/format",
						Map.of("type", "json_object") },
				{ "outputSchema", "{\"type\":\"object\"}", "/response_format/json_schema/schema", "/text/format/schema",
						Map.of("type", "object") },
				{ "parallelToolCalls", false, "/parallel_tool_calls", "/parallel_tool_calls" },
				{ "toolChoice", "required", "/tool_choice", "/tool_choice" },
				{ "toolStrict", false, "/tools/0/function/strict", "/tools/0/strict" },
				{ "provider", new OpenRouterProviderPreferences(true, null, null, null, null, null, null), "/provider",
						"/provider", Map.of("allow_fallbacks", true) },
				{ "reasoning", new OpenRouterReasoningOptions("high", null, null, null), "/reasoning", "/reasoning",
						Map.of("effort", "high") },
				{ "serviceTier", OpenRouterServiceTier.FLEX, "/service_tier", "/service_tier", "flex" },
				{ "metadata", Map.of("test", "value"), "/metadata", "/metadata" },
				{ "route", "fallback", "/route", "/route" },
				{ "modalities", List.of("text"), "/modalities", "/modalities" },
				{ "imageConfig", Map.of("aspect_ratio", "1:1"), "/image_config", "/image_config" },
				{ "audio", new OpenRouterAudioOptions("alloy", "pcm16"), "rejected", "rejected" },
				{ "extraBody", Map.of("prompt_cache_key", "synthetic"), "/prompt_cache_key", "/prompt_cache_key",
						"synthetic" },
				{ "providerExtraBody", Map.of("zdr", false), "/provider/zdr", "/provider/zdr", false },
				{ "toolCallbacks", List.of(), "/tools/0/function/name", "/tools/0/name", "get_weather" },
				{ "toolContext", Map.of("test", "value"), null, null },
				{ "stopSequences", List.of("STOP"), "/stop", "rejected" }, { "seed", 0, "/seed", "rejected" },
				{ "repetitionPenalty", 1.0, "/repetition_penalty", "rejected" }, { "minP", 0.0, "/min_p", "rejected" },
				{ "topA", 0.0, "/top_a", "rejected" }, { "includeUsage", false, "/usage/include", "rejected" } };
		return Arrays.stream(rows)
			.map(row -> new OptionRow((String) row[0], row[1], (String) row[2], (String) row[3],
					row.length > 4 ? row[4] : row[1]))
			.toList();
	}

	@Test
	void everyDeclaredOptionHasAContractRow() {
		assertThat(optionRows()).extracting(OptionRow::name)
			.containsExactlyInAnyOrderElementsOf(Arrays.stream(OpenRouterChatOptions.class.getDeclaredFields())
				.filter(field -> !Modifier.isStatic(field.getModifiers()))
				.map(Field::getName)
				.toList());
	}

	// ---------------------------------------------------------------------
	// Responses-mode system message handling
	// ---------------------------------------------------------------------

	@Test
	void joinsMultipleSystemMessagesIntoResponsesInstructions() {
		JsonNode json = serializeResponses(base().build(),
				List.of(new SystemMessage("first"), new SystemMessage("second"), new UserMessage("hi")), List.of());

		assertThat(json.path("instructions").stringValue()).isEqualTo("first\nsecond");
	}

	@Test
	void filtersBlankSystemMessagesFromResponsesInstructions() {
		JsonNode json = serializeResponses(base().build(),
				List.of(new SystemMessage("keep"), new SystemMessage("   "), new UserMessage("hi")), List.of());

		assertThat(json.path("instructions").stringValue()).isEqualTo("keep");
	}

	private record OptionRow(String name, Object value, String chatPath, String responsesPath, Object expected) {

		@Override
		public String toString() {
			return this.name;
		}

		boolean isMapped(String path) {
			return path != null && !"rejected".equals(path);
		}

		// The Java option name must not appear on the wire unless it is also the wire
		// name.
		List<String> camelCaseNamesAbsentFrom(String path) {
			return path.equals("/" + this.name) ? List.of() : List.of(this.name);
		}

	}

	enum Wire {

		CHAT, RESPONSES

	}

}
