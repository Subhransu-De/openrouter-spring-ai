package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesFunctionCallOutput;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Tool-round behavior of {@link OpenRouterChatModel} driven through {@code ChatClient}
 * and the real {@code ToolCallingAdvisor}: how this library maps tool results and the
 * assistant tool-call history into follow-up requests, and how a provider failure after a
 * tool round reaches the caller. The happy path lives in
 * {@code OpenRouterChatModelToolCallingTests}. Tool resolution, JSON binding and the loop
 * itself belong to Spring AI and are not asserted here.
 */
class OpenRouterChatModelToolCallingFailureTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private static final String BERLIN_ARGS = "{\"city\":\"Berlin\"}";

	private static final String SUNNY_IN_BERLIN = "\"sunny in Berlin\"";

	private static final String TOOL_FAILURE_APOLOGY = "Sorry, the tool failed.";

	private final AtomicInteger weatherInvocations = new AtomicInteger();

	private final ToolCallback weatherTool = FunctionToolCallback.builder("get_weather", (Map<String, Object> in) -> {
		this.weatherInvocations.incrementAndGet();
		return "sunny in " + in.getOrDefault("city", "?");
	}).description("Look up the weather").inputType(Map.class).build();

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

	private ResponsesResult responsesToolCall(String toolName) {
		return new ResponsesResult("resp-1", "response", 123L, MODEL, "completed",
				List.of(new ResponsesOutputItem("fc-1", "function_call", "completed", null, null, "call-1", toolName,
						"{}", null)),
				null, null);
	}

	private static OpenRouterNonTransientApiException providerRejection(String endpoint) {
		return new OpenRouterNonTransientApiException("Provider rejected the follow-up request", HttpStatus.BAD_REQUEST,
				"{\"error\":{\"code\":400,\"message\":\"Provider rejected the follow-up request\"}}", null, null,
				endpoint);
	}

	private ChatClient toolCallingClient(OpenRouterApi api) {
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		ToolCallingAdvisor advisor = ToolCallingAdvisor.builder()
			.toolCallingManager(ToolCallingManager.builder()
				.toolExecutionExceptionProcessor(new OpenRouterToolExecutionExceptionProcessor())
				.build())
			.build();
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
		assertThat(followUp).filteredOn(message -> "tool".equals(message.role())).singleElement().satisfies(message -> {
			assertThat(message.content()).isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			assertThat(message.toolCallId()).isEqualTo("call-1");
		});
		assertThat(followUp).extracting(message -> String.valueOf(message.content()))
			.allSatisfy(content -> assertThat(content).doesNotContain("tool exploded", "sk-sensitive", "nested secret",
					"IllegalStateException"));
	}

	@Test
	void responsesModeUsesTheSameSafeToolFailurePayload() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ResponsesResult answer = new ResponsesResult(
				"resp-2", "response", 124L, MODEL, "completed", List.of(new ResponsesOutputItem("msg-1", "message",
						"completed", "assistant", List.of(new ResponsesContent("output_text", TOOL_FAILURE_APOLOGY)))),
				null, null);
		when(api.responses(any())).thenReturn(responsesToolCall("boom"), answer);

		ChatResponse response = toolCallingClient(api).prompt(responsesPrompt(this.boomTool)).call().chatResponse();

		assertThat(response.getResult().getOutput().getText()).isEqualTo(TOOL_FAILURE_APOLOGY);
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api, times(2)).responses(captor.capture());
		List<?> followUp = (List<?>) captor.getAllValues().get(1).input();
		assertThat(followUp).filteredOn(ResponsesFunctionCallOutput.class::isInstance)
			.singleElement()
			.isInstanceOfSatisfying(ResponsesFunctionCallOutput.class, output -> {
				assertThat(output.callId()).isEqualTo("call-1");
				assertThat(output.output())
					.isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			});
	}

	@Test
	void providerErrorOnTheFollowUpRequestReachesTheCallerAfterTheToolRanOnce() {
		OpenRouterNonTransientApiException failure = providerRejection("/chat/completions");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse("get_weather", BERLIN_ARGS)).thenThrow(failure);
		ChatClient client = toolCallingClient(api);

		assertThatThrownBy(() -> call(client, this.weatherTool)).isSameAs(failure);

		assertThat(this.weatherInvocations).hasValue(1);
		verify(api, times(2)).chatCompletion(any());
	}

	@Test
	void providerErrorOnTheStreamedFollowUpRequestReachesTheCallerAfterTheToolRanOnce() {
		OpenRouterNonTransientApiException failure = providerRejection("/chat/completions");
		OpenRouterApi api = mock(OpenRouterApi.class);
		ChatCompletionChunk toolCall = new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL, "openai",
				List.of(new Choice(0, null,
						new Delta("assistant", null, null, List
							.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", BERLIN_ARGS), 0))),
						"tool_calls", "tool_calls")),
				null, null);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(toolCall), Flux.error(failure));
		ChatClient client = toolCallingClient(api);

		StepVerifier.create(client.prompt(prompt(this.weatherTool)).stream().chatResponse())
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify();

		assertThat(this.weatherInvocations).hasValue(1);
		verify(api, times(2)).chatCompletionStream(any());
	}

	@Test
	void providerErrorOnTheResponsesFollowUpRequestReachesTheCallerAfterTheToolRanOnce() {
		OpenRouterNonTransientApiException failure = providerRejection("/responses");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any())).thenReturn(responsesToolCall("get_weather")).thenThrow(failure);
		ChatClient client = toolCallingClient(api);

		assertThatThrownBy(() -> client.prompt(responsesPrompt(this.weatherTool)).call().chatResponse())
			.isSameAs(failure);

		assertThat(this.weatherInvocations).hasValue(1);
		verify(api, times(2)).responses(any());
	}

	@Test
	void providerErrorOnTheStreamedResponsesFollowUpRequestReachesTheCallerAfterTheToolRanOnce() {
		OpenRouterNonTransientApiException failure = providerRejection("/responses");
		OpenRouterApi api = mock(OpenRouterApi.class);
		ResponsesResult round = responsesToolCall("get_weather");
		when(api.responsesStream(any()))
			.thenReturn(
					Flux.just(new ResponsesStreamEvent("response.output_item.done", null, round.output().get(0), null,
							null), new ResponsesStreamEvent("response.completed", null, null, round, null)),
					Flux.error(failure));
		ChatClient client = toolCallingClient(api);

		StepVerifier.create(client.prompt(responsesPrompt(this.weatherTool)).stream().chatResponse())
			.thenConsumeWhile(response -> true)
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify();

		assertThat(this.weatherInvocations).hasValue(1);
		verify(api, times(2)).responsesStream(any());
	}

	@Test
	void parallelToolCallsInOneAssistantTurnRunOnceEachAndReturnInCallOrder() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ChatCompletionResponse parallel = new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0, new ChatMessage("assistant", null, null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", BERLIN_ARGS)),
								new ToolCall("call-2", "function",
										new FunctionCall("get_weather", "{\"city\":\"Paris\"}")))),
						null, "tool_calls", "tool_calls")),
				null);
		when(api.chatCompletion(any())).thenReturn(parallel, finalResponse("Both are sunny."));

		ChatResponse response = call(toolCallingClient(api), this.weatherTool);

		assertThat(response.getResult().getOutput().getText()).isEqualTo("Both are sunny.");
		assertThat(this.weatherInvocations).hasValue(2);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		List<ChatMessage> followUp = captor.getAllValues().get(1).messages();
		assertThat(followUp).filteredOn(message -> "assistant".equals(message.role()))
			.singleElement()
			.satisfies(message -> assertThat(message.toolCalls()).extracting(ToolCall::id)
				.containsExactly("call-1", "call-2"));
		assertThat(followUp).filteredOn(message -> "tool".equals(message.role()))
			.extracting(ChatMessage::toolCallId, ChatMessage::content)
			.containsExactly(tuple("call-1", SUNNY_IN_BERLIN), tuple("call-2", "\"sunny in Paris\""));
	}

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
			assertThat(message.content()).isEqualTo(SUNNY_IN_BERLIN);
		});

		List<ChatMessage> finalRound = captor.getAllValues().get(2).messages();
		assertThat(finalRound).filteredOn(m -> "tool".equals(m.role()))
			.extracting(ChatMessage::toolCallId, ChatMessage::content)
			.containsExactly(tuple("call-1", SUNNY_IN_BERLIN), tuple("call-2", "\"sunny in Paris\""));
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
		assertThat(captor.getAllValues().get(1).messages()).filteredOn(message -> "tool".equals(message.role()))
			.singleElement()
			.satisfies(message -> {
				assertThat(message.content()).isEqualTo(SUNNY_IN_BERLIN);
				assertThat(message.toolCallId()).isNull();
			});
	}

}
