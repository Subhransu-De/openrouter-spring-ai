package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
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

	private final OpenRouterResponsesResponseMapper responseMapper = new OpenRouterResponsesResponseMapper();

	private final OpenRouterResponsesStreamingResponseMapper streamingMapper = new OpenRouterResponsesStreamingResponseMapper();

	private final OpenRouterResponsesRequestMapper requestMapper = new OpenRouterResponsesRequestMapper(
			new ObjectMapper());

	private ResponsesResult result(String status, List<ResponsesOutputItem> output, Usage usage, StreamError error) {
		return new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", status, output, usage, error);
	}

	private ResponsesOutputItem message(String text) {
		return new ResponsesOutputItem("item-msg", "message", "completed", "assistant",
				List.of(new ResponsesContent("output_text", text)));
	}

	private de.subhransu.openrouter.springai.chat.OpenRouterChatOptions options() {
		return de.subhransu.openrouter.springai.chat.OpenRouterChatOptions.builder().model("openai/gpt-5.4").build();
	}

	// ---------------------------------------------------------------------
	// Sync result edge cases
	// ---------------------------------------------------------------------

	@Test
	void concatenatesTextAcrossMultipleMessageOutputItems() {
		ChatResponse mapped = this.responseMapper
			.map(result("completed", List.of(message("Hello "), message("world")), null, null));

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("Hello world");
	}

	@Test
	void ignoresReasoningOutputItemsWhenBuildingText() {
		ResponsesOutputItem reasoning = new ResponsesOutputItem("item-r", "reasoning", "completed", "assistant",
				List.of(new ResponsesContent("reasoning_text", "internal thoughts")));
		ChatResponse mapped = this.responseMapper
			.map(result("completed", List.of(reasoning, message("final")), null, null));

		// Only message-type items contribute to the visible answer.
		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("final");
	}

	@Test
	void mapsFunctionCallOutputItemsToToolCalls() {
		ResponsesOutputItem functionCall = new ResponsesOutputItem("item-fc", "function_call", "completed", null, null,
				"call-1", "get_weather", "{\"city\":\"Berlin\"}", null);
		ChatResponse mapped = this.responseMapper.map(result("completed", List.of(functionCall), null, null));

		assertThat(mapped.hasToolCalls()).isTrue();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(mapped.getResult().getOutput().getToolCalls().get(0).id()).isEqualTo("call-1");
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

		assertThatThrownBy(() -> this.streamingMapper.map(event))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter responses stream failed");
				assertThat(exception.getErrorDetails().message()).isEqualTo("line one line two Bearer [REDACTED]");
				assertThat(exception.getResponseBody()).doesNotContain("\r", "\n", "response-stream-secret");
			});
	}

	@Test
	void incompleteStatusMapsToIncompleteFinishReason() {
		ChatResponse mapped = this.responseMapper.map(result("incomplete", List.of(message("partial")), null, null));

		// "incomplete" is unknown to the finish-reason normalizer, so it passes through.
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("incomplete");
		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("partial");
	}

	@Test
	void unknownOutputItemTypeContributesNoText() {
		ResponsesOutputItem unknown = new ResponsesOutputItem("item-x", "web_search_call", "completed", null,
				List.of(new ResponsesContent("output_text", "should be ignored")), null, null, null, null);
		ChatResponse mapped = this.responseMapper
			.map(result("completed", List.of(unknown, message("kept")), null, null));

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("kept");
	}

	@Test
	void outputItemWithMissingOptionalFieldsDoesNotFail() {
		ResponsesOutputItem sparse = new ResponsesOutputItem(null, "message", null, null, null);
		ChatResponse mapped = this.responseMapper.map(result("completed", List.of(sparse), null, null));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
	}

	@Test
	void nullOutputArrayMapsToEmptyText() {
		ChatResponse mapped = this.responseMapper.map(result("completed", null, null, null));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
	}

	@Test
	void emptyOutputArrayMapsToEmptyText() {
		ChatResponse mapped = this.responseMapper.map(result("completed", List.of(), null, null));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
	}

	// ---------------------------------------------------------------------
	// Stream event variants
	// ---------------------------------------------------------------------

	@Test
	void mapsReasoningTextDeltaUnderReasoningMetadata() throws Exception {
		ChatResponse mapped = this.streamingMapper.map(streamEvent("""
				{ "type": "response.reasoning_text.delta", "delta": "thinking" }
				"""));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getMetadata().<String>get("openrouter.reasoning")).isEqualTo("thinking");
	}

	@Test
	void mapsIncompleteStreamEventToReasonFromDetails() throws Exception {
		ChatResponse mapped = this.streamingMapper.map(streamEvent("""
				{
				  "type": "response.incomplete",
				  "response": {"incomplete_details": {"reason": "max_output_tokens"}}
				}
				"""));

		// max_output_tokens normalizes to LENGTH.
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("LENGTH");
	}

	@Test
	void unknownStreamEventTypeIsANoOp() throws Exception {
		ChatResponse mapped = this.streamingMapper.map(streamEvent("""
				{ "type": "response.some_future_event", "data": 123 }
				"""));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isNull();
		assertThat(mapped.hasToolCalls()).isFalse();
	}

	@Test
	void malformedTerminalPayloadStillEmitsFinishWithoutThrowing() throws Exception {
		// The event's lenient response deserialization swallows a malformed response
		// object so the stream still terminates cleanly; the finish reason from the
		// event type survives, identity/usage do not.
		ChatResponse mapped = this.streamingMapper.map(streamEvent("""
				{
				  "type": "response.completed",
				  "response": "this should be an object not a string"
				}
				"""));

		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		// The response deserialized to null, so no id/model/usage were attached; the
		// metadata id defaults to empty rather than carrying stale identity.
		assertThat(mapped.getMetadata().getId()).isEmpty();
	}

	@Test
	void completedEventExtractsUsageAndIdentity() throws Exception {
		ChatResponse mapped = this.streamingMapper.map(streamEvent("""
				{
				  "type": "response.completed",
				  "response": {
				    "id": "resp-9",
				    "model": "openai/gpt-5.4",
				    "status": "completed",
				    "usage": {"input_tokens": 3, "output_tokens": 4, "total_tokens": 7}
				  }
				}
				"""));

		assertThat(mapped.getMetadata().getId()).isEqualTo("resp-9");
		assertThat(mapped.getMetadata().getUsage().getTotalTokens()).isEqualTo(7);
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

		List<?> input = (List<?>) request.input();
		// user message + one function_call item, no empty assistant text message.
		assertThat(input).hasSize(2);
		assertThat(input.get(1)).isInstanceOf(de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCall.class);
	}

	@Test
	void assistantMessageWithNeitherTextNorToolsContributesNothing() {
		AssistantMessage empty = AssistantMessage.builder().content("").build();
		List<Message> messages = List.of(new UserMessage("hi"), empty);

		ResponsesRequest request = this.requestMapper.map(messages, options(), false, List.of());

		// The empty assistant turn adds no input item; only the user message remains.
		assertThat((List<?>) request.input()).hasSize(1);
	}

	private ResponsesStreamEvent streamEvent(String json) throws Exception {
		return this.objectMapper.readValue(json, ResponsesStreamEvent.class);
	}

}
