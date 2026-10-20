package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Streamed tool-call fragments (split by the wire {@code index} across chunks) must be
 * merged into complete tool calls before they reach consumers — partial JSON arguments
 * are unusable, and the aggregating {@code ToolCallingAdvisor} would otherwise execute
 * against a fragment. Plain text chunks keep streaming through one-by-one.
 */
class OpenRouterStreamingToolCallAggregationTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private static final String WEATHER_TOOL = "get_weather";

	private ChatCompletionChunk chunk(Choice... choices) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL, "openai", List.of(choices), null,
				null);
	}

	private Choice textChoice(String text) {
		return new Choice(0, null, new Delta("assistant", text, null, null), null, null);
	}

	private Choice toolFragmentChoice(int index, String id, String name, String argumentFragment) {
		return new Choice(0, null, new Delta(id != null ? "assistant" : null, null, null, List
			.of(new ToolCall(id, id != null ? "function" : null, new FunctionCall(name, argumentFragment), index))),
				null, null);
	}

	private Choice finishChoice(String finishReason) {
		return new Choice(0, null, new Delta(null, null, null, null), finishReason, finishReason);
	}

	private OpenRouterChatModel model(OpenRouterApi api) {
		return OpenRouterChatModel.builder().openRouterApi(api).build();
	}

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("weather in Berlin?")),
				OpenRouterChatOptions.builder().model(MODEL).build());
	}

	@Test
	void fragmentedToolCallArgumentsAreMergedIntoOneCompleteToolCall() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(toolFragmentChoice(0, "call-1", WEATHER_TOOL, "{\"city\":")),
					chunk(toolFragmentChoice(0, null, null, "\"Berlin\"}")), chunk(finishChoice("tool_calls"))));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(1);
		ChatResponse merged = responses.get(0);
		assertThat(merged.hasToolCalls()).isTrue();
		AssistantMessage.ToolCall toolCall = merged.getResult().getOutput().getToolCalls().get(0);
		assertThat(toolCall.id()).isEqualTo("call-1");
		assertThat(toolCall.name()).isEqualTo(WEATHER_TOOL);
		assertThat(toolCall.arguments()).isEqualTo("{\"city\":\"Berlin\"}");
		assertThat(merged.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
	}

	@Test
	void parallelToolCallsSplitByIndexAreEachMergedCompletely() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(toolFragmentChoice(0, "call-1", WEATHER_TOOL, "{\"city\":")),
					chunk(toolFragmentChoice(1, "call-2", "get_time", "{\"zone\":")),
					chunk(toolFragmentChoice(0, null, null, "\"Berlin\"}")),
					chunk(toolFragmentChoice(1, null, null, "\"UTC\"}")), chunk(finishChoice("tool_calls"))));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(1);
		List<AssistantMessage.ToolCall> toolCalls = responses.get(0).getResult().getOutput().getToolCalls();
		assertThat(toolCalls).hasSize(2);
		assertThat(toolCalls.get(0).id()).isEqualTo("call-1");
		assertThat(toolCalls.get(0).arguments()).isEqualTo("{\"city\":\"Berlin\"}");
		assertThat(toolCalls.get(1).id()).isEqualTo("call-2");
		assertThat(toolCalls.get(1).arguments()).isEqualTo("{\"zone\":\"UTC\"}");
	}

	@Test
	void textChunksKeepStreamingIndividuallyAroundTheToolCallBuffer() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(textChoice("Let me ")), chunk(textChoice("check.")),
					chunk(toolFragmentChoice(0, "call-1", WEATHER_TOOL, "{}")), chunk(finishChoice("tool_calls"))));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		// Text still streams chunk-by-chunk; only the tool-call fragments collapse.
		assertThat(responses).hasSize(3);
		assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("Let me ");
		assertThat(responses.get(1).getResult().getOutput().getText()).isEqualTo("check.");
		assertThat(responses.get(2).hasToolCalls()).isTrue();
	}

	@Test
	void usageOnlyChunkAfterTheFinishChunkPassesThroughSeparately() {
		ChatCompletionChunk usageChunk = new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL,
				"openai", List.of(), new Usage(10, 5, 15, null, null, null, null, null, null), null);
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(toolFragmentChoice(0, "call-1", WEATHER_TOOL, "{}")),
					chunk(finishChoice("tool_calls")), usageChunk));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).hasToolCalls()).isTrue();
		assertThat(responses.get(1).getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
	}

	@Test
	void fragmentsWithoutAWireIndexFallBackToIdThenLastCallCorrelation() {
		Choice idFragment = new Choice(0, null,
				new Delta("assistant", null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall(WEATHER_TOOL, "{\"city\":")))),
				null, null);
		Choice continuationFragment = new Choice(0, null,
				new Delta(null, null, null, List.of(new ToolCall(null, null, new FunctionCall(null, "\"Berlin\"}")))),
				null, null);
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(idFragment), chunk(continuationFragment), chunk(finishChoice("tool_calls"))));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(1);
		List<AssistantMessage.ToolCall> toolCalls = responses.get(0).getResult().getOutput().getToolCalls();
		assertThat(toolCalls).hasSize(1);
		assertThat(toolCalls.get(0).arguments()).isEqualTo("{\"city\":\"Berlin\"}");
	}

	@Test
	void streamedToolCallsExecuteEndToEndThroughTheChatClientAdvisor() {
		AtomicBoolean toolInvoked = new AtomicBoolean(false);
		ToolCallback weatherTool = FunctionToolCallback.builder(WEATHER_TOOL, (Map<String, Object> in) -> {
			toolInvoked.set(true);
			return "sunny";
		}).description("Look up the weather").inputType(Map.class).build();

		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(
				Flux.just(chunk(toolFragmentChoice(0, "call-1", WEATHER_TOOL, "{\"city\":")),
						chunk(toolFragmentChoice(0, null, null, "\"Berlin\"}")), chunk(finishChoice("tool_calls"))),
				Flux.just(chunk(textChoice("It is sunny.")), chunk(finishChoice("stop"))));

		ChatClient client = ChatClient.builder(model(api)).build();
		Prompt prompt = new Prompt(List.of(new UserMessage("weather in Berlin?")),
				OpenRouterChatOptions.builder().model(MODEL).toolCallbacks(List.of(weatherTool)).build());

		String streamedText = String.join("",
				client.prompt(prompt).stream().content().collectList().block(Duration.ofSeconds(10)));

		// The advisor aggregated the fragmented stream into a complete tool call,
		// executed it, and re-streamed the follow-up turn.
		assertThat(toolInvoked).isTrue();
		assertThat(streamedText).isEqualTo("It is sunny.");
		verify(api, times(2)).chatCompletionStream(any());
	}

	@Test
	void defaultLimitsAssembleSixKilobyteArgumentsStreamedInFourCharacterFragments() {
		String arguments = noteArguments(6_000);
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(fragmentedToolCall(arguments, 4));

		StepVerifier.create(model(api).stream(prompt())).assertNext(response -> {
			AssistantMessage.ToolCall toolCall = response.getResult().getOutput().getToolCalls().get(0);
			assertThat(toolCall.name()).isEqualTo(WEATHER_TOOL);
			assertThat(toolCall.arguments()).isEqualTo(arguments);
		}).verifyComplete();
	}

	@Test
	void chatClientRunsTheToolOnceForSixKilobyteStreamedArguments() {
		String arguments = noteArguments(6_000);
		List<Map<String, Object>> invocations = new CopyOnWriteArrayList<>();
		ToolCallback noteTool = FunctionToolCallback.builder(WEATHER_TOOL, (Map<String, Object> in) -> {
			invocations.add(in);
			return "stored";
		}).description("Store a note").inputType(Map.class).build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(fragmentedToolCall(arguments, 4),
				Flux.just(chunk(textChoice("Stored.")), chunk(finishChoice("stop"))));
		ChatClient client = ChatClient.builder(model(api)).build();
		Prompt prompt = new Prompt(List.of(new UserMessage("store this note")),
				OpenRouterChatOptions.builder().model(MODEL).toolCallbacks(List.of(noteTool)).build());

		String streamedText = String.join("",
				client.prompt(prompt).stream().content().collectList().block(Duration.ofSeconds(10)));

		assertThat(invocations).singleElement()
			.satisfies(
					input -> assertThat(input.get("note")).isEqualTo(arguments.substring(9, arguments.length() - 2)));
		assertThat(streamedText).isEqualTo("Stored.");
		verify(api, times(2)).chatCompletionStream(any());
	}

	@Test
	void defaultByteLimitStillBoundsArgumentsBelowTheChunkLimit() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(fragmentedToolCall(noteArguments(1_200_000), 4_000));

		StepVerifier.create(model(api).stream(prompt()))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit()).isEqualTo(Limit.STREAMING_TOOL_CALL_BYTES);
					assertThat(failure.getConfiguredLimit())
						.isEqualTo(OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_BYTES);
				}))
			.verify(Duration.ofSeconds(10));
	}

	@Test
	void defaultChunkLimitStillRejectsOneFragmentMoreThanItAllows() {
		int fragments = OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS + 1;
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.range(0, fragments)
			.map(fragment -> new ChatCompletionChunk(null, null, null, null, null,
					List.of(toolFragmentChoice(0, fragment == 0 ? "call-1" : null, fragment == 0 ? WEATHER_TOOL : null,
							"x")),
					null, null))
			.concatWith(Flux.just(chunk(finishChoice("tool_calls")))));

		StepVerifier.create(model(api).stream(prompt()))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit()).isEqualTo(Limit.STREAMING_TOOL_CALL_CHUNKS);
					assertThat(failure.getConfiguredLimit())
						.isEqualTo(OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS);
					assertThat(failure.getObservedValue()).isEqualTo(fragments);
				}))
			.verify(Duration.ofSeconds(10));
	}

	private Flux<ChatCompletionChunk> fragmentedToolCall(String arguments, int fragmentLength) {
		List<ChatCompletionChunk> chunks = new ArrayList<>();
		for (int start = 0; start < arguments.length(); start += fragmentLength) {
			String fragment = arguments.substring(start, Math.min(arguments.length(), start + fragmentLength));
			chunks.add(chunk(start == 0 ? toolFragmentChoice(0, "call-1", WEATHER_TOOL, fragment)
					: toolFragmentChoice(0, null, null, fragment)));
		}
		chunks.add(chunk(finishChoice("tool_calls")));
		return Flux.fromIterable(chunks);
	}

	private static String noteArguments(int length) {
		return "{\"note\":\"" + "x".repeat(length - 11) + "\"}";
	}

}
