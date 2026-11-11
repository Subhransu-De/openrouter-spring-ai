package de.subhransu.openrouter.springai.chat.mapper;

import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.MODEL;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.chunk;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.finishChoice;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.textChoice;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.toolFragment;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.wireChunk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Streamed tool-call fragments (split by the wire {@code index} across chunks) must be
 * merged into complete tool calls before they reach consumers: partial JSON arguments are
 * unusable, and the aggregating {@code ToolCallingAdvisor} would otherwise execute
 * against a fragment. Plain text chunks keep streaming through one-by-one. These tests
 * drive the aggregator through {@link OpenRouterChatModel}; the aggregator's own
 * fragment, ordering and limit rules are covered in
 * {@link OpenRouterStreamingToolCallAggregatorTests} and
 * {@link OpenRouterStreamingToolCallLimitTests}.
 */
class OpenRouterStreamingToolCallAggregationTests {

	private static final String WEATHER_TOOL = "get_weather";

	private OpenRouterChatModel model(OpenRouterApi api) {
		return OpenRouterChatModel.builder().openRouterApi(api).build();
	}

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("weather in Berlin?")),
				OpenRouterChatOptions.builder().model(MODEL).build());
	}

	private static Choice weatherFragment(int toolIndex, String id, String name, String argumentFragment) {
		return toolFragment(0, toolIndex, id, name, argumentFragment);
	}

	@Test
	void parallelToolCallsSplitByIndexAreEachMergedCompletely() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(weatherFragment(0, "call-1", WEATHER_TOOL, "{\"city\":")),
					chunk(weatherFragment(1, "call-2", "get_time", "{\"zone\":")),
					chunk(weatherFragment(0, null, null, "\"Berlin\"}")),
					chunk(weatherFragment(1, null, null, "\"UTC\"}")), chunk(finishChoice(0))));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(1);
		ChatResponse merged = responses.get(0);
		assertThat(merged.hasToolCalls()).isTrue();
		assertThat(merged.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		List<AssistantMessage.ToolCall> toolCalls = merged.getResult().getOutput().getToolCalls();
		assertThat(toolCalls).extracting(AssistantMessage.ToolCall::id).containsExactly("call-1", "call-2");
		assertThat(toolCalls).extracting(AssistantMessage.ToolCall::name).containsExactly(WEATHER_TOOL, "get_time");
		assertThat(toolCalls).extracting(AssistantMessage.ToolCall::arguments)
			.containsExactly("{\"city\":\"Berlin\"}", "{\"zone\":\"UTC\"}");
	}

	@Test
	void textChunksKeepStreamingIndividuallyAroundTheToolCallBuffer() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(chunk(textChoice(0, "Let me ")), chunk(textChoice(0, "check.")),
					chunk(weatherFragment(0, "call-1", WEATHER_TOOL, "{}")), chunk(finishChoice(0))));

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
		when(api.chatCompletionStream(any())).thenReturn(
				Flux.just(chunk(weatherFragment(0, "call-1", WEATHER_TOOL, "{}")), chunk(finishChoice(0)), usageChunk));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).hasToolCalls()).isTrue();
		assertThat(responses.get(1).getMetadata().getUsage().getTotalTokens()).isEqualTo(15);
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
				Flux.just(chunk(textChoice(0, "Stored.")), chunk(finishChoice(0, "stop"))));
		ChatClient client = ChatClient.builder(model(api)).build();
		Prompt prompt = new Prompt(List.of(new UserMessage("store this note")),
				OpenRouterChatOptions.builder().model(MODEL).toolCallbacks(List.of(noteTool)).build());

		String streamedText = String.join("",
				client.prompt(prompt).stream().content().collectList().block(Duration.ofSeconds(5)));

		// The advisor aggregated the fragmented stream into one complete tool call,
		// executed it once, and re-streamed the follow-up turn.
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
			.verify();
	}

	@Test
	void defaultChunkLimitStillRejectsOneFragmentMoreThanItAllows() {
		int fragments = OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS + 1;
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.range(0, fragments)
			.map(fragment -> new ChatCompletionChunk(null, null, null, null, null,
					List.of(weatherFragment(0, fragment == 0 ? "call-1" : null, fragment == 0 ? WEATHER_TOOL : null,
							"x")),
					null, null))
			.concatWith(Flux.just(chunk(finishChoice(0)))));

		StepVerifier.create(model(api).stream(prompt()))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit()).isEqualTo(Limit.STREAMING_TOOL_CALL_CHUNKS);
					assertThat(failure.getConfiguredLimit())
						.isEqualTo(OpenRouterStreamingToolCallAggregator.DEFAULT_MAX_CHUNKS);
					assertThat(failure.getObservedValue()).isEqualTo(fragments);
				}))
			.verify();
	}

	@Test
	void newestUsageWinsWhenAUsageOnlyChunkPrecedesThePlainTextFinish() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(chunk(textChoice(0, "Sunny.")),
				wireChunk("{\"choices\":[],\"usage\":{\"total_tokens\":12,\"cost\":0.1}}"),
				wireChunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
						+ "\"usage\":{\"total_tokens\":15,\"cost\":0.2}}")));

		List<ChatResponse> responses = model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).filteredOn(response -> response.getMetadata().getUsage() instanceof OpenRouterUsage)
			.last()
			.satisfies(response -> assertUsage(response, 15, 0.2));
	}

	/**
	 * Model-level view of the superseded-usage rule. The observation context, the
	 * {@code openrouter.choice_index} metadata and the emitted responses must all agree
	 * on the newest snapshot. The aggregator-level rules (including a single choice with
	 * a usage-only chunk) are covered by
	 * {@code OpenRouterStreamingToolCallAggregatorTests}.
	 */
	@Test
	void olderSharedUsageIsNotReportedAfterANewerToolChoiceFinish() {
		List<ChatResponse> observed = new ArrayList<>();
		ObservationRegistry observationRegistry = ObservationRegistry.create();
		observationRegistry.observationConfig()
			.observationHandler(new ObservationHandler<ChatModelObservationContext>() {
				@Override
				public boolean supportsContext(Observation.Context context) {
					return context instanceof ChatModelObservationContext;
				}

				@Override
				public void onStop(ChatModelObservationContext context) {
					observed.add(context.getResponse());
				}
			});
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(wireChunk(
				"{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-0\",\"type\":\"function\","
						+ "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]}},"
						+ "{\"index\":1,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\","
						+ "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]}}]}"),
				wireChunk("{\"choices\":[],\"usage\":{\"total_tokens\":12,\"cost\":0.1},\"revision\":\"old\"}"),
				wireChunk("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
						+ "\"usage\":{\"total_tokens\":15,\"cost\":0.2},\"revision\":\"new\"}"),
				wireChunk("{\"choices\":[{\"index\":1,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.observationRegistry(observationRegistry)
			.build();

		List<ChatResponse> responses = model.stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(responses).flatExtracting(ChatResponse::getResults)
			.extracting(generation -> generation.getMetadata().<Integer>get("openrouter.choice_index"))
			.containsExactly(0, 1);
		assertThat(responses).filteredOn(response -> response.getMetadata().getUsage() instanceof OpenRouterUsage)
			.last()
			.satisfies(response -> assertUsage(response, 15, 0.2));
		assertThat(responseExtensions(responses.get(responses.size() - 1))).containsEntry("revision", "new");
		assertThat(observed).singleElement().satisfies(response -> {
			assertUsage(response, 15, 0.2);
			assertThat(responseExtensions(response)).containsEntry("revision", "new");
		});
	}

	private static Map<String, Object> responseExtensions(ChatResponse response) {
		return response.getMetadata().get("openrouter.response.extensions");
	}

	private static void assertUsage(ChatResponse response, int totalTokens, double cost) {
		assertThat(response.getMetadata().getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class, usage -> {
			assertThat(usage.getTotalTokens()).isEqualTo(totalTokens);
			assertThat(usage.getCost()).isEqualTo(cost);
		});
	}

	private Flux<ChatCompletionChunk> fragmentedToolCall(String arguments, int fragmentLength) {
		List<ChatCompletionChunk> chunks = new ArrayList<>();
		for (int start = 0; start < arguments.length(); start += fragmentLength) {
			String fragment = arguments.substring(start, Math.min(arguments.length(), start + fragmentLength));
			chunks.add(chunk(start == 0 ? weatherFragment(0, "call-1", WEATHER_TOOL, fragment)
					: weatherFragment(0, null, null, fragment)));
		}
		chunks.add(chunk(finishChoice(0)));
		return Flux.fromIterable(chunks);
	}

	private static String noteArguments(int length) {
		return "{\"note\":\"" + "x".repeat(length - 11) + "\"}";
	}

}
