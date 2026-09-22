package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesInputMessage;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.definition.ToolDefinition;

class OpenRouterResponsesMapperTests {

	private static final String WEATHER_ARGUMENTS = "{\"city\":\"Berlin\"}";

	@Test
	void mapsTextPromptToResponsesRequest() {
		ResponsesRequest request = new OpenRouterResponsesRequestMapper(new ObjectMapper()).map(
				List.of(new SystemMessage("system"), new UserMessage("hello"), new AssistantMessage("hi")),
				OpenRouterChatOptions.builder()
					.model("openai/gpt-5.4")
					.maxCompletionTokens(64)
					.temperature(0.2)
					.build(),
				true, List.of());

		assertThat(request.model()).isEqualTo("openai/gpt-5.4");
		assertThat(request.instructions()).isEqualTo("system");
		assertThat(request.maxOutputTokens()).isEqualTo(64);
		assertThat((List<?>) request.input()).hasSize(2);
		assertThat(((ResponsesInputMessage) ((List<?>) request.input()).get(0)).content().get(0).type())
			.isEqualTo("input_text");
		assertThat(((ResponsesOutputItem) ((List<?>) request.input()).get(1)).content().get(0).type())
			.isEqualTo("output_text");
		assertThat(request.stream()).isTrue();
	}

	@Test
	void mapsToolsAndToolExchangeIntoResponsesRequest() {
		ToolDefinition weather = ToolDefinition.builder()
			.name("get_weather")
			.description("Look up the weather")
			.inputSchema("""
					{
					  "type": "object",
					  "properties": {
					    "city": {
					      "type": "string"
					    }
					  }
					}
					""")
			.build();
		AssistantMessage assistantToolCall = AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "get_weather", WEATHER_ARGUMENTS)))
			.build();
		ToolResponseMessage toolResult = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "get_weather", "\"sunny\"")))
			.build();

		ResponsesRequest request = new OpenRouterResponsesRequestMapper(new ObjectMapper()).map(
				List.of(new UserMessage("weather in Berlin?"), assistantToolCall, toolResult),
				OpenRouterChatOptions.builder().model("openai/gpt-5.4").build(), false, List.of(weather));

		assertThat(request.tools()).hasSize(1);
		assertThat(request.tools().get(0).name()).isEqualTo("get_weather");
		assertThat(request.tools().get(0).parameters().path("type").stringValue()).isEqualTo("object");
		List<?> input = (List<?>) request.input();
		assertThat(input).hasSize(3);
		ResponsesFunctionCall functionCall = (ResponsesFunctionCall) input.get(1);
		assertThat(functionCall.callId()).isEqualTo("call-1");
		assertThat(functionCall.name()).isEqualTo("get_weather");
		ResponsesFunctionCallOutput output = (ResponsesFunctionCallOutput) input.get(2);
		assertThat(output.id()).isEqualTo("fc_output_call-1");
		assertThat(output.callId()).isEqualTo("call-1");
		assertThat(output.output()).isEqualTo("\"sunny\"");
	}

	@Test
	void forwardsModalitiesAndImageConfigToResponsesRequest() {
		ResponsesRequest request = new OpenRouterResponsesRequestMapper(new ObjectMapper()).map(
				List.of(new UserMessage("draw a red panda")),
				OpenRouterChatOptions.builder()
					.model("google/gemini-2.5-flash-image")
					.modalities(List.of("image", "text"))
					.imageConfig(Map.of("aspect_ratio", "16:9"))
					.build(),
				false, List.of());

		assertThat(request.modalities()).containsExactly("image", "text");
		assertThat(request.imageConfig()).containsEntry("aspect_ratio", "16:9");
	}

	@Test
	void mapsImageGenerationCallOutputItemsToMedia() {
		ResponsesResult result = new ResponsesResult("resp-1", "response", 123L, "google/gemini-2.5-flash-image",
				"completed",
				List.of(new ResponsesOutputItem("item-img", "image_generation_call", "completed", null, null, null,
						null, null, "aW1hZ2Ux"),
						new ResponsesOutputItem("item-1", "message", "completed", "assistant",
								List.of(new ResponsesContent("output_text", "here you go")))),
				null, null);

		ChatResponse mapped = new OpenRouterResponsesResponseMapper().map(result);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("here you go");
		assertThat(mapped.getResult().getOutput().getMedia()).hasSize(1);
		assertThat(mapped.getResult().getOutput().getMedia().get(0).getData())
			.isEqualTo("data:image/png;base64,aW1hZ2Ux");
	}

	@Test
	void mapsCompletedImageGenerationCallItemsFromResponsesStream() throws Exception {
		ChatResponse mapped = new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.output_item.done",
				  "item": {
				    "type": "image_generation_call",
				    "id": "ig-1",
				    "status": "completed",
				    "result": "aW1hZ2Ux"
				  }
				}
				"""));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getOutput().getMedia()).hasSize(1);
		assertThat(mapped.getResult().getOutput().getMedia().get(0).getData())
			.isEqualTo("data:image/png;base64,aW1hZ2Ux");
	}

	@Test
	void mapsFunctionCallOutputItemsToToolCalls() {
		ResponsesResult result = new ResponsesResult(
				"resp-1", "response", 123L, "openai/gpt-5.4", "completed", List.of(new ResponsesOutputItem("item-1",
						"function_call", "completed", null, null, "call-1", "get_weather", WEATHER_ARGUMENTS, null)),
				null, null);

		ChatResponse mapped = new OpenRouterResponsesResponseMapper().map(result);

		assertThat(mapped.hasToolCalls()).isTrue();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		AssistantMessage.ToolCall toolCall = mapped.getResult().getOutput().getToolCalls().get(0);
		assertThat(toolCall.id()).isEqualTo("call-1");
		assertThat(toolCall.name()).isEqualTo("get_weather");
		assertThat(toolCall.arguments()).isEqualTo(WEATHER_ARGUMENTS);
	}

	@Test
	void mapsResponsesResultToChatResponse() {
		ResponsesResult result = new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", "completed",
				List.of(new ResponsesOutputItem("item-1", "message", "completed", "assistant",
						List.of(new ResponsesContent("output_text", "hello"),
								new ResponsesContent("output_text", " world")))),
				new Usage(4, 2, 6, null, null, null, null, null, null), null);

		ChatResponse mapped = new OpenRouterResponsesResponseMapper().map(result);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hello world");
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(mapped.getMetadata().getId()).isEqualTo("resp-1");
		assertThat(mapped.getMetadata().getUsage().getTotalTokens()).isEqualTo(6);
	}

	@Test
	void mapsResponsesTextDeltaStreamEvent() throws Exception {
		ChatResponse mapped = new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.output_text.delta",
				  "delta": "hello"
				}
				"""));

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hello");
	}

	@Test
	void doesNotRepeatStreamedTextOnTerminalEvents() throws Exception {
		// output_text.done and output_item.done carry the full text already sent as
		// deltas;
		// re-emitting it would duplicate the streamed output.
		String outputTextDone = """
				{
				  "type": "response.output_text.done",
				  "text": "already streamed"
				}
				""";
		String outputItemDone = """
				{
				  "type": "response.output_item.done",
				  "item": {
				    "type": "message",
				    "content": [{"type": "output_text", "text": "already streamed"}]
				  }
				}
				""";
		OpenRouterResponsesStreamingResponseMapper mapper = new OpenRouterResponsesStreamingResponseMapper();

		assertThat(mapper.map(streamEvent(outputTextDone)).getResult().getOutput().getText()).isEmpty();
		assertThat(mapper.map(streamEvent(outputItemDone)).getResult().getOutput().getText()).isEmpty();
	}

	@Test
	void mapsResponsesCompletedEventToFinishReason() throws Exception {
		ChatResponse mapped = new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.completed",
				  "response": {"status": "completed", "output": []}
				}
				"""));

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@Test
	void doesNotExposeFunctionCallBeforeTerminalResponse() throws Exception {
		ChatResponse mapped = new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.output_item.done",
				  "item": {
				    "type": "function_call",
				    "id": "fc-1",
				    "call_id": "call-1",
				    "name": "get_weather",
				    "arguments": "{\\"city\\":\\"Berlin\\"}"
				  }
				}
				"""));

		assertThat(mapped.hasToolCalls()).isFalse();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isNull();
	}

	@Test
	void mapsUsageAndIdentityFromResponsesCompletedEvent() throws Exception {
		ChatResponse mapped = new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.completed",
				  "response": {
				    "id": "resp-1",
				    "model": "openai/gpt-5.4",
				    "status": "completed",
				    "output": [],
				    "usage": {
				      "input_tokens": 4,
				      "output_tokens": 2,
				      "total_tokens": 6
				    }
				  }
				}
				"""));

		assertThat(mapped.getMetadata().getId()).isEqualTo("resp-1");
		assertThat(mapped.getMetadata().getModel()).isEqualTo("openai/gpt-5.4");
		assertThat(mapped.getMetadata().getUsage().getTotalTokens()).isEqualTo(6);
	}

	@Test
	void throwsOnResponsesFailedStreamEvent() throws Exception {
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.failed",
				  "response": {
				    "status": "failed",
				    "error": {"code": "server_error", "message": "provider down"},
				    "error_type": "provider_unavailable"
				  }
				}
				"""))).isInstanceOfSatisfying(OpenRouterTransientApiException.class, exception -> {
			assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
			assertThat(exception.getErrorDetails().errorType()).isEqualTo("provider_unavailable");
		}).hasMessage("OpenRouter responses stream failed");
	}

	@Test
	void failedResponsesStreamKeepsTaxonomyWithNonStringMessage() throws Exception {
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.failed",
				  "response": {
				    "status": "failed",
				    "error": {"code": "invalid_api_key", "message": {"detail": "invalid key"}},
				    "error_type": "authentication"
				  }
				}
				"""))).isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, exception -> {
			assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
			assertThat(exception.getErrorDetails().message()).isEqualTo("{\"detail\":\"invalid key\"}");
		});
	}

	@Test
	void failedResponsesStreamPreservesNonStringRootErrorType() throws Exception {
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.failed",
				  "response": {
				    "status": "failed",
				    "error": {"code": "invalid_api_key", "message": "invalid key"},
				    "error_type": {"kind": "authentication"}
				  }
				}
				"""))).isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, exception -> {
			assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
			assertThat(exception.getErrorDetails().code()).isEqualTo("invalid_api_key");
			assertThat(exception.getErrorDetails().errorType()).isEqualTo("{\"kind\":\"authentication\"}");
		});
	}

	@Test
	void failedResponsesResultPreservesNonStringRootErrorType() throws Exception {
		ResponsesResult result = new ObjectMapper().readValue("""
				{"status":"failed","error":{"code":"invalid_api_key","message":"invalid key"},
				 "error_type":{"kind":"authentication"}}
				""", ResponsesResult.class);

		assertThatThrownBy(() -> new OpenRouterResponsesResponseMapper().map(result))
			.isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, exception -> {
				assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
				assertThat(exception.getErrorDetails().code()).isEqualTo("invalid_api_key");
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("{\"kind\":\"authentication\"}");
			});
	}

	@Test
	void throwsOnFailedResponsesResult() {
		ResponsesResult result = new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", "failed", List.of(),
				null, new StreamError("server_error", "add credits"), null, "payment_required");

		assertThatThrownBy(() -> new OpenRouterResponsesResponseMapper().map(result))
			.isInstanceOfSatisfying(OpenRouterNonTransientApiException.class,
					exception -> assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.BILLING_CREDITS))
			.hasMessage("OpenRouter responses request failed");
	}

	@Test
	void throwsOnResponsesStreamErrorEvents() throws Exception {
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{
				  "type": "response.failed.error",
				  "error": {"message": "boom"}
				}
				"""))).isInstanceOf(OpenRouterNonTransientApiException.class)
			.hasMessage("OpenRouter responses stream failed");
	}

	@Test
	void eventLevelResponsesStreamErrorPreservesTypedDetails() throws Exception {
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper().map(streamEvent("""
				{"type":"error","error":{"code":"server_error","message":"invalid key",
				 "metadata":{"provider_code":"nested_code","upstream_request_id":"req-1"}},
				 "error_type":"authentication","metadata":{"provider_code":"bad_key","trace":"abc"}}
				"""))).isInstanceOfSatisfying(OpenRouterNonTransientApiException.class, exception -> {
			assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
			assertThat(exception.getErrorDetails().code()).isEqualTo("server_error");
			assertThat(exception.getErrorDetails().message()).isEqualTo("invalid key");
			assertThat(exception.getErrorDetails().errorType()).isEqualTo("authentication");
			assertThat(exception.getErrorDetails().providerCode()).isEqualTo("bad_key");
			assertThat(exception.getErrorDetails().metadata().get("trace").stringValue()).isEqualTo("abc");
			assertThat(exception.getErrorDetails().metadata().get("upstream_request_id").stringValue())
				.isEqualTo("req-1");
		}).hasMessage("OpenRouter responses stream failed");
	}

	private static ResponsesStreamEvent streamEvent(String json) throws Exception {
		return new ObjectMapper().readValue(json, ResponsesStreamEvent.class);
	}

}
