package de.subhransu.openrouter.springai.chat.mapper;

import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.chunk;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.finishChoice;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.toolFragment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Signal;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.io.IOContext;
import tools.jackson.core.io.OutputDecorator;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Limits of the streamed tool-call assembly.
 */
class OpenRouterStreamingToolCallLimitTests {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private static final Duration TEST_DURATION = Duration.ofSeconds(30);

	static Stream<Arguments> invalidConstruction() {
		String bytes = "Maximum streamed tool-call size must be greater than zero";
		String chunks = "Maximum streamed tool-call chunk count must be greater than zero";
		String duration = "Maximum streamed tool-call duration must be greater than zero";
		return Stream.of(
				Arguments.of(Named.of("zero bytes", (Executable) () -> aggregator(0, 1, TEST_DURATION)), bytes),
				Arguments.of(Named.of("negative bytes", (Executable) () -> aggregator(-1, 1, TEST_DURATION)), bytes),
				Arguments.of(Named.of("zero chunks", (Executable) () -> aggregator(1, 0, TEST_DURATION)), chunks),
				Arguments.of(Named.of("negative chunks", (Executable) () -> aggregator(1, -1, TEST_DURATION)), chunks),
				Arguments.of(Named.of("zero duration", (Executable) () -> aggregator(1, 1, Duration.ZERO)), duration),
				Arguments.of(Named.of("negative duration", (Executable) () -> aggregator(1, 1, Duration.ofMillis(-1))),
						duration),
				Arguments.of(Named.of("missing mapper",
						(Executable) () -> new OpenRouterStreamingToolCallAggregator(null, 1, 1, TEST_DURATION)),
						"ObjectMapper must not be null"),
				Arguments.of(Named.of("missing duration", (Executable) () -> aggregator(1, 1, null)),
						"Maximum streamed tool-call duration must not be null"));
	}

	@ParameterizedTest
	@MethodSource("invalidConstruction")
	void rejectsInvalidConstructionArguments(Executable construction, String message) {
		assertThatIllegalArgumentException().isThrownBy(construction::execute).withMessage(message);
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
			.create(aggregator(bytes, 3, TEST_DURATION)
				.aggregate(Flux.just(first, choiceLess, last, first, choiceLess, last, first, last)))
			.assertNext(merged -> assertThat(merged.usage().totalTokens()).isEqualTo(3))
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

	@ParameterizedTest(name = "{0}")
	@EnumSource(Termination.class)
	void noLateSignalFollowsAnyTermination(Termination termination) {
		// The assembly timer is Mono.delay, which reads the global scheduler factory, so
		// the virtual clock has to be installed there; the test restores it.
		VirtualTimeScheduler clock = VirtualTimeScheduler.getOrSet();
		List<Throwable> dropped = new CopyOnWriteArrayList<>();
		Hooks.onErrorDropped(dropped::add);
		try {
			List<Signal<ChatCompletionChunk>> signals = new CopyOnWriteArrayList<>();
			Disposable subscription = aggregator(Long.MAX_VALUE, termination.maxChunks, TEST_DURATION)
				.aggregate(Flux.just(toolChunk("{}")).concatWith(termination.tail.get()))
				.materialize()
				.subscribe(signals::add);
			if (termination.cancel) {
				subscription.dispose();
			}
			List<Signal<ChatCompletionChunk>> atTermination = List.copyOf(signals);

			clock.advanceTimeBy(TEST_DURATION.plusSeconds(1));

			assertThat(signals).as("signals after the limit duration passed").isEqualTo(atTermination);
			assertThat(dropped).as("errors dropped after termination").isEmpty();
		}
		finally {
			Hooks.resetOnErrorDropped();
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

	@ParameterizedTest(name = "{0} mapper")
	@MethodSource("byteCountingMappers")
	void byteBudgetChargesTheSerializedSizeOfEveryChunk(ObjectMapper mapper) {
		List<ChatCompletionChunk> chunks = List.of(toolChunk("{\"city\":"), toolChunk("\"Berlin\"}"), finishChunk());
		long bytes = chunks.stream().mapToLong(chunk -> mapper.writeValueAsBytes(chunk).length).sum();

		StepVerifier
			.create(new OpenRouterStreamingToolCallAggregator(mapper, bytes, 3, TEST_DURATION)
				.aggregate(Flux.fromIterable(chunks)))
			.expectNextCount(1)
			.verifyComplete();
		StepVerifier
			.create(new OpenRouterStreamingToolCallAggregator(mapper, bytes - 1, 3, TEST_DURATION)
				.aggregate(Flux.fromIterable(chunks)))
			.expectErrorSatisfies(thrown -> assertThat(((OpenRouterLimitExceededException) thrown).getObservedValue())
				.isEqualTo(bytes))
			.verify();
	}

	static Stream<Arguments> byteCountingMappers() {
		OutputDecorator closingMarker = new OutputDecorator() {

			@Override
			public OutputStream decorate(IOContext context, OutputStream output) {
				return new FilterOutputStream(output) {

					@Override
					public void close() throws IOException {
						write('#');
						super.close();
					}

				};
			}

			@Override
			public Writer decorate(IOContext context, Writer writer) {
				return writer;
			}

		};
		return Stream.of(Arguments.of(Named.of("default", OBJECT_MAPPER)),
				Arguments
					.of(Named.of("indenting", JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build())),
				Arguments.of(Named.of("root-separated",
						JsonMapper.builder(JsonFactory.builder().rootValueSeparator("<|>").build()).build())),
				Arguments.of(Named.of("output-decorated",
						JsonMapper.builder(JsonFactory.builder().outputDecorator(closingMarker).build()).build())),
				Arguments.of(Named.of("entry-count-sensitive", entryCountSensitiveMapper())));
	}

	/**
	 * Writes a string whose length grows with the generator's entry count, so a generator
	 * reused across chunks would charge more than a fresh one.
	 */
	private static ObjectMapper entryCountSensitiveMapper() {
		SimpleModule module = new SimpleModule();
		module.addSerializer(ChatCompletionChunk.class, new ValueSerializer<ChatCompletionChunk>() {
			@Override
			public void serialize(ChatCompletionChunk value, JsonGenerator generator, SerializationContext context) {
				generator.writeString("x".repeat(generator.streamWriteContext().getEntryCount() + 1));
			}
		});
		return JsonMapper.builder().addModule(module).build();
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

	static Stream<Arguments> durationLimits() {
		return Stream.of(Arguments.of(Named.of("whole seconds", Duration.ofSeconds(5))),
				Arguments.of(Named.of("sub-second", Duration.ofMillis(250))));
	}

	@ParameterizedTest(name = "{0}")
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
		return toolFragment(index, 0, id, "get_weather", arguments);
	}

	private static ChatCompletionChunk finishChunk() {
		return chunk(finishChoice(0));
	}

	private static ChatCompletionChunk completeToolChunk() {
		Choice call = toolChoice(0, "call-1", "{}");
		return chunk(new Choice(0, null, call.delta(), "tool_calls", "tool_calls"));
	}

	private enum Termination {

		COMPLETE(() -> Flux.just(finishChunk()), 2, false),

		CANCEL(() -> Flux.never(), 2, true),

		ERROR(() -> Flux.error(new IllegalStateException("synthetic failure")), 2, false),

		LIMIT(() -> Flux.just(toolChunk("{}")), 1, false),

		TRUNCATED(() -> Flux.empty(), 2, false);

		private final Supplier<Flux<ChatCompletionChunk>> tail;

		private final int maxChunks;

		private final boolean cancel;

		Termination(Supplier<Flux<ChatCompletionChunk>> tail, int maxChunks, boolean cancel) {
			this.tail = tail;
			this.maxChunks = maxChunks;
			this.cancel = cancel;
		}

	}

}
