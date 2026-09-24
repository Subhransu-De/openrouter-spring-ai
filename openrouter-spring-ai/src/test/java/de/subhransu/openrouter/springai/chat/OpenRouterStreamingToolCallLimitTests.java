package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.Disposable;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

class OpenRouterStreamingToolCallLimitTests {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Duration TEST_DURATION = Duration.ofSeconds(30);

	@ParameterizedTest
	@ValueSource(ints = { 0, -1 })
	void rejectsNonPositiveLimits(int limit) {
		assertThatIllegalArgumentException().isThrownBy(() -> aggregator(limit, 1, TEST_DURATION));
		assertThatIllegalArgumentException().isThrownBy(() -> aggregator(1, limit, TEST_DURATION));
		assertThatIllegalArgumentException().isThrownBy(() -> aggregator(1, 1, Duration.ofMillis(limit)));
	}

	@Test
	void requiresMapperAndDurationAtConstruction() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterStreamingToolCallAggregator(null, 1, 1, TEST_DURATION));
		assertThatIllegalArgumentException().isThrownBy(() -> aggregator(1, 1, null));
	}

	@Test
	void completedCallsReleaseTheirByteAndChunkBudgets() {
		ChatCompletionChunk first = toolChunk("{}");
		ChatCompletionChunk last = finishChunk();
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(serializedBytes(first) + serializedBytes(last), 2,
				TEST_DURATION);
		StepVerifier.create(aggregator.aggregate(Flux.just(first, last, first, last)))
			.expectNextCount(2)
			.verifyComplete();
	}

	@Test
	void choiceLessChunksAreReleasedAndEmittedOnlyOnceBetweenCalls() {
		ChatCompletionChunk first = toolChunk("{}");
		ChatCompletionChunk last = finishChunk();
		ChatCompletionChunk choiceLess = OBJECT_MAPPER.readValue("{\"choices\":[],\"usage\":{\"total_tokens\":3}}",
				ChatCompletionChunk.class);
		long bytes = serializedBytes(first) + serializedBytes(choiceLess) + serializedBytes(last);
		StepVerifier
			.create(aggregator(bytes, 3, TEST_DURATION).aggregate(Flux.just(first, choiceLess, last, first, last)))
			.assertNext(merged -> assertThat(merged.usage().totalTokens()).isEqualTo(3))
			.assertNext(merged -> assertThat(merged.usage()).isNull())
			.verifyComplete();
	}

	@Test
	void choiceLessChunksCountAgainstTheSharedBudget() {
		ChatCompletionChunk concurrent = chunk(List.of(toolChoice(0, "first", "{"), toolChoice(1, "second", "{")));
		StepVerifier
			.create(aggregator(Long.MAX_VALUE, 2, TEST_DURATION).aggregate(Flux.just(concurrent, chunk(List.of()))))
			.expectErrorSatisfies(error -> {
				assertThat(error).isInstanceOf(OpenRouterLimitExceededException.class);
				assertThat(((OpenRouterLimitExceededException) error).getObservedValue()).isEqualTo(3);
			})
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "complete", "cancel", "error", "limit", "truncated" })
	void terminationDisposesPendingAssemblyTimers(String termination) {
		VirtualTimeScheduler scheduler = spy(VirtualTimeScheduler.create());
		List<Disposable> timers = new ArrayList<>();
		doAnswer(invocation -> {
			Disposable timer = (Disposable) invocation.callRealMethod();
			timers.add(timer);
			return timer;
		}).when(scheduler).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
		VirtualTimeScheduler.set(scheduler);
		try {
			Flux<ChatCompletionChunk> tail = switch (termination) {
				case "complete" -> Flux.just(finishChunk());
				case "error" -> Flux.error(new IllegalStateException("synthetic failure"));
				case "limit" -> Flux.just(toolChunk("{}"));
				case "truncated" -> Flux.empty();
				default -> Flux.never();
			};
			int maxChunks = termination.equals("limit") ? 1 : 2;
			Disposable subscription = aggregator(Long.MAX_VALUE, maxChunks, TEST_DURATION)
				.aggregate(Flux.just(toolChunk("{}")).concatWith(tail))
				.subscribe(ignored -> {
				}, error -> {
				});
			if (termination.equals("cancel")) {
				subscription.dispose();
			}
			assertThat(timers).hasSize(1).allSatisfy(timer -> assertThat(timer.isDisposed()).isTrue());
		}
		finally {
			VirtualTimeScheduler.reset();
		}
	}

	static Stream<Arguments> acceptedByteBoundaries() {
		ChatCompletionChunk tool = toolChunk("{\"city\":\"Berlin\"}");
		ChatCompletionChunk finish = finishChunk();
		long bytes = serializedBytes(tool) + serializedBytes(finish);
		return Stream.of(Arguments.of(Named.of("just below", bytes + 1)), Arguments.of(Named.of("exact", bytes)));
	}

	@ParameterizedTest(name = "assembled bytes are {0} the limit")
	@MethodSource("acceptedByteBoundaries")
	void acceptsToolCallBytesThroughTheExactLimit(long maxBytes) {
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(maxBytes, 2, TEST_DURATION);

		StepVerifier.create(aggregator.aggregate(Flux.just(toolChunk("{\"city\":\"Berlin\"}"), finishChunk())))
			.assertNext(merged -> assertThat(merged.choices().get(0).delta().toolCalls().get(0).function().arguments())
				.isEqualTo("{\"city\":\"Berlin\"}"))
			.verifyComplete();
	}

	@ParameterizedTest(name = "assembled chunk count is {0} the limit")
	@MethodSource("acceptedChunkBoundaries")
	void acceptsToolCallChunksThroughTheExactLimit(int maxChunks) {
		ChatCompletionChunk complete = completeToolChunk();
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(serializedBytes(complete), maxChunks,
				TEST_DURATION);

		StepVerifier.create(aggregator.aggregate(Flux.just(complete))).expectNextCount(1).verifyComplete();
	}

	static Stream<Arguments> acceptedChunkBoundaries() {
		return Stream.of(Arguments.of(Named.of("just below", 2)), Arguments.of(Named.of("exact", 1)));
	}

	@ParameterizedTest(name = "over-limit {0} assembly cancels its source")
	@MethodSource("overLimitAggregators")
	void overLimitAssemblyFailsWithTypedExceptionAndCancelsSource(OpenRouterStreamingToolCallAggregator aggregator,
			OpenRouterLimitExceededException.Limit expectedLimit) {
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<ChatCompletionChunk> source = Flux
			.concat(Flux.just(toolChunk("{"), toolChunk("\"city\":"), finishChunk()), Flux.never())
			.doOnCancel(() -> cancelled.set(true));

		StepVerifier.create(aggregator.aggregate(source)).expectErrorSatisfies(thrown -> {
			assertThat(thrown).isInstanceOf(OpenRouterLimitExceededException.class);
			assertThat(((OpenRouterLimitExceededException) thrown).getLimit()).isEqualTo(expectedLimit);
		}).verify();
		assertThat(cancelled).isTrue();
	}

	static Stream<Arguments> overLimitAggregators() {
		ChatCompletionChunk first = toolChunk("{");
		long oneChunkBytes = serializedBytes(first);
		return Stream.of(
				Arguments.of(Named.of("byte", aggregator(oneChunkBytes, 10, TEST_DURATION)),
						OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES),
				Arguments.of(Named.of("chunk", aggregator(Long.MAX_VALUE, 2, TEST_DURATION)),
						OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_CHUNKS));
	}

	@ParameterizedTest
	@MethodSource("durationLimits")
	void durationLimitFailsWithTypedExceptionAndCancelsSource(Duration duration) {
		AtomicBoolean cancelled = new AtomicBoolean();
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(Long.MAX_VALUE, Integer.MAX_VALUE, duration);
		Flux<ChatCompletionChunk> source = Flux.concat(Flux.just(toolChunk("{")), Flux.never())
			.doOnCancel(() -> cancelled.set(true));

		StepVerifier.withVirtualTime(() -> aggregator.aggregate(source))
			.expectSubscription()
			.thenAwait(duration)
			.expectErrorSatisfies(thrown -> {
				assertThat(thrown).isInstanceOf(OpenRouterLimitExceededException.class);
				assertThat(((OpenRouterLimitExceededException) thrown).getLimit())
					.isEqualTo(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_DURATION);
			})
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void aggregateLimitAppliesAcrossConcurrentChoices() {
		AtomicBoolean cancelled = new AtomicBoolean();
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(Long.MAX_VALUE, 2, TEST_DURATION);
		ChatCompletionChunk concurrent = chunk(List.of(toolChoice(0, "call-1", "{"), toolChoice(1, "call-2", "{")));
		Flux<ChatCompletionChunk> source = Flux.concat(Flux.just(concurrent, toolChunk("\"city\":")), Flux.never())
			.doOnCancel(() -> cancelled.set(true));

		StepVerifier.create(aggregator.aggregate(source)).expectErrorSatisfies(thrown -> {
			assertThat(thrown).isInstanceOf(OpenRouterLimitExceededException.class);
			assertThat(((OpenRouterLimitExceededException) thrown).getLimit())
				.isEqualTo(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_CHUNKS);
		}).verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void aggregateByteLimitAppliesAcrossConcurrentChoices() {
		AtomicBoolean cancelled = new AtomicBoolean();
		Choice first = toolChoice(0, "call-1", "{");
		Choice second = toolChoice(1, "call-2", "{");
		OpenRouterStreamingToolCallAggregator aggregator = aggregator(serializedBytes(chunk(first)), 10, TEST_DURATION);
		Flux<ChatCompletionChunk> source = Flux.concat(Flux.just(chunk(List.of(first, second))), Flux.never())
			.doOnCancel(() -> cancelled.set(true));

		StepVerifier.create(aggregator.aggregate(source)).expectErrorSatisfies(thrown -> {
			assertThat(thrown).isInstanceOf(OpenRouterLimitExceededException.class);
			assertThat(((OpenRouterLimitExceededException) thrown).getLimit())
				.isEqualTo(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES);
		}).verify();
		assertThat(cancelled).isTrue();
	}

	static Stream<Duration> durationLimits() {
		return Stream.of(Duration.ofSeconds(5));
	}

	private static OpenRouterStreamingToolCallAggregator aggregator(long maxBytes, int maxChunks,
			Duration maxDuration) {
		return new OpenRouterStreamingToolCallAggregator(OBJECT_MAPPER, maxBytes, maxChunks, maxDuration);
	}

	private static long serializedBytes(ChatCompletionChunk chunk) {
		return OBJECT_MAPPER.writeValueAsBytes(chunk).length;
	}

	private static ChatCompletionChunk toolChunk(String arguments) {
		return chunk(toolChoice(0, "call-1", arguments));
	}

	private static Choice toolChoice(int index, String id, String arguments) {
		return new Choice(index, null,
				new Delta("assistant", null, null,
						List.of(new ToolCall(id, "function", new FunctionCall("get_weather", arguments), 0))),
				null, null);
	}

	private static ChatCompletionChunk finishChunk() {
		return chunk(new Choice(0, null, new Delta(null, null, null, null), "tool_calls", "tool_calls"));
	}

	private static ChatCompletionChunk completeToolChunk() {
		Choice choice = new Choice(0, null,
				new Delta("assistant", null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("get_weather", "{}"), 0))),
				"tool_calls", "tool_calls");
		return chunk(choice);
	}

	private static ChatCompletionChunk chunk(Choice choice) {
		return chunk(List.of(choice));
	}

	private static ChatCompletionChunk chunk(List<Choice> choices) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, "openai/gpt-5.4-mini", "openai", choices,
				null, null);
	}

}
