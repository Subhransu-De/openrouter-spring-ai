package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Edge-case coverage for the beta Responses API mode: sync result shapes (multiple output
 * items, reasoning, function calls, failed/incomplete status, unknown item types, empty
 * output), stream event variants, and request-mapper message handling. Generic
 * chat-completions fixture work lives in the chat-completions fixture test class. The
 * baseline happy paths stay in {@code OpenRouterResponsesMapperTests}.
 */
class OpenRouterResponsesEdgeCaseMapperTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenRouterResponsesResponseMapper responseMapper = ResponseMappers.responses();

	private final OpenRouterResponsesStreamingResponseMapper streamingMapper = ResponseMappers.responsesStreaming();

	private final OpenRouterResponsesRequestMapper requestMapper = new OpenRouterResponsesRequestMapper(
			new ObjectMapper());

	private ResponsesResult result(String status, List<ResponsesOutputItem> output, Usage usage, StreamError error) {
		return new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", status, output, usage, error);
	}

	private static ResponsesOutputItem message(String text) {
		return new ResponsesOutputItem("item-msg", "message", "completed", "assistant",
				List.of(new ResponsesContent("output_text", text)));
	}

	private de.subhransu.openrouter.springai.chat.OpenRouterChatOptions options() {
		return de.subhransu.openrouter.springai.chat.OpenRouterChatOptions.builder().model("openai/gpt-5.4").build();
	}

	static Stream<Arguments> malformedOutputs() {
		return Stream.of(Arguments.of(Named.of("null output", "null"), "OpenRouter response requires Responses output"),
				Arguments.of(Named.of("null output item", "[null]"), "OpenRouter response requires output item"),
				Arguments.of(Named.of("null message content part", "[{\"type\":\"message\",\"content\":[null]}]"),
						"OpenRouter response requires message content"),
				Arguments.of(
						Named.of("function call without an id",
								"[{\"type\":\"function_call\",\"name\":\"lookup\",\"arguments\":\"{}\"}]"),
						"OpenRouter response requires a nonblank tool call id"),
				Arguments.of(Named.of("function call with an empty name",
						"[{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"\",\"arguments\":\"{\\\"job\\\":\\\"a\\\"}{\\\"job\\\":\\\"b\\\"}\"}]"),
						"OpenRouter response requires a nonblank tool call name"),
				Arguments.of(Named.of("function call with a blank id",
						"[{\"type\":\"function_call\",\"call_id\":\" \",\"name\":\"lookup\",\"arguments\":\"{}\"}]"),
						"OpenRouter response requires a nonblank tool call id"));
	}

	@ParameterizedTest
	@MethodSource("malformedOutputs")
	void malformedOutputFailsConsistentlyInSyncAndStreaming(String output, String message) {
		String json = "{\"status\":\"completed\",\"output\":" + output + "}";
		ResponsesResult response = this.objectMapper.readValue(json, ResponsesResult.class);
		assertThatThrownBy(() -> this.responseMapper.map(response)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(message);
		ResponsesStreamEvent event = this.objectMapper
			.readValue("{\"type\":\"response.completed\",\"response\":" + json + "}", ResponsesStreamEvent.class);
		StepVerifier.create(this.streamingMapper.map(Flux.just(event)))
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class).hasMessage(message))
			.verify();
	}

	@Test
	void failedStatusRaisesApiExceptionWithStructuredError() {
		assertThatThrownBy(() -> this.responseMapper.map(result("failed", List.of(), null,
				new StreamError("server_error", "model crashed\r\nforged\u001B[31m Bearer response-secret"))))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter responses request failed");
				assertThat(exception.getErrorDetails().code()).isEqualTo("server_error");
				assertThat(exception.getStatusCode().value()).isEqualTo(500);
				assertThat(exception.getEndpoint()).isEqualTo("/responses");
				assertThat(exception.getRetryAfter()).isNull();
				assertThat(exception.getErrorDetails().message())
					.isEqualTo("model crashed forged [31m Bearer [REDACTED]");
				assertThat(exception.getResponseBody()).doesNotContain("\r", "\n", "\u001B", "response-secret");
			});
	}

	@Test
	void streamErrorEventUsesTheSameDiagnosticPolicy() throws Exception {
		ResponsesStreamEvent event = streamEvent("""
				{ "type": "response.failed.error",
				  "error": {"code":"server_error","message":"line one\\r\\nline two Bearer response-stream-secret"} }
				""");

		assertThatThrownBy(() -> ResponseMappers.responsesEvent(event))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter responses stream failed");
				assertThat(exception.getErrorDetails().message()).isEqualTo("line one line two Bearer [REDACTED]");
				assertThat(exception.getResponseBody()).doesNotContain("\r", "\n", "response-stream-secret");
			});
	}

	// ---------------------------------------------------------------------
	// Request mapper message handling
	// ---------------------------------------------------------------------

	@Test
	void allSystemPromptProducesInstructionsAndEmptyInput() {
		ResponsesRequest request = this.requestMapper.map(List.of(new SystemMessage("only system")), options(), false,
				List.of());

		assertThat(request.instructions()).isEqualTo("only system");
		assertThat((List<?>) request.input()).isEmpty();
	}

	@Test
	void assistantMessageWithToolCallsOnlyMapsToFunctionCallItem() {
		AssistantMessage toolOnly = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{}")))
			.build();
		ResponsesRequest request = this.requestMapper.map(List.of(new UserMessage("hi"), toolOnly), options(), false,
				List.of());

		// user message + one function_call item, no empty assistant text message.
		JsonNode input = this.objectMapper.valueToTree(request.input());
		assertThat(input).hasSize(2);
		assertThat(input.get(1).propertyNames()).containsExactlyInAnyOrder("type", "call_id", "name", "arguments");
		assertThat(input.at("/1/type").asString()).isEqualTo("function_call");
		assertThat(input.at("/1/call_id").asString()).isEqualTo("call-1");
		assertThat(input.at("/1/name").asString()).isEqualTo("get_weather");
		assertThat(input.at("/1/arguments").asString()).isEqualTo("{}");
	}

	@Test
	void conversationWithToolRoundTripMapsEveryMessageKindToItsInputShape() {
		AssistantMessage call = AssistantMessage.builder()
			.content("checking")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", "{\"city\":\"x\"}")))
			.build();
		ToolResponseMessage result = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "sunny")))
			.build();
		List<Message> messages = List.of(new SystemMessage("be brief"), new UserMessage("weather?"), call, result,
				new AssistantMessage("done"));

		ResponsesRequest request = this.requestMapper.map(messages, options(), false, List.of());

		JsonNode input = this.objectMapper.valueToTree(request.input());
		assertThat(request.instructions()).isEqualTo("be brief");
		assertThat(input).isEqualTo(this.objectMapper.readTree("""
				[{"type":"message","role":"user","content":[{"type":"input_text","text":"weather?"}]},
				 {"type":"message","status":"completed","role":"assistant",
				  "content":[{"type":"output_text","text":"checking"}]},
				 {"type":"function_call","call_id":"call-1","name":"get_weather","arguments":"{\\"city\\":\\"x\\"}"},
				 {"id":"fc_output_call-1","type":"function_call_output","call_id":"call-1","output":"sunny"},
				 {"type":"message","status":"completed","role":"assistant",
				  "content":[{"type":"output_text","text":"done"}]}]
				"""));
	}

	static Stream<Arguments> outputShapes() {
		var reasoning = new ResponsesOutputItem("item-r", "reasoning", "completed", "assistant",
				List.of(new ResponsesContent("reasoning_text", "internal thoughts")));
		var unknown = new ResponsesOutputItem("item-x", "web_search_call", "completed", null,
				List.of(new ResponsesContent("output_text", "should be ignored")), null, null, null, null);
		var sparse = new ResponsesOutputItem(null, "message", null, null, null);
		return Stream.of(
				Arguments.of(
						Named.of("text across several message items", List.of(message("Hello "), message("world"))),
						"Hello world", null),
				Arguments.of(Named.of("reasoning item", List.of(reasoning, message("final"))), "final",
						"internal thoughts"),
				Arguments.of(Named.of("unknown item type", List.of(unknown, message("kept"))), "kept", null),
				Arguments.of(Named.of("message item without optional fields", List.of(sparse)), "", null),
				Arguments.of(Named.of("empty output", List.of()), "", null));
	}

	@ParameterizedTest
	@MethodSource("outputShapes")
	void outputItemsMapToVisibleTextAndReasoningMetadata(List<ResponsesOutputItem> output, String text,
			String reasoning) {
		ChatResponse mapped = this.responseMapper.map(result("completed", output, null, null));

		assertThat(mapped.getResults()).singleElement().satisfies(generation -> {
			assertThat(generation.getOutput().getText()).isEqualTo(text);
			assertThat(generation.getOutput().getMetadata().get("openrouter.reasoning")).isEqualTo(reasoning);
			assertThat(generation.getMetadata().getFinishReason()).isEqualTo("STOP");
		});
		assertThat(mapped.hasToolCalls()).isFalse();
	}

	static Stream<Arguments> singleStreamEvents() {
		return Stream.of(Arguments.of(Named.of("reasoning text delta", """
				{ "type": "response.reasoning_text.delta", "delta": "thinking" }
				"""), "thinking", null),
				Arguments.of(
						Named.of("incomplete event with a reason",
								"""
										{
										  "type": "response.incomplete",
										  "response": {"status": "incomplete", "output": [], "incomplete_details": {"reason": "max_output_tokens"}}
										}
										"""),
						null, "LENGTH"));
	}

	@ParameterizedTest
	@MethodSource("singleStreamEvents")
	void singleStreamEventsMapToReasoningMetadataAndFinishReason(String event, String reasoning, String finishReason)
			throws Exception {
		ChatResponse mapped = ResponseMappers.responsesEvent(streamEvent(event));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getMetadata().<String>get("openrouter.reasoning")).isEqualTo(reasoning);
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo(finishReason);
	}

	@Test
	void assistantMessageWithNeitherTextNorToolsContributesNothing() {
		AssistantMessage empty = AssistantMessage.builder().content("").build();
		List<Message> messages = List.of(new UserMessage("hi"), empty);

		ResponsesRequest request = this.requestMapper.map(messages, options(), false, List.of());

		// The empty assistant turn adds no input item; only the user message remains.
		JsonNode input = this.objectMapper.valueToTree(request.input());
		assertThat(input).hasSize(1);
		assertThat(input.at("/0/role").asString()).isEqualTo("user");
	}

	private ResponsesStreamEvent streamEvent(String json) throws Exception {
		return this.objectMapper.readValue(json, ResponsesStreamEvent.class);
	}

}
