package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

/**
 * Tool-calling contract of {@link OpenRouterChatModel} under Spring AI 2.0: the model
 * advertises tool definitions to the provider and surfaces tool calls without executing
 * them; the tool-execution loop is owned by {@code ToolCallingAdvisor} on a
 * {@code ChatClient}. Failure modes and multi-round flow live in
 * {@code OpenRouterChatModelToolCallingFailureTests}.
 */
class OpenRouterChatModelToolCallingTests {

	private static final String BERLIN_ARGS = "{\"city\":\"Berlin\"}";

	private static final String WEATHER_PROMPT = "weather in Berlin?";

	private static final String MINI_MODEL = "openai/gpt-5.4-mini";

	private final AtomicBoolean toolInvoked = new AtomicBoolean(false);

	private final ToolCallback weatherTool = FunctionToolCallback
		.builder("get_weather", (Map<String, Object> input) -> {
			this.toolInvoked.set(true);
			return "sunny";
		})
		.description("Look up the weather")
		.inputType(Map.class)
		.inputSchema(
				"""
						{"type":"object","properties":{"city":{"type":"string"}},"required":["city"],"additionalProperties":false}
						""")
		.build();

	private ChatClient toolCallingClient(OpenRouterChatModel model) {
		return ChatClient.builder(model).defaultAdvisors(ToolCallingAdvisor.builder().build()).build();
	}

	@Test
	void clientCombinesDefaultAndRequestToolsAndExecutesDefaultTool() {
		ToolCallback requestTool = FunctionToolCallback.builder("get_time", (Map<String, Object> input) -> "12:00")
			.description("Current time")
			.inputType(Map.class)
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(
				chatCompletionResponse(new ChatMessage("assistant", null, null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", BERLIN_ARGS)))),
						"tool_calls"),
				chatCompletionResponse(new ChatMessage("assistant", "It is sunny.", null, null, null), "stop"));
		var client = ChatClient.builder(OpenRouterChatModel.builder().openRouterApi(api).build())
			.defaultAdvisors(ToolCallingAdvisor.builder().build())
			.defaultOptions(OpenRouterChatOptions.builder().model("synthetic-model").toolCallbacks(this.weatherTool))
			.build();
		// prompt(Prompt) combines builders; options(builder) replaces the builder.
		var request = client
			.prompt(new Prompt(WEATHER_PROMPT, OpenRouterChatOptions.builder().toolCallbacks(requestTool).build()));

		assertThat(request.call().content()).isEqualTo("It is sunny.");
		assertThat(this.toolInvoked).isTrue();
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		assertThat(captor.getAllValues())
			.allSatisfy(sent -> assertThat(sent.tools()).extracting(tool -> tool.function().name())
				.containsExactly("get_weather", "get_time"));
		assertThat(captor.getAllValues().get(1).messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo("tool");
			assertThat(message.content()).isEqualTo("\"sunny\"");
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void advisorExecutesToolsAndSendsResultsBackInChatCompletionsMode(boolean strict) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(
				chatCompletionResponse(new ChatMessage("assistant", null, null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", BERLIN_ARGS)))),
						"tool_calls"),
				chatCompletionResponse(new ChatMessage("assistant", "It is sunny.", null, null, null), "stop"));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		ChatResponse response = toolCallingClient(model)
			.prompt(new Prompt(List.of(new UserMessage(WEATHER_PROMPT)),
					OpenRouterChatOptions.builder()
						.model(MINI_MODEL)
						.toolStrict(strict)
						.toolCallbacks(List.of(this.weatherTool))
						.build()))
			.call()
			.chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("It is sunny.");
		assertThat(this.toolInvoked).isTrue();
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		ChatCompletionRequest first = captor.getAllValues().get(0);
		assertThat(first.tools()).hasSize(1);
		assertThat(first.tools().get(0).function().name()).isEqualTo("get_weather");
		assertThat(captor.getAllValues())
			.allSatisfy(request -> assertThat(request.tools().get(0).function().strict()).isEqualTo(strict));
		ChatCompletionRequest second = captor.getAllValues().get(1);
		assertThat(second.tools()).hasSize(1);
		assertThat(second.messages()).anySatisfy(message -> {
			assertThat(message.role()).isEqualTo("tool");
			assertThat(message.content()).isEqualTo("\"sunny\"");
		});
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void advisorExecutesToolsAndSendsResultsBackInResponsesMode(boolean strict) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any())).thenReturn(
				responsesResult(new ResponsesOutputItem("item-1", "function_call", "completed", null, null, "call-1",
						"get_weather", BERLIN_ARGS, null)),
				responsesResult(new ResponsesOutputItem("item-2", "message", "completed", "assistant",
						List.of(new ResponsesContent("output_text", "It is sunny.")))));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		ChatResponse response = toolCallingClient(model)
			.prompt(new Prompt(List.of(new UserMessage(WEATHER_PROMPT)),
					OpenRouterChatOptions.builder()
						.model("openai/gpt-5.4")
						.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
						.toolStrict(strict)
						.toolCallbacks(List.of(this.weatherTool))
						.build()))
			.call()
			.chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo("It is sunny.");
		assertThat(this.toolInvoked).isTrue();
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api, times(2)).responses(captor.capture());
		ResponsesRequest first = captor.getAllValues().get(0);
		assertThat(first.tools()).hasSize(1);
		assertThat(first.tools().get(0).name()).isEqualTo("get_weather");
		assertThat(captor.getAllValues())
			.allSatisfy(request -> assertThat(request.tools().get(0).strict()).isEqualTo(strict));
		ResponsesRequest second = captor.getAllValues().get(1);
		List<?> input = (List<?>) second.input();
		assertThat(input).anySatisfy(item -> {
			assertThat(item).isInstanceOf(ResponsesFunctionCallOutput.class);
			ResponsesFunctionCallOutput output = (ResponsesFunctionCallOutput) item;
			assertThat(output.callId()).isEqualTo("call-1");
			assertThat(output.output()).isEqualTo("\"sunny\"");
		});
	}

	@Test
	void modelSurfacesToolCallsWithoutExecutingThem() {
		// Spring AI 2.0 contract: the raw ChatModel never runs the tool loop. A
		// tool_calls response reaches the caller as-is, the provider is hit exactly
		// once, and the callback is never invoked.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(chatCompletionResponse(
				new ChatMessage("assistant", null, null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", BERLIN_ARGS)))),
				"tool_calls"));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		ChatResponse response = model.call(new Prompt(List.of(new UserMessage(WEATHER_PROMPT)),
				OpenRouterChatOptions.builder().model(MINI_MODEL).toolCallbacks(List.of(this.weatherTool)).build()));

		assertThat(response.hasToolCalls()).isTrue();
		assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
		assertThat(this.toolInvoked).isFalse();
		verify(api, times(1)).chatCompletion(any());
	}

	@Test
	void toolsConfiguredAsModelDefaultsAreAdvertisedOnTheWire() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(
				chatCompletionResponse(new ChatMessage("assistant", "No tools needed.", null, null, null), "stop"));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(
					OpenRouterChatOptions.builder().model(MINI_MODEL).toolCallbacks(List.of(this.weatherTool)).build())
			.build();

		model.call(new Prompt(List.of(new UserMessage(WEATHER_PROMPT))));

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		assertThat(captor.getValue().tools()).hasSize(1);
		assertThat(captor.getValue().tools().get(0).function().name()).isEqualTo("get_weather");
	}

	@Test
	void runtimeToolCallbacksReplaceModelDefaultsOnTheWire() {
		// Framework merge semantics: runtime tool callbacks replace the defaults
		// wholesale, so the executing advisor sees exactly the advertised tools.
		ToolCallback runtimeTool = FunctionToolCallback.builder("get_time", (Map<String, Object> input) -> "12:00")
			.description("Current time")
			.inputType(Map.class)
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any()))
			.thenReturn(chatCompletionResponse(new ChatMessage("assistant", "Done.", null, null, null), "stop"));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(
					OpenRouterChatOptions.builder().model(MINI_MODEL).toolCallbacks(List.of(this.weatherTool)).build())
			.build();

		model.call(new Prompt(List.of(new UserMessage(WEATHER_PROMPT)),
				OpenRouterChatOptions.builder().toolCallbacks(List.of(runtimeTool)).build()));

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		assertThat(captor.getValue().tools()).hasSize(1);
		assertThat(captor.getValue().tools().get(0).function().name()).isEqualTo("get_time");
	}

	private ResponsesResult responsesResult(ResponsesOutputItem item) {
		return new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", "completed", List.of(item), null,
				null);
	}

	private ChatCompletionResponse chatCompletionResponse(ChatMessage message, String finishReason) {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, MINI_MODEL, "openai",
				List.of(new Choice(0, message, null, finishReason, finishReason)), null);
	}

}
