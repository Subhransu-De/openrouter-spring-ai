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
import java.util.Map;
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

	private static final String FALLBACK_MODEL = "anthropic/claude-3.5-sonnet";

	private static final String PARALLEL_TOOL_CALLS = "parallel_tool_calls";

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
	// Core sampling and identity options
	// ---------------------------------------------------------------------

	@Test
	void serializesModelAndModelsFallbackList() {
		JsonNode json = serializeChat(base().models(List.of(FALLBACK_MODEL, "openai/gpt-5.4")).build());

		assertThat(json.path("model").stringValue()).isEqualTo("openai/gpt-5.4-mini");
		assertThat(json.path("models").isArray()).isTrue();
		assertThat(json.path("models").get(0).stringValue()).isEqualTo(FALLBACK_MODEL);
		assertThat(json.path("models").get(1).stringValue()).isEqualTo("openai/gpt-5.4");
	}

	@Test
	void serializesSamplingScalarsUnderSnakeCaseNames() {
		JsonNode json = serializeChat(base().temperature(0.7)
			.topP(0.9)
			.topK(40)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.repetitionPenalty(1.1)
			.minP(0.05)
			.topA(0.8)
			.seed(42)
			.maxTokens(256)
			.maxCompletionTokens(512)
			.stopSequences(List.of("STOP", "END"))
			.user("user-7")
			.build());

		assertThat(json.path("temperature").asDouble()).isEqualTo(0.7);
		assertThat(json.path("top_p").asDouble()).isEqualTo(0.9);
		assertThat(json.path("top_k").asInt()).isEqualTo(40);
		assertThat(json.path("frequency_penalty").asDouble()).isEqualTo(0.1);
		assertThat(json.path("presence_penalty").asDouble()).isEqualTo(0.2);
		assertThat(json.path("repetition_penalty").asDouble()).isEqualTo(1.1);
		assertThat(json.path("min_p").asDouble()).isEqualTo(0.05);
		assertThat(json.path("top_a").asDouble()).isEqualTo(0.8);
		assertThat(json.path("seed").asInt()).isEqualTo(42);
		assertThat(json.path("max_tokens").asInt()).isEqualTo(256);
		assertThat(json.path("max_completion_tokens").asInt()).isEqualTo(512);
		assertThat(json.path("stop").get(0).stringValue()).isEqualTo("STOP");
		assertThat(json.path("user").stringValue()).isEqualTo("user-7");
		// Camel-case forms must never leak.
		assertThat(json.has("topP")).isFalse();
		assertThat(json.has("maxTokens")).isFalse();
		assertThat(json.has("frequencyPenalty")).isFalse();
	}

	@Test
	void serializesStreamFlagAndUsageInclude() {
		ChatCompletionRequest streamed = this.chatMapper.map(List.of(new UserMessage("hi")),
				base().includeUsage(true).build(), true, List.of());
		JsonNode json = this.objectMapper.valueToTree(streamed);

		assertThat(json.path("stream").asBoolean()).isTrue();
		assertThat(json.path("usage").path("include").asBoolean()).isTrue();
	}

	// ---------------------------------------------------------------------
	// Response format / structured output
	// ---------------------------------------------------------------------

	@Test
	void serializesExplicitResponseFormat() {
		JsonNode json = serializeChat(base().responseFormat(OpenRouterResponseFormat.jsonObject()).build());

		assertThat(json.path("response_format").path("type").stringValue()).isEqualTo("json_object");
	}

	@Test
	void serializesTypedJsonSchemaResponseFormatWithNameAndStrict() {
		String schema = """
				{
					"type": "object",
					"properties": {
						"answer": {
							"type": "string"
						}
					}
				}
				""";
		JsonNode json = serializeChat(
				base().responseFormat(OpenRouterResponseFormat.jsonSchema("weather", true, schema)).build());

		assertThat(json.path("response_format").path("type").stringValue()).isEqualTo("json_schema");
		assertThat(json.path("response_format").path("json_schema").path("name").stringValue()).isEqualTo("weather");
		assertThat(json.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
		assertThat(json.path("response_format").path("json_schema").path("schema").path("type").stringValue())
			.isEqualTo("object");
	}

	@Test
	void serializesOutputSchemaAsJsonSchemaResponseFormat() {
		String schema = """
				{
					"type": "object",
					"properties": {
						"answer": {
							"type": "string"
						}
					}
				}
				""";
		JsonNode json = serializeChat(base().outputSchema(schema).build());

		assertThat(json.path("response_format").path("type").stringValue()).isEqualTo("json_schema");
		assertThat(json.path("response_format").path("json_schema").path("name").stringValue()).isEqualTo("response");
		assertThat(json.path("response_format").path("json_schema").path("schema").path("type").stringValue())
			.isEqualTo("object");
	}

	@Test
	void explicitResponseFormatTakesPrecedenceOverOutputSchema() {
		JsonNode json = serializeChat(base().responseFormat(OpenRouterResponseFormat.jsonObject()).outputSchema("""
				{
				  "type": "object"
				}
				""").build());

		// When both are present, the explicit responseFormat wins (no json_schema
		// wrapper).
		assertThat(json.path("response_format").path("type").stringValue()).isEqualTo("json_object");
	}

	@Test
	void malformedOutputSchemaThrowsTypedExceptionWithUsefulMessage() {
		assertThatThrownBy(() -> serializeChat(base().outputSchema("{not valid json").build()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
	}

	@Test
	void malformedToolSchemaThrowsTypedException() {
		ToolDefinition badTool = ToolDefinition.builder()
			.name("broken")
			.description("broken schema")
			.inputSchema("{not valid json")
			.build();

		assertThatThrownBy(() -> serializeChat(base().build(), List.of(new UserMessage("hi")), List.of(badTool)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
	}

	// ---------------------------------------------------------------------
	// Tools
	// ---------------------------------------------------------------------

	@Test
	void serializesToolsToolChoiceAndParallelToolCalls() {
		JsonNode json = serializeChat(base().toolChoice(Map.of("type", "auto")).parallelToolCalls(true).build(),
				List.of(new UserMessage("hi")), List.of(weatherTool()));

		assertThat(json.path("tools").get(0).path("type").stringValue()).isEqualTo("function");
		assertThat(json.path("tools").get(0).path("function").path("name").stringValue()).isEqualTo("get_weather");
		assertThat(json.path("tools").get(0).path("function").path("parameters").path("type").stringValue())
			.isEqualTo("object");
		assertThat(json.path("tool_choice").stringValue()).isEqualTo("auto");
		assertThat(json.path(PARALLEL_TOOL_CALLS).asBoolean()).isTrue();
	}

	@Test
	void serializesStringFormToolChoiceVerbatim() {
		// tool_choice accepts a named function object or a bare string
		// ("none"/"auto"/"required"); the string form must not be wrapped or quoted
		// differently.
		JsonNode json = serializeChat(base().toolChoice("none").build());

		assertThat(json.path("tool_choice").isString()).isTrue();
		assertThat(json.path("tool_choice").stringValue()).isEqualTo("none");
	}

	@Test
	void parallelToolCallsFalseIsNotOmitted() {
		// NON_EMPTY would drop an empty collection, but a Boolean.FALSE is meaningful and
		// must survive serialization.
		JsonNode json = serializeChat(base().parallelToolCalls(false).build());

		assertThat(json.has(PARALLEL_TOOL_CALLS)).isTrue();
		assertThat(json.path(PARALLEL_TOOL_CALLS).asBoolean()).isFalse();
	}

	// ---------------------------------------------------------------------
	// Provider routing, reasoning, service tier, metadata, route
	// ---------------------------------------------------------------------

	@Test
	void serializesProviderRoutingPreferences() {
		OpenRouterProviderPreferences provider = new OpenRouterProviderPreferences(true, false, "deny",
				List.of("openai", "anthropic"), List.of("azure"), List.of("fp16", "int8"), "throughput");
		JsonNode json = serializeChat(base().provider(provider).build()).path("provider");

		assertThat(json.path("allow_fallbacks").asBoolean()).isTrue();
		assertThat(json.path("require_parameters").asBoolean()).isFalse();
		assertThat(json.path("data_collection").stringValue()).isEqualTo("deny");
		assertThat(json.path("order").get(0).stringValue()).isEqualTo("openai");
		assertThat(json.path("ignore").get(0).stringValue()).isEqualTo("azure");
		assertThat(json.path("quantizations").get(1).stringValue()).isEqualTo("int8");
		assertThat(json.path("sort").stringValue()).isEqualTo("throughput");
	}

	@Test
	void serializesReasoningEffortFields() {
		JsonNode json = serializeChat(
				base().reasoning(new OpenRouterReasoningOptions("high", null, false, true)).build())
			.path("reasoning");

		assertThat(json.path("effort").stringValue()).isEqualTo("high");
		assertThat(json.has("max_tokens")).isFalse();
		assertThat(json.path("exclude").asBoolean()).isFalse();
		assertThat(json.path("enabled").asBoolean()).isTrue();
	}

	@Test
	void serializesReasoningTokenBudgetWithoutEffort() {
		JsonNode json = serializeChat(base().reasoning(new OpenRouterReasoningOptions(null, 1024, false, true)).build())
			.path("reasoning");

		assertThat(json.path("max_tokens").asInt()).isEqualTo(1024);
		assertThat(json.has("effort")).isFalse();
	}

	@Test
	void serializesServiceTierAsLowercaseWireValue() {
		JsonNode json = serializeChat(base().serviceTier(OpenRouterServiceTier.FLEX).build());

		assertThat(json.path("service_tier").stringValue()).isEqualTo("flex");
	}

	@Test
	void serializesMetadataAndRoute() {
		JsonNode json = serializeChat(base().metadata(Map.of("trace", "abc")).route("fallback").build());

		assertThat(json.path("metadata").path("trace").stringValue()).isEqualTo("abc");
		assertThat(json.path("route").stringValue()).isEqualTo("fallback");
	}

	// ---------------------------------------------------------------------
	// Null omission
	// ---------------------------------------------------------------------

	@Test
	void omitsNullAndUnsetFields() {
		JsonNode json = serializeChat(base().build());

		assertThat(json.has("temperature")).isFalse();
		assertThat(json.has("tools")).isFalse();
		assertThat(json.has("provider")).isFalse();
		assertThat(json.has("reasoning")).isFalse();
		assertThat(json.has("response_format")).isFalse();
		assertThat(json.has("metadata")).isFalse();
		assertThat(json.has("usage")).isFalse();
		assertThat(json.has("service_tier")).isFalse();
	}

	@Test
	void omitsExplicitlyEmptyCollections() {
		// NON_EMPTY must drop empty lists/maps: OpenRouter rejects some empty arrays
		// (e.g. models) and an empty stop array is noise.
		JsonNode json = serializeChat(base().models(List.of()).stopSequences(List.of()).metadata(Map.of()).build());

		assertThat(json.has("models")).isFalse();
		assertThat(json.has("stop")).isFalse();
		assertThat(json.has("metadata")).isFalse();
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
		assertThat(json.get(2).path("tool_calls").get(0).path("function").path("name").stringValue())
			.isEqualTo("get_weather");
		assertThat(json.get(3).path("role").stringValue()).isEqualTo("tool");
		assertThat(json.get(3).path("tool_call_id").stringValue()).isEqualTo("call-1");
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
	void serializesResponsesOptionFieldsUnderSnakeCaseNames() {
		JsonNode json = serializeResponses(base().models(List.of(FALLBACK_MODEL))
			.temperature(0.7)
			.topP(0.9)
			.topK(40)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.maxCompletionTokens(512)
			.metadata(Map.of("trace", "abc"))
			.provider(new OpenRouterProviderPreferences(true, false, "deny", List.of("openai"), List.of("azure"),
					List.of("fp16"), "throughput"))
			.reasoning(new OpenRouterReasoningOptions("high", null, false, true))
			.route("fallback")
			.serviceTier(OpenRouterServiceTier.PRIORITY)
			.user("user-7")
			.parallelToolCalls(true)
			.toolChoice("auto")
			.build(), List.of(new UserMessage("hi")), List.of(weatherTool()));

		assertThat(json.path("model").stringValue()).isEqualTo("openai/gpt-5.4-mini");
		assertThat(json.path("models").get(0).stringValue()).isEqualTo(FALLBACK_MODEL);
		assertThat(json.path("temperature").asDouble()).isEqualTo(0.7);
		assertThat(json.path("top_p").asDouble()).isEqualTo(0.9);
		assertThat(json.path("top_k").asInt()).isEqualTo(40);
		assertThat(json.path("frequency_penalty").asDouble()).isEqualTo(0.1);
		assertThat(json.path("presence_penalty").asDouble()).isEqualTo(0.2);
		assertThat(json.path(MAX_OUTPUT_TOKENS).asInt()).isEqualTo(512);
		assertThat(json.path("metadata").path("trace").stringValue()).isEqualTo("abc");
		assertThat(json.path("provider").path("allow_fallbacks").asBoolean()).isTrue();
		assertThat(json.path("provider").path("data_collection").stringValue()).isEqualTo("deny");
		assertThat(json.path("reasoning").path("effort").stringValue()).isEqualTo("high");
		assertThat(json.path("reasoning").has("max_tokens")).isFalse();
		assertThat(json.path("route").stringValue()).isEqualTo("fallback");
		assertThat(json.path("service_tier").stringValue()).isEqualTo("priority");
		assertThat(json.path("user").stringValue()).isEqualTo("user-7");
		assertThat(json.path(PARALLEL_TOOL_CALLS).asBoolean()).isTrue();
		assertThat(json.path("tool_choice").stringValue()).isEqualTo("auto");
		assertThat(json.path("tools").get(0).path("type").stringValue()).isEqualTo("function");
		assertThat(json.path("tools").get(0).path("name").stringValue()).isEqualTo("get_weather");
		// camelCase forms must never leak onto the responses wire either
		assertThat(json.has("maxOutputTokens")).isFalse();
		assertThat(json.has("topP")).isFalse();
		assertThat(json.has("serviceTier")).isFalse();
		assertThat(json.has("parallelToolCalls")).isFalse();
		assertThat(json.has("toolChoice")).isFalse();
	}

	@Test
	void serializesResponsesReasoningTokenBudgetWithoutEffort() {
		JsonNode json = serializeResponses(
				base().reasoning(new OpenRouterReasoningOptions(null, 1024, false, true)).build(),
				List.of(new UserMessage("hi")), List.of());

		assertThat(json.path("reasoning").path("max_tokens").asInt()).isEqualTo(1024);
		assertThat(json.path("reasoning").has("effort")).isFalse();
	}

	@Test
	void responsesMaxOutputTokensPrefersMaxCompletionTokensOverMaxTokens() {
		JsonNode fallback = serializeResponses(base().maxTokens(256).build(), List.of(new UserMessage("hi")),
				List.of());
		JsonNode preferred = serializeResponses(base().maxTokens(256).maxCompletionTokens(512).build(),
				List.of(new UserMessage("hi")), List.of());

		assertThat(fallback.path(MAX_OUTPUT_TOKENS).asInt()).isEqualTo(256);
		assertThat(preferred.path(MAX_OUTPUT_TOKENS).asInt()).isEqualTo(512);
	}

	@Test
	void omitsNullAndUnsetResponsesFields() {
		JsonNode json = serializeResponses(base().build(), List.of(new UserMessage("hi")), List.of());

		assertThat(json.has("temperature")).isFalse();
		assertThat(json.has("tools")).isFalse();
		assertThat(json.has("provider")).isFalse();
		assertThat(json.has("reasoning")).isFalse();
		assertThat(json.has("metadata")).isFalse();
		assertThat(json.has("service_tier")).isFalse();
		assertThat(json.has(MAX_OUTPUT_TOKENS)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "stopSequences", "seed", "repetitionPenalty", "minP", "topA", "includeUsage" })
	void rejectsUnsupportedResponsesOptions(String name) throws Exception {
		Object value = switch (name) {
			case "stopSequences" -> List.of();
			case "seed" -> 0;
			case "includeUsage" -> false;
			default -> 0.0;
		};
		var builder = base();
		var method = Arrays.stream(builder.getClass().getMethods())
			.filter(candidate -> candidate.getName().equals(name))
			.findFirst()
			.orElseThrow();
		method.invoke(builder, value);
		for (boolean stream : List.of(false, true)) {
			assertThatThrownBy(
					() -> this.responsesMapper.map(List.of(new UserMessage("hi")), builder.build(), stream, List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(name)
				.hasMessageContaining("OPENAI_RESPONSES");
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void structuredFormatsAndNamedToolsArePortable(boolean stream) {
		String schema = "{\"type\":\"object\"}";
		for (Boolean strict : Arrays.asList(null, false, true)) {
			var options = base().outputSchema("invalid but overridden")
				.responseFormat(OpenRouterResponseFormat.jsonSchema("answer", strict, schema))
				.toolChoice(Map.of("type", "function", "function", Map.of("name", "get_weather")))
				.build();
			var messages = List.<Message>of(new UserMessage("hi"));
			JsonNode chat = this.objectMapper
				.valueToTree(this.chatMapper.map(messages, options, stream, List.of(weatherTool())));
			JsonNode responses = this.objectMapper
				.valueToTree(this.responsesMapper.map(messages, options, stream, List.of(weatherTool())));
			JsonNode format = responses.at("/text/format");
			assertThat(format.path("type").stringValue()).isEqualTo("json_schema");
			assertThat(format.path("name").stringValue()).isEqualTo("answer");
			assertThat(format.path("schema")).isEqualTo(this.objectMapper.readTree(schema));
			assertThat(format.path("strict")).isEqualTo(chat.at("/response_format/json_schema/strict"));
			assertThat(format.has("strict")).isEqualTo(strict != null);
			if (strict != null) {
				assertThat(format.path("strict").asBoolean()).isEqualTo(strict);
			}
			assertThat(responses.at("/tool_choice/name").stringValue()).isEqualTo("get_weather");
			assertThat(chat.at("/tool_choice/function/name").stringValue()).isEqualTo("get_weather");
			assertThat(responses.at("/tools/0").has("strict")).isFalse();
			assertThat(chat.at("/tools/0/function").has("strict")).isFalse();
		}
		for (var format : List.of(OpenRouterResponseFormat.text(), OpenRouterResponseFormat.jsonObject())) {
			var options = base().responseFormat(format).build();
			assertThat(serializeResponses(options, List.of(new UserMessage("hi")), List.of()).at("/text/format"))
				.isEqualTo(serializeChat(options).path("response_format"));
		}
		JsonNode portable = serializeResponses(base().outputSchema(schema).build(), List.of(new UserMessage("hi")),
				List.of());
		assertThat(portable.at("/text/format/schema")).isEqualTo(this.objectMapper.readTree(schema));
		assertThat(portable.at("/text/format").has("strict")).isFalse();
		assertThatThrownBy(() -> serializeResponses(base().outputSchema("invalid").build(),
				List.of(new UserMessage("hi")), List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
	}

	@ParameterizedTest
	@ValueSource(strings = { "auto", "none", "required", "{\"type\":\"auto\"}", "{\"type\":\"none\"}",
			"{\"type\":\"required\"}", "{\"type\":\"function\",\"name\":\"get_weather\"}",
			"{\"type\":\"function\",\"function\":{\"name\":\"get_weather\"}}" })
	void acceptsBothNamedToolShapesAndStringChoices(String choice) {
		Object value = choice.startsWith("{") ? this.objectMapper.readTree(choice) : choice;
		var options = base().toolChoice(value).build();
		JsonNode chat = serializeChat(options).path("tool_choice");
		JsonNode responses = serializeResponses(options, List.of(new UserMessage("hi")), List.of()).path("tool_choice");
		if (choice.contains("function")) {
			assertThat(chat.at("/function/name").stringValue()).isEqualTo("get_weather");
			assertThat(responses.path("name").stringValue()).isEqualTo("get_weather");
		}
		else {
			assertThat(chat.stringValue()).isEqualTo(
					choice.startsWith("{") ? this.objectMapper.readTree(choice).path("type").stringValue() : choice);
			assertThat(responses).isEqualTo(chat);
		}
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

	@ParameterizedTest
	@MethodSource("optionContracts")
	void everyOptionIsMappedClientOnlyOrRejected(String name, Object value, String chatPath, String responsesPath)
			throws Exception {
		var builder = OpenRouterChatOptions.builder();
		var method = Arrays.stream(builder.getClass().getMethods())
			.filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount() == 1
					&& candidate.getParameterTypes()[0].isInstance(value))
			.findFirst()
			.orElseThrow();
		method.invoke(builder, value);
		var options = builder.build();
		var messages = List.<Message>of(new UserMessage("hi"));
		var tools = "toolCallbacks".equals(name) || "toolStrict".equals(name) ? List.of(weatherTool())
				: List.<ToolDefinition>of();
		for (boolean stream : List.of(false, true)) {
			JsonNode chat = this.objectMapper.valueToTree(this.chatMapper.map(messages, options, stream, tools));
			if (chatPath != null) {
				assertThat(chat.at(chatPath).isMissingNode()).as(name).isFalse();
			}
			else {
				assertThat(chat.has(name)).isFalse();
			}
			if ("rejected".equals(responsesPath)) {
				assertThatThrownBy(() -> this.responsesMapper.map(messages, options, stream, tools))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(name);
			}
			else {
				JsonNode responses = this.objectMapper
					.valueToTree(this.responsesMapper.map(messages, options, stream, tools));
				if (responsesPath != null) {
					assertThat(responses.at(responsesPath)).as(name).isEqualTo(chat.at(chatPath));
				}
				else {
					assertThat(responses.has(name)).isFalse();
				}
			}
		}
	}

	static Stream<Arguments> optionContracts() {
		Object[][] rows = { { "model", "test/model", "/model", "/model" },
				{ "models", List.of("test/fallback"), "/models", "/models" },
				{ "requestMode", OpenRouterRequestMode.OPENAI_RESPONSES, null, null },
				{ "frequencyPenalty", 0.0, "/frequency_penalty", "/frequency_penalty" },
				{ "maxTokens", 12, "/max_tokens", "/max_output_tokens" },
				{ "maxCompletionTokens", 24, "/max_completion_tokens", "/max_output_tokens" },
				{ "presencePenalty", 0.0, "/presence_penalty", "/presence_penalty" },
				{ "temperature", 0.0, "/temperature", "/temperature" }, { "topK", 10, "/top_k", "/top_k" },
				{ "topP", 0.5, "/top_p", "/top_p" }, { "user", "synthetic-user", "/user", "/user" },
				{ "responseFormat", OpenRouterResponseFormat.jsonObject(), "/response_format", "/text/format" },
				{ "outputSchema", "{\"type\":\"object\"}", "/response_format/json_schema/schema",
						"/text/format/schema" },
				{ "parallelToolCalls", false, "/parallel_tool_calls", "/parallel_tool_calls" },
				{ "toolChoice", "required", "/tool_choice", "/tool_choice" },
				{ "toolStrict", false, "/tools/0/function/strict", "/tools/0/strict" },
				{ "provider", new OpenRouterProviderPreferences(true, null, null, null, null, null, null), "/provider",
						"/provider" },
				{ "reasoning", new OpenRouterReasoningOptions("high", null, null, null), "/reasoning", "/reasoning" },
				{ "serviceTier", OpenRouterServiceTier.FLEX, "/service_tier", "/service_tier" },
				{ "metadata", Map.of("test", "value"), "/metadata", "/metadata" },
				{ "route", "fallback", "/route", "/route" },
				{ "modalities", List.of("text"), "/modalities", "/modalities" },
				{ "imageConfig", Map.of("aspect_ratio", "1:1"), "/image_config", "/image_config" },
				{ "toolCallbacks", List.of(), "/tools/0/function/name", "/tools/0/name" },
				{ "toolContext", Map.of("test", "value"), null, null },
				{ "stopSequences", List.of("STOP"), "/stop", "rejected" }, { "seed", 0, "/seed", "rejected" },
				{ "repetitionPenalty", 1.0, "/repetition_penalty", "rejected" }, { "minP", 0.0, "/min_p", "rejected" },
				{ "topA", 0.0, "/top_a", "rejected" }, { "includeUsage", false, "/usage/include", "rejected" } };
		assertThat(Arrays.stream(OpenRouterChatOptions.class.getDeclaredFields())
			.filter(field -> !Modifier.isStatic(field.getModifiers()))
			.map(Field::getName))
			.containsExactlyInAnyOrder(Arrays.stream(rows).map(row -> (String) row[0]).toArray(String[]::new));
		return Arrays.stream(rows).map(Arguments::of);
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

	@Test
	void serializesNonEnglishContent() {
		JsonNode json = serializeChat(base().build(), List.of(new UserMessage("こんにちは世界 🌍 Köln")), List.of());

		assertThat(json.path("messages").get(0).path("content").stringValue()).isEqualTo("こんにちは世界 🌍 Köln");
	}

}
