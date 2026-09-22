package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesTool;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the streaming tool-call behavior: surface, do not execute.
 *
 * <p>
 * Like {@link OpenRouterChatModel#call}, {@link OpenRouterChatModel#stream} never runs a
 * tool-execution loop (Spring AI 2.0 moved the loop to {@code ToolCallingAdvisor} on the
 * {@code ChatClient}). When a provider streams a tool call, the library surfaces it to
 * the caller as tool-call metadata on the emitted {@link ChatResponse} and never invokes
 * the tool callback. The behavior is identical in chat-completions and responses mode.
 */
class OpenRouterChatModelStreamingToolCallTests {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final AtomicBoolean toolInvoked = new AtomicBoolean(false);

	private final ToolCallback weatherTool = FunctionToolCallback.builder("get_weather", (Map<String, Object> in) -> {
		this.toolInvoked.set(true);
		return "sunny";
	}).description("Look up the weather").inputType(Map.class).build();

	private ChatCompletionChunk toolCallChunk() {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, "openai/gpt-5.4-mini", "openai",
				List.of(new Choice(0, null,
						new Delta("assistant", "", null,
								List.of(new ToolCall("call-1", "function",
										new FunctionCall("get_weather", "{\"city\":\"Berlin\"}")))),
						"tool_calls", "tool_calls")),
				null, null);
	}

	private ResponsesStreamEvent responsesToolCallEvent() {
		try {
			return MAPPER.readValue("""
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
					""", ResponsesStreamEvent.class);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void advisorNeverExecutesUnterminatedToolCallsOrReplaysTheStream() {
		for (String arguments : List.of("", "{", "{}")) {
			OpenRouterApi api = mock(OpenRouterApi.class);
			ChatCompletionChunk incomplete = new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, "model",
					"provider",
					List.of(new Choice(0, null,
							new Delta("assistant", null, null, List
								.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", arguments)))),
							null, null)),
					null, null);
			when(api.chatCompletionStream(any())).thenReturn(Flux.just(incomplete));
			OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
			var client = ChatClient.builder(model).build();
			StepVerifier.create(client
				.prompt(new Prompt("weather?",
						OpenRouterChatOptions.builder().toolCallbacks(List.of(this.weatherTool)).build()))
				.stream()
				.chatResponse()).expectError(OpenRouterTruncatedResponseException.class).verify(Duration.ofSeconds(5));
			assertThat(this.toolInvoked).isFalse();
			verify(api).chatCompletionStream(any());
		}
	}

	@Test
	void chatCompletionsStreamingSurfacesToolCallWithoutExecutingIt() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(toolCallChunk()));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		List<ChatResponse> responses = model
			.stream(new Prompt(List.of(new UserMessage("weather in Berlin?")),
					OpenRouterChatOptions.builder()
						.model("openai/gpt-5.4-mini")
						.toolCallbacks(List.of(this.weatherTool))
						.build()))
			.collectList()
			.block(Duration.ofSeconds(5));

		// The real ToolCallingManager resolved the callback into the outgoing request,
		// so the provider actually learned about the tool.
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletionStream(captor.capture());
		assertThat(captor.getValue().tools()).extracting(tool -> tool.function().name()).containsExactly("get_weather");

		// The tool call is surfaced, not swallowed.
		assertThat(responses).hasSize(1);
		ChatResponse response = responses.get(0);
		assertThat(response.hasToolCalls()).isTrue();
		AssistantMessage.ToolCall surfaced = response.getResult().getOutput().getToolCalls().get(0);
		assertThat(surfaced.name()).isEqualTo("get_weather");
		assertThat(surfaced.arguments()).isEqualTo("{\"city\":\"Berlin\"}");
		// No automatic execution during streaming.
		assertThat(this.toolInvoked).isFalse();
	}

	@Test
	void responsesStreamingSurfacesToolCallWithoutExecutingIt() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any()))
			.thenReturn(
					Flux.just(responsesToolCallEvent(),
							new ResponsesStreamEvent("response.completed", null, null, new ResponsesResult(null, null,
									null, null, "completed", List.of(responsesToolCallEvent().item()), null, null),
									null)));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		List<ChatResponse> responses = model
			.stream(new Prompt(List.of(new UserMessage("weather in Berlin?")),
					OpenRouterChatOptions.builder()
						.model("openai/gpt-5.4")
						.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
						.toolCallbacks(List.of(this.weatherTool))
						.build()))
			.collectList()
			.block(Duration.ofSeconds(5));

		// The real ToolCallingManager resolved the callback into the outgoing request.
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api).responsesStream(captor.capture());
		assertThat(captor.getValue().tools()).extracting(ResponsesTool::name).containsExactly("get_weather");

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).hasToolCalls()).isFalse();
		ChatResponse response = responses.get(1);
		assertThat(response.hasToolCalls()).isTrue();
		assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
		assertThat(this.toolInvoked).isFalse();
	}

}
