package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Resilience tests for the tool-execution loop driving {@link OpenRouterChatModel}
 * through {@code ChatClient} + {@code ToolCallingAdvisor} — the Spring AI 2.0 replacement
 * for in-model tool execution. The happy path lives in
 * {@code OpenRouterChatModelToolCallingTests}; this class owns failure modes, multi-round
 * flow, history accumulation, and the (intentional) absence of a max-iteration guard.
 *
 * <p>
 * These tests drive the loop through the real {@code ToolCallingAdvisor} and
 * {@code ToolCallingManager}, so tool resolution, JSON binding, callback invocation, and
 * this library's request/response mapping behave exactly as they do in production.
 */
class OpenRouterChatModelToolCallingFailureTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private static final String BERLIN_ARGS = "{\"city\":\"Berlin\"}";

	private static final String TOOL_FAILURE_APOLOGY = "Sorry, the tool failed.";

	private final ToolCallback weatherTool = FunctionToolCallback
		.builder("get_weather", (Map<String, Object> in) -> "sunny in " + in.getOrDefault("city", "?"))
		.description("Look up the weather")
		.inputType(Map.class)
		.build();

	private final ToolCallback boomTool = FunctionToolCallback.builder("boom", (Map<String, Object> in) -> {
		throw new IllegalStateException("tool exploded\napi-key=sk-sensitive",
				new IllegalArgumentException("nested secret"));
	}).description("Always fails").inputType(Map.class).build();

	private ChatCompletionResponse toolCallResponse(String toolName, String arguments) {
		return toolCallResponse("call-1", toolName, arguments);
	}

	private ChatCompletionResponse toolCallResponse(String callId, String toolName, String arguments) {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0,
						new ChatMessage("assistant", null, null, null,
								List.of(new ToolCall(callId, "function", new FunctionCall(toolName, arguments)))),
						null, "tool_calls", "tool_calls")),
				null);
	}

	private ChatCompletionResponse finalResponse(String text) {
		return new ChatCompletionResponse("gen-2", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0, new ChatMessage("assistant", text, null, null, null), null, "stop", "stop")),
				null);
	}

	private ChatClient toolCallingClient(OpenRouterApi api) {
		return toolCallingClient(api,
				ToolCallingAdvisor.builder()
					.toolCallingManager(org.springframework.ai.model.tool.ToolCallingManager.builder()
						.toolExecutionExceptionProcessor(new OpenRouterToolExecutionExceptionProcessor())
						.build())
					.build());
	}

	private ChatClient toolCallingClient(OpenRouterApi api, ToolCallingAdvisor advisor) {
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		return ChatClient.builder(model).defaultAdvisors(advisor).build();
	}

	private ChatResponse call(ChatClient client, ToolCallback... tools) {
		return client.prompt(prompt(tools)).call().chatResponse();
	}

	private Prompt prompt(ToolCallback... tools) {
		return new Prompt(List.of(new UserMessage("go")),
				OpenRouterChatOptions.builder().model(MODEL).toolCallbacks(List.of(tools)).build());
	}

	private Prompt responsesPrompt(ToolCallback... tools) {
		return new Prompt(List.of(new UserMessage("go")),
				OpenRouterChatOptions.builder()
					.model(MODEL)
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.toolCallbacks(List.of(tools))
					.build());
	}

	// ---------------------------------------------------------------------
	// Failure modes
	// ---------------------------------------------------------------------

	@Test
	void unknownToolNameSurfacesAsClearError() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("does_not_exist", "{}"));

		ChatClient client = toolCallingClient(api);
		assertThatThrownBy(() -> call(client, this.weatherTool)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("No ToolCallback found")
			.hasMessageContaining("does_not_exist");
	}

	@Test
	void toolCallbackFailureBecomesSafeToolOutputAndLoopContinues() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("boom", "{}"), finalResponse(TOOL_FAILURE_APOLOGY));

		ChatResponse response = call(toolCallingClient(api), this.boomTool);

		assertThat(response.getResult().getOutput().getText()).isEqualTo(TOOL_FAILURE_APOLOGY);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		// The failed-tool result is appended without crossing the provider trust
		// boundary.
		List<ChatMessage> followUp = captor.getAllValues().get(1).messages();
		assertThat(followUp).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo("tool");
			assertThat(message.content()).isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			assertThat(String.valueOf(message.content())).doesNotContain("tool exploded", "sk-sensitive",
					"nested secret", "\n", "IllegalStateException");
		});
	}

	@Test
	void responsesModeUsesTheSameSafeToolFailurePayload() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ResponsesResult toolCall = new ResponsesResult("resp-1", "response", 123L, MODEL, "completed",
				List.of(new ResponsesOutputItem("fc-1", "function_call", "completed", null, null, "call-1", "boom",
						"{}", null)),
				null, null);
		ResponsesResult answer = new ResponsesResult(
				"resp-2", "response", 124L, MODEL, "completed", List.of(new ResponsesOutputItem("msg-1", "message",
						"completed", "assistant", List.of(new ResponsesContent("output_text", TOOL_FAILURE_APOLOGY)))),
				null, null);
		when(api.responses(any())).thenReturn(toolCall, answer);

		ChatResponse response = toolCallingClient(api).prompt(responsesPrompt(this.boomTool)).call().chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo(TOOL_FAILURE_APOLOGY);
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api, times(2)).responses(captor.capture());
		List<?> followUp = (List<?>) captor.getAllValues().get(1).input();
		assertThat(followUp).filteredOn(ResponsesFunctionCallOutput.class::isInstance)
			.singleElement()
			.isInstanceOfSatisfying(ResponsesFunctionCallOutput.class, output -> assertThat(output.output())
				.isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD));
	}

	@Test
	void toolCallbackFailureIsRethrownWhenExecutionProcessorAlwaysThrows() {
		// A caller who installs an always-throwing exception processor gets the original
		// failure instead of silent recovery. Verifies the failure cause is preserved and
		// safe to log.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("boom", "{}"));
		ToolCallingAdvisor advisor = ToolCallingAdvisor.builder()
			.toolCallingManager(org.springframework.ai.model.tool.ToolCallingManager.builder()
				.toolExecutionExceptionProcessor(
						new org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor(true))
				.build())
			.build();

		ChatClient client = toolCallingClient(api, advisor);
		assertThatThrownBy(() -> call(client, this.boomTool)).isInstanceOf(ToolExecutionException.class)
			.hasMessageContaining("tool exploded");
	}

	@Test
	void invalidJsonArgumentsSurfaceAsError() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("get_weather", "{not valid json"));

		ChatClient client = toolCallingClient(api);
		assertThatThrownBy(() -> call(client, this.weatherTool)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Conversion from JSON");
	}

	@Test
	void optingOutOfToolExecutionSurfacesToolCallsWithoutExecuting() {
		// ChatClient auto-registers a ToolCallingAdvisor by default; opting out via
		// AdvisorParams leaves the client behaving like the raw model: the tool call is
		// returned to the caller, the provider is hit exactly once, and the callback
		// never runs. This replaces the removed internalToolExecutionEnabled=false
		// option from the 1.x-style API.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("get_weather", BERLIN_ARGS));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		ChatClient plainClient = ChatClient.builder(model).build();

		ChatResponse response = plainClient.prompt(prompt(this.weatherTool))
			.advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))
			.call()
			.chatResponse();

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
		verify(api, times(1)).chatCompletion(any());
	}

	@Test
	void returnDirectReturnsToolOutputWithoutAnotherModelCall() {
		ToolCallback directTool = FunctionToolCallback.builder("get_weather", (Map<String, Object> in) -> "sunny")
			.description("Look up the weather")
			.inputType(Map.class)
			.toolMetadata(ToolMetadata.builder().returnDirect(true).build())
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("get_weather", BERLIN_ARGS));

		ChatResponse response = call(toolCallingClient(api), directTool);

		// returnDirect short-circuits the loop: tool output is returned, no second call.
		assertThat(response.getResult().getOutput().getText()).contains("sunny");
		verify(api, times(1)).chatCompletion(any());
	}

	// ---------------------------------------------------------------------
	// Multi-round flow and history accumulation
	// ---------------------------------------------------------------------

	@Test
	void twoSequentialToolRoundsBeforeFinalAnswer() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("call-1", "get_weather", BERLIN_ARGS),
				toolCallResponse("call-2", "get_weather", "{\"city\":\"Paris\"}"), finalResponse("Both are sunny."));

		ChatResponse response = call(toolCallingClient(api), this.weatherTool);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Both are sunny.");
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		// Exactly three provider calls: two tool rounds + the final answer.
		verify(api, times(3)).chatCompletion(captor.capture());

		// History grows in order: each follow-up preserves the assistant tool-call
		// message immediately followed by its tool response.
		List<ChatMessage> secondRound = captor.getAllValues().get(1).messages();
		assertThat(secondRound).extracting(ChatMessage::role).containsSequence("assistant", "tool");
		assertThat(secondRound).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo("tool");
			assertThat(message.toolCallId()).isEqualTo("call-1");
			assertThat(message.content()).isEqualTo("\"sunny in Berlin\"");
		});

		List<ChatMessage> finalRound = captor.getAllValues().get(2).messages();
		assertThat(finalRound).filteredOn(m -> "tool".equals(m.role())).hasSize(2);
		assertThat(finalRound).anySatisfy(message -> assertThat(message.toolCallId()).isEqualTo("call-2"));
	}

	// ---------------------------------------------------------------------
	// Runaway-provider safety: documented current behavior
	// ---------------------------------------------------------------------

	@Test
	@Timeout(5)
	void providerThatNeverStopsAskingForToolsLoopsUntilItDoes() {
		// ToolCallingAdvisor has no configurable max-iteration guard today. The loop
		// terminates only when the provider stops returning tool calls. This test pins
		// that behavior: a provider that asks N times then answers drives exactly N+1
		// calls and finishes. (If a guard is ever added, this test should change to
		// assert the guard fires.) The timeout turns an infinite-loop regression into a
		// test failure instead of a CI hang.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("call-1", "get_weather", "{\"city\":\"A\"}"),
				toolCallResponse("call-2", "get_weather", "{\"city\":\"B\"}"),
				toolCallResponse("call-3", "get_weather", "{\"city\":\"C\"}"), finalResponse("Done."));

		ChatResponse response = call(toolCallingClient(api), this.weatherTool);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Done.");
		verify(api, times(4)).chatCompletion(any());
	}

	@Test
	void toolCallWithoutIdExecutesToolAndCompletesTheLoop() {
		// Some providers emit a tool call with a null id. The DTO allows it; the loop
		// uses an empty Spring AI id and restores the missing id on the follow-up
		// wire message, completing normally.
		OpenRouterApi api = mock(OpenRouterApi.class);
		ChatCompletionResponse noId = new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0,
						new ChatMessage("assistant", null, null, null,
								List.of(new ToolCall(null, "function", new FunctionCall("get_weather", BERLIN_ARGS)))),
						null, "tool_calls", "tool_calls")),
				null);
		when(api.chatCompletion(any())).thenReturn(noId, finalResponse("Sunny."));

		ChatResponse response = call(toolCallingClient(api), this.weatherTool);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Sunny.");
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		assertThat(captor.getAllValues().get(1).messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo("tool");
			assertThat(String.valueOf(message.content())).contains("sunny in Berlin");
			assertThat(message.toolCallId()).isNull();
		});
	}

}
