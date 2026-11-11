package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

class OpenRouterToolCallingManagersTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private static final String SECRET = "jdbc:postgresql://db-internal.example:5432/orders";

	private static final String ANSWER = "The lookup failed.";

	private final ToolCallback failingTool = FunctionToolCallback.builder("lookup", (Map<String, Object> input) -> {
		throw new IllegalStateException("Could not connect to " + SECRET);
	}).description("Always fails").inputType(Map.class).build();

	@Test
	void customizerCannotReplaceTheDeclaredProcessor() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse(), finalResponse());
		ToolExecutionExceptionProcessor declared = new OpenRouterToolExecutionExceptionProcessor();
		ToolCallingManager manager = OpenRouterToolCallingManagers.withFailurePolicy(declared,
				builder -> builder.toolExecutionExceptionProcessor(new DefaultToolExecutionExceptionProcessor(false)));

		// The processor the manager declares is the one it uses: a throwing processor set
		// by the customizer would leave the secret in the tool result.
		assertThat(manager).isInstanceOfSatisfying(OpenRouterToolFailurePolicy.class,
				policy -> assertThat(policy.toolExecutionExceptionProcessor()).isSameAs(declared));
		client(api, manager).prompt(prompt()).call().content();

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		assertSanitizedToolResult(captor.getAllValues().get(1).messages());
	}

	@Test
	void failingToolReturnsTheSanitizedPayloadInASynchronousLoop() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(toolCallResponse(), finalResponse());

		String content = client(api, safeManager()).prompt(prompt()).call().content();

		assertThat(content).isEqualTo(ANSWER);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletion(captor.capture());
		assertSanitizedToolResult(captor.getAllValues().get(1).messages());
	}

	@Test
	void failingToolReturnsTheSanitizedPayloadInAStreamingLoop() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(
				Flux.just(
						chunk(new Delta("assistant", null, null,
								List.of(new ToolCall("call-1", "function", new FunctionCall("lookup", "{}"), 0)))),
						finish("tool_calls")),
				Flux.just(chunk(new Delta("assistant", ANSWER, null, null)), finish("stop")));

		List<String> parts = client(api, safeManager()).prompt(prompt()).stream().content().collectList().block();

		assertThat(String.join("", parts)).isEqualTo(ANSWER);
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletionStream(captor.capture());
		assertSanitizedToolResult(captor.getAllValues().get(1).messages());
	}

	@Test
	void failingToolReturnsTheSanitizedPayloadInAStreamingResponsesLoop() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ResponsesResult round = new ResponsesResult("resp-1", "response", 123L, MODEL, "completed",
				List.of(new ResponsesOutputItem("fc-1", "function_call", "completed", null, null, "call-1", "lookup",
						"{}", null)),
				null, null);
		ResponsesResult answer = new ResponsesResult("resp-2", "response", 124L, MODEL, "completed",
				List.of(new ResponsesOutputItem("msg-1", "message", "completed", "assistant",
						List.of(new ResponsesContent("output_text", ANSWER)))),
				null, null);
		when(api.responsesStream(any()))
			.thenReturn(
					Flux.just(new ResponsesStreamEvent("response.output_item.done", null, round.output().get(0), null,
							null), new ResponsesStreamEvent("response.completed", null, null, round, null)),
					Flux.just(new ResponsesStreamEvent("response.output_text.delta", ANSWER, null, null, null),
							new ResponsesStreamEvent("response.completed", null, null, answer, null)));
		Prompt responsesPrompt = new Prompt(List.of(new UserMessage("go")),
				OpenRouterChatOptions.builder()
					.model(MODEL)
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.toolCallbacks(List.of(this.failingTool))
					.build());

		List<String> parts = client(api, safeManager()).prompt(responsesPrompt)
			.stream()
			.content()
			.collectList()
			.block();

		assertThat(String.join("", parts)).isEqualTo(ANSWER);
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api, times(2)).responsesStream(captor.capture());
		assertThat((List<?>) captor.getAllValues().get(1).input())
			.filteredOn(ResponsesFunctionCallOutput.class::isInstance)
			.singleElement()
			.isInstanceOfSatisfying(ResponsesFunctionCallOutput.class, output -> {
				assertThat(output.callId()).isEqualTo("call-1");
				assertThat(output.output()).isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD)
					.doesNotContain(SECRET);
			});
	}

	@ParameterizedTest
	@MethodSource("missingFactoryArguments")
	void factoryRejectsMissingArguments(Executable factoryCall, String message) {
		assertThatIllegalArgumentException().isThrownBy(factoryCall::execute).withMessage(message);
	}

	static Stream<Arguments> missingFactoryArguments() {
		return Stream.of(Arguments.of(Named.of("missing processor",
				(Executable) () -> OpenRouterToolCallingManagers.withFailurePolicy(null, builder -> {
				})), "processor must not be null"),
				Arguments.of(
						Named.of("missing customizer",
								(Executable) () -> OpenRouterToolCallingManagers
									.withFailurePolicy(new OpenRouterToolExecutionExceptionProcessor(), null)),
						"customizer must not be null"));
	}

	private static ToolCallingManager safeManager() {
		return OpenRouterToolCallingManagers.withFailurePolicy(new OpenRouterToolExecutionExceptionProcessor(),
				builder -> {
				});
	}

	private static ChatClient client(OpenRouterApi api, ToolCallingManager manager) {
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		return ChatClient.builder(model)
			.defaultAdvisors(ToolCallingAdvisor.builder().toolCallingManager(manager).build())
			.build();
	}

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("go")),
				OpenRouterChatOptions.builder().model(MODEL).toolCallbacks(List.of(this.failingTool)).build());
	}

	private static void assertSanitizedToolResult(List<ChatMessage> messages) {
		assertThat(messages).filteredOn(message -> "tool".equals(message.role())).singleElement().satisfies(message -> {
			assertThat(message.content()).isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			assertThat(String.valueOf(message.content())).doesNotContain(SECRET);
		});
	}

	private static ChatCompletionResponse toolCallResponse() {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0,
						new ChatMessage("assistant", null, null, null,
								List.of(new ToolCall("call-1", "function", new FunctionCall("lookup", "{}")))),
						null, "tool_calls", "tool_calls")),
				null);
	}

	private static ChatCompletionResponse finalResponse() {
		return new ChatCompletionResponse("gen-2", "chat.completion", 123L, MODEL, "openai",
				List.of(new Choice(0, new ChatMessage("assistant", ANSWER, null, null, null), null, "stop", "stop")),
				null);
	}

	private static ChatCompletionChunk chunk(Delta delta) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL, "openai",
				List.of(new Choice(0, null, delta, null, null)), null, null);
	}

	private static ChatCompletionChunk finish(String reason) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta(null, null, null, null), reason, reason)), null, null);
	}

}
