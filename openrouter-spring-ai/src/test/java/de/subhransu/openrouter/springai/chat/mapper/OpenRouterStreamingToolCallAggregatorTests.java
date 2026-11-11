package de.subhransu.openrouter.springai.chat.mapper;

import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.CHUNK_OBJECT;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.MODEL;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.chunk;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.finishChoice;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.textChoice;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingToolCallFixtures.toolFragment;
import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.reactivestreams.Subscriber;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.publisher.TestPublisher;
import tools.jackson.databind.ObjectMapper;

class OpenRouterStreamingToolCallAggregatorTests {

	private final OpenRouterStreamingToolCallAggregator aggregator = ResponseMappers.aggregator();

	/**
	 * Each row feeds the three chunks content/reasoning/refusal = (a, b, c), (b, c, a),
	 * (c, a, b), so the expected values are literal concatenations in arrival order, null
	 * only when every part is null.
	 */
	static Stream<Arguments> terminalTextFields() {
		return Stream.of(
				Arguments.of(Named.of("all null", new String[] { null, null, null }),
						new String[] { null, null, null }),
				Arguments.of(Named.of("a lone empty string stays empty, not null", new String[] { "", null, null }),
						new String[] { "", "", "" }),
				Arguments.of(Named.of("an empty string between values", new String[] { "first-😀", null, "" }),
						new String[] { "first-😀", "first-😀", "first-😀" }),
				Arguments.of(Named.of("one value only", new String[] { null, "second-漢", null }),
						new String[] { "second-漢", "second-漢", "second-漢" }),
				Arguments.of(Named.of("unicode in every field", new String[] { "first-😀", "second-漢", "third-ह" }),
						new String[] { "first-😀second-漢third-ह", "second-漢third-हfirst-😀",
								"third-हfirst-😀second-漢" }));
	}

	private static String quote(String value) {
		return value == null ? "null" : "'" + value + "'";
	}

	@ParameterizedTest
	@MethodSource("terminalTextFields")
	void terminalTextPreservesNullEmptyAndUnicodeFieldsIndependently(String[] fields, String[] expected) {
		String first = fields[0];
		String second = fields[1];
		String third = fields[2];
		Choice tool = toolFragment(0, 0, "call-0", "lookup", "{}");
		Flux<ChatCompletionChunk> source = Flux.just(chunk(new Choice(0, null,
				new Delta("assistant", first, second, tool.delta().toolCalls(), null, null, third), null, null)),
				chunk(new Choice(0, null, new Delta(null, second, third, null, null, null, first), null, null)),
				chunk(new Choice(0, null, new Delta(null, third, first, null, null, null, second), null, null)),
				chunk(finishChoice(0)));

		StepVerifier.create(this.aggregator.aggregate(source)).assertNext(merged -> {
			Delta delta = merged.choices().get(0).delta();
			assertThat(delta.content()).isEqualTo(expected[0]);
			assertThat(delta.reasoning()).isEqualTo(expected[1]);
			assertThat(delta.refusal()).isEqualTo(expected[2]);
		}).verifyComplete();
	}

	private static Map<String, Object> nullValue(String key) {
		Map<String, Object> value = new HashMap<>();
		value.put(key, null);
		return value;
	}

	private static Map<String, Object> logprobs(Map<String, Object> value) {
		return Map.of("logprobs", value);
	}

	private static Map<String, Object> token(String token) {
		return Map.of("token", token);
	}

	static Stream<Arguments> choiceExtensionSequences() {
		return Stream.of(
				Arguments.of(
						Named.of("content entries append in arrival order and scalar siblings take the latest value",
								List.of(logprobs(Map.of("content", List.of(token("a")), "revision", 1)),
										logprobs(Map.of("content", List.of(token("b")), "revision", 2)),
										logprobs(Map.of("content", List.of(token("c")), "revision", 3)))),
						logprobs(Map.of("content", List.of(token("a"), token("b"), token("c")), "revision", 3))),
				Arguments.of(
						Named.of("later chunks without logprobs keep the earlier value and add other keys",
								List.of(logprobs(Map.of("content", List.of(token("a")))), Map.of(),
										Map.of("other", "x"))),
						Map.of("logprobs", Map.of("content", List.of(token("a"))), "other", "x")),
				Arguments.of(Named.of("an explicit null logprobs value does not erase the earlier value",
						List.of(logprobs(Map.of("content", List.of(token("a")))), nullValue("logprobs"), Map.of())),
						logprobs(Map.of("content", List.of(token("a"))))),
				Arguments.of(
						Named.of("an opaque logprobs value replaces the earlier map",
								List.of(logprobs(Map.of("content", List.of(token("a")))), Map.of("logprobs", "opaque"),
										Map.of())),
						Map.of("logprobs", "opaque")),
				Arguments.of(
						Named.of(
								"content and refusal entries accumulate independently",
								List.of(logprobs(Map.of("content", List.of(token("a")))),
										logprobs(Map.of("refusal", List.of(token("r")))),
										logprobs(Map.of("content", List.of(token("b")))))),
						logprobs(Map.of("content", List.of(token("a"), token("b")), "refusal", List.of(token("r"))))),
				Arguments.of(
						Named.of("a later map without entry lists keeps the accumulated entries",
								List.of(logprobs(Map.of("content", List.of(token("a")))),
										logprobs(Map.of("revision", 2)), Map.of())),
						logprobs(Map.of("content", List.of(token("a")), "revision", 2))),
				Arguments.of(Named.of("chunks without extensions leave the merged choice without extensions",
						List.of(Map.of(), Map.of(), Map.of())), Map.of()));
	}

	@ParameterizedTest
	@MethodSource("choiceExtensionSequences")
	void terminalLogprobsAccumulateAcrossFragments(List<Map<String, Object>> fragments, Map<String, Object> expected) {
		Choice start = toolFragment(0, 0, "call-0", "lookup", "{}");
		Flux<ChatCompletionChunk> source = Flux.just(
				chunk(new Choice(0, null, start.delta(), null, null, null, fragments.get(0))),
				chunk(new Choice(0, null, null, null, null, null, fragments.get(1))),
				chunk(new Choice(0, null, null, null, null, null, fragments.get(2))), chunk(finishChoice(0)));

		StepVerifier.create(this.aggregator.aggregate(source))
			.assertNext(merged -> assertThat(merged.choices().get(0).extensions()).isEqualTo(expected))
			.verifyComplete();
	}

	@Test
	void boundedDemandAfterSubscriptionStillCollectsEveryToolFragment() {
		Flux<ChatCompletionChunk> source = Flux.just(chunk(textChoice(0, "Checking")),
				chunk(toolFragment(0, 0, "call-0", "lookup", "{")), chunk(toolFragment(0, 0, null, null, "}")),
				chunk(finishChoice(0)));
		StepVerifier.create(this.aggregator.aggregate(source), 1)
			.assertNext(value -> assertThat(value.choices().get(0).delta().content()).isEqualTo("Checking"))
			.thenRequest(1)
			.assertNext(value -> assertThat(value.choices().get(0).delta().toolCalls().get(0).function().arguments())
				.isEqualTo("{}"))
			.expectComplete()
			.verify();
	}

	@Test
	void cancellationBeforeDemandCancelsTheSource() {
		TestPublisher<ChatCompletionChunk> source = TestPublisher.create();
		StepVerifier.create(this.aggregator.aggregate(source.flux()), 0).thenCancel().verify();
		source.assertCancelled();
	}

	@Test
	void demandRequestedBeforeTheUpstreamSubscriptionSaturatesAndEveryItemArrives() {
		AtomicReference<Subscriber<? super ChatCompletionChunk>> upstream = new AtomicReference<>();
		List<ChatCompletionChunk> chunks = IntStream.range(0, 20)
			.mapToObj(index -> chunk(textChoice(0, "text-" + index)))
			.toList();
		StepVerifier.create(this.aggregator.aggregate(Flux.<ChatCompletionChunk>from(upstream::set)), 0)
			.thenRequest(Long.MAX_VALUE - 1)
			.thenRequest(10)
			.then(() -> Flux.fromIterable(chunks).subscribe(upstream.get()))
			.expectNextCount(chunks.size())
			.verifyComplete();
	}

	@Test
	void missingFunctionsAndMixedIndexesPreserveSeparateCalls() {
		ObjectMapper mapper = new ObjectMapper();
		ChatCompletionChunk first = mapper.readValue("""
				{"choices":[{"delta":{"tool_calls":[{"index":7,"id":"first"},
				{"id":"second","function":{"name":"lookup","arguments":"{"}}]}}]}
				""", ChatCompletionChunk.class);
		ChatCompletionChunk next = mapper.readValue("""
				{"choices":[{"delta":{"tool_calls":[{"function":{"name":"lookup","arguments":"}"}},
				{"index":7,"function":{"name":"lookup","arguments":"{}"}},{"index":7},
				{"index":7,"function":{"name":"lookup"}},
				{"id":"third","function":{"name":"lookup"}},{"function":{"arguments":"{}"}},
				{"id":"fourth","function":{"name":"lookup","arguments":"{}"}}]}}]}
				""", ChatCompletionChunk.class);
		ChatCompletionChunk last = mapper.readValue("""
				{"choices":[{"finish_reason":"tool_calls"}]}
				""", ChatCompletionChunk.class);
		StepVerifier.create(this.aggregator.aggregate(Flux.just(first, next, last))).assertNext(merged -> {
			assertThat(merged.choices().get(0).delta().toolCalls()).containsExactly(
					new ToolCall("first", null, new FunctionCall("lookup", "{}"), 7),
					new ToolCall("second", null, new FunctionCall("lookup", "{}"), null),
					new ToolCall("third", null, new FunctionCall("lookup", "{}"), null),
					new ToolCall("fourth", null, new FunctionCall("lookup", "{}"), null));
		}).verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"choices\":[null]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[null]},\"finish_reason\":\"tool_calls\"}]}",
			"{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"call-1\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}" })
	void malformedChoicesFailWithProtocolErrorAndCancelUpstream(String json) {
		ChatCompletionChunk malformed = new ObjectMapper().readValue(json, ChatCompletionChunk.class);
		AtomicBoolean cancelled = new AtomicBoolean();
		Flux<ChatCompletionChunk> source = Flux.concat(Flux.just(malformed), Flux.never())
			.doOnCancel(() -> cancelled.set(true));
		StepVerifier.create(ResponseMappers.chatStream(this.aggregator.aggregate(source)))
			.expectError(OpenRouterProtocolException.class)
			.verify();
		assertThat(cancelled).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "synthetic_lookup" })
	void missingOrRepeatedNamesPreserveCompleteToolCalls(String initialName) {
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", initialName, "{\"key\":")),
					chunk(toolFragment(0, 1, "call-1", "other_lookup", "{}")),
					chunk(toolFragment(0, 0, null, "synthetic_lookup", "\"value\"}")),
					chunk(toolFragment(0, 0, null, initialName, null)), chunk(finishChoice(0)))))
			.assertNext(value -> assertThat(value.choices().get(0).delta().toolCalls()).containsExactly(
					new ToolCall("call-0", "function", new FunctionCall("synthetic_lookup", "{\"key\":\"value\"}"), 0),
					new ToolCall("call-1", "function", new FunctionCall("other_lookup", "{}"), 1)))
			.verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "lookup", "synthetic_lookup", "different_name" })
	void differingNonblankNamesFailWithoutEmittingPartialCalls(String laterName) {
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "synthetic_", "{")),
					chunk(toolFragment(0, 0, null, laterName, "}")), chunk(finishChoice(0)))))
			.expectErrorMatches(error -> error instanceof OpenRouterProtocolException && error.getMessage()
				.equals("Conflicting streamed tool-call function names; fragmented names are not supported"))
			.verify();
	}

	static Stream<Arguments> unnamedCompletedCalls() {
		return Stream.of(null, "", " ")
			.flatMap(
					name -> Stream.of(
							Arguments.of(Named.of("name=" + quote(name) + " finish on the terminal fragment", name),
									Named.<Function<Choice, Flux<ChatCompletionChunk>>>of("terminal fragment",
											fragment -> Flux.just(
													chunk(new Choice(0, null, fragment.delta(), "tool_calls", null))))),
							Arguments.of(Named.of("name=" + quote(name) + " finish on a separate chunk", name),
									Named.<Function<Choice, Flux<ChatCompletionChunk>>>of("separate chunk",
											fragment -> Flux.just(chunk(fragment), chunk(finishChoice(0)))))));
	}

	@ParameterizedTest
	@MethodSource("unnamedCompletedCalls")
	void completedToolCallRequiresUsableName(String name, Function<Choice, Flux<ChatCompletionChunk>> finishingSource) {
		Flux<ChatCompletionChunk> source = finishingSource.apply(toolFragment(0, 0, "call-0", name, "{}"));

		StepVerifier.create(this.aggregator.aggregate(source))
			.expectErrorMatches(error -> error instanceof OpenRouterProtocolException
					&& "Completed streamed tool call has no function name".equals(error.getMessage()))
			.verify();
	}

	@Test
	void missingToolCallIdUsesEmptySpringAiId() {
		ChatCompletionChunk response = new ObjectMapper().readValue(
				"""
						{"choices":[{"delta":{"tool_calls":[{"type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
						""",
				ChatCompletionChunk.class);
		StepVerifier.create(ResponseMappers.chatStream(this.aggregator.aggregate(Flux.just(response))))
			.assertNext(mapped -> assertThat(mapped.getResult().getOutput().getToolCalls()).singleElement()
				.satisfies(call -> assertThat(call.id()).isEmpty()))
			.verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(strings = { "tool_calls", "function_call" })
	void supportedToolCallTerminatorsProduceExecutableCalls(String reason) {
		StepVerifier.create(ResponseMappers
			.chatStream(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "weather", "{}")),
					chunk(new Choice(0, null, null, reason, null))))))
			.assertNext(response -> {
				assertThat(response.hasToolCalls()).isTrue();
				assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
			})
			.verifyComplete();
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("imagePlacements")
	void imageBearingToolFragmentsRetainMediaAndMatchSynchronousResponse(boolean imagesOnContinuation) {
		ContentPart firstImage = ContentPart.image("data:image/png;base64,AQID");
		ContentPart secondImage = ContentPart.image("data:image/png;base64,BAUG");
		List<ContentPart> firstImages = imagesOnContinuation ? null : List.of(firstImage);
		List<ContentPart> laterImages = imagesOnContinuation ? List.of(firstImage, secondImage) : List.of(secondImage);
		Choice first = new Choice(0, null, new Delta("assistant", "Checking ", "First ",
				List.of(new ToolCall("call-0", "function", new FunctionCall("weather", "{\"city\":"), 0)), firstImages),
				null, null);
		Choice continuation = new Choice(0, null,
				new Delta(null, "both.", "second.",
						List.of(new ToolCall(null, null, new FunctionCall(null, "\"Berlin\"}"), 0)), laterImages),
				null, null);
		Usage usage = new Usage(10, 5, 15, null, null, null, null, null, null);
		ChatCompletionChunk terminal = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(finishChoice(0)), usage, null);
		List<ToolCall> expectedTools = List.of(
				new ToolCall("call-0", "function", new FunctionCall("weather", "{\"city\":\"Berlin\"}"), 0),
				new ToolCall("call-1", "function", new FunctionCall("time", "{\"zone\":\"UTC\"}"), 1));
		ChatMessage message = new ChatMessage("assistant", "Checking both.", null, null, expectedTools,
				List.of(firstImage, secondImage), "First second.", null);
		ChatResponse synchronous = ResponseMappers.chat()
			.map(new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL, "openai",
					List.of(new Choice(0, message, null, "tool_calls", "tool_calls")), usage));

		StepVerifier
			.create(this.aggregator
				.aggregate(Flux.just(chunk(first), chunk(toolFragment(0, 1, "call-1", "time", "{\"zone\":")),
						chunk(continuation), chunk(toolFragment(0, 1, null, null, "\"UTC\"}")), terminal)))
			.assertNext(merged -> {
				Choice choice = merged.choices().get(0);
				assertThat(choice.delta().images()).containsExactly(firstImage, secondImage);
				assertThat(choice.delta().content()).isEqualTo("Checking both.");
				assertThat(choice.delta().reasoning()).isEqualTo("First second.");
				assertThat(choice.delta().toolCalls()).containsExactlyElementsOf(expectedTools);
				assertThat(choice.finishReason()).isEqualTo("tool_calls");
				assertThat(merged.usage()).isEqualTo(usage);

				ChatResponse streamed = ResponseMappers.chunk(merged);
				assertThat(streamed.getResult().getOutput().getMedia()).hasSize(2)
					.allSatisfy(media -> assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_PNG));
				assertThat(streamed.getResult().getOutput().getMedia()).extracting(Media::getData)
					.containsExactly("data:image/png;base64,AQID", "data:image/png;base64,BAUG");
				assertThat(streamed.getResult().getOutput()).usingRecursiveComparison()
					.ignoringFields("media.name")
					.isEqualTo(synchronous.getResult().getOutput());
				assertThat(streamed.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS")
					.isEqualTo(synchronous.getResult().getMetadata().getFinishReason());
				assertThat(streamed.getMetadata().getUsage()).isEqualTo(synchronous.getMetadata().getUsage());
			})
			.verifyComplete();
	}

	static Stream<Arguments> imagePlacements() {
		return Stream.of(Arguments.of(Named.of("images on the first and a later fragment", false)),
				Arguments.of(Named.of("all images repeated on the continuation fragment", true)));
	}

	@Test
	void usageWhileBufferedIsRetainedAfterToolCallTerminator() {
		var usage = new Usage(10, 5, 15, null, null, null, null, null, null);
		var usageChunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", List.of(), usage, null);
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "weather", "{}")),
					usageChunk, chunk(finishChoice(0)))))
			.assertNext(value -> {
				assertThat(value.choices().get(0).finishReason()).isEqualTo("tool_calls");
				assertThat(value.usage()).isEqualTo(usage);
			})
			.verifyComplete();
	}

	@ParameterizedTest
	@MethodSource("singleChoiceUsageOrders")
	void newestUsageAndExtensionsWinForASingleToolChoice(List<ChatCompletionChunk> chunks, int expectedTokens,
			String expectedRevision) {
		List<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.fromIterable(chunks))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).hasSize(1);
		assertThat(totalTokens(aggregated)).containsExactly(expectedTokens);
		assertThat(revisions(aggregated)).containsExactly(expectedRevision);
	}

	static Stream<Arguments> singleChoiceUsageOrders() {
		return Stream.of(
				Arguments.of(Named.of("a usage-only chunk replaces the usage of a buffered fragment",
						List.of(snapshot(10, Map.of("revision", "oldest"),
								toolFragment(0, 0, "call-0", "weather", "{}")),
								snapshot(12, Map.of("revision", "newer")), chunk(finishChoice(0)))),
						12, "newer"),
				Arguments.of(Named.of("a finish chunk replaces the usage of an earlier usage-only chunk",
						List.of(chunk(toolFragment(0, 0, "call-0", "weather", "{}")),
								snapshot(12, Map.of("revision", "older")),
								snapshot(15, Map.of("revision", "newer"), finishChoice(0)))),
						15, "newer"));
	}

	@Test
	void olderSharedUsageIsNotEmittedAfterANewerToolChoiceFinish() {
		List<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.just(
				chunk(toolFragment(0, 0, "call-0", "weather", "{}"), toolFragment(1, 0, "call-1", "weather", "{}")),
				snapshot(12, Map.of("revision", "old")), snapshot(15, Map.of("revision", "new"), finishChoice(0)),
				chunk(finishChoice(1))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).flatExtracting(ChatCompletionChunk::choices)
			.extracting(Choice::index)
			.containsExactly(0, 1);
		assertThat(totalTokens(aggregated)).containsExactly(15);
		assertThat(revisions(aggregated)).containsExactly("new");
	}

	@Test
	void newerSharedUsageStillReachesALaterToolChoiceFinish() {
		List<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.just(
				chunk(toolFragment(0, 0, "call-0", "weather", "{}"), toolFragment(1, 0, "call-1", "weather", "{}")),
				snapshot(12, Map.of("revision", "old"), finishChoice(0)), snapshot(15, Map.of("revision", "new")),
				chunk(finishChoice(1))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).flatExtracting(ChatCompletionChunk::choices)
			.extracting(Choice::index)
			.containsExactly(0, 1);
		assertThat(totalTokens(aggregated)).containsExactly(12, 15);
		assertThat(revisions(aggregated)).containsExactly("old", "new");
	}

	@Test
	void threeInterleavedToolChoicesEmitUsageAndExtensionsInArrivalOrder() {
		List<ChatCompletionChunk> aggregated = this.aggregator
			.aggregate(Flux.just(snapshot(9, Map.of("revision", "r1"), toolFragment(0, 0, "call-0", "weather", "{}"),
					toolFragment(1, 0, "call-1", "weather", "{}"), toolFragment(2, 0, "call-2", "weather", "{")),
					snapshot(11, Map.of("revision", "r2", "route", "b"), toolFragment(2, 0, null, null, "}")),
					snapshot(13, Map.of("revision", "r3"), finishChoice(0)), chunk(finishChoice(1)),
					snapshot(14, Map.of("revision", "r5")), chunk(finishChoice(2))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).flatExtracting(ChatCompletionChunk::choices)
			.extracting(Choice::index)
			.containsExactly(0, 1, 2);
		assertThat(aggregated.get(2).choices().get(0).delta().toolCalls().get(0).function().arguments())
			.isEqualTo("{}");
		assertThat(totalTokens(aggregated)).containsExactly(13, 14);
		assertThat(revisions(aggregated)).containsExactly("r3", "r5");
		assertThat(aggregated.get(2).extensions()).containsEntry("route", "b");
	}

	@Test
	void olderToolChoiceUsageIsNotEmittedAfterANewerTextChoice() {
		List<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.just(
				snapshot(5, Map.of("revision", "old"), textChoice(0, "Sunny"),
						toolFragment(1, 0, "call-1", "weather", "{}")),
				snapshot(15, Map.of("revision", "new"),
						new Choice(0, null, new Delta(null, ".", null, null), "stop", "stop")),
				chunk(finishChoice(1))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).flatExtracting(ChatCompletionChunk::choices)
			.extracting(Choice::index)
			.containsExactly(0, 0, 1);
		assertThat(totalTokens(aggregated)).containsExactly(5, 15);
		assertThat(revisions(aggregated)).containsExactly("old", "new");
	}

	/**
	 * Chunks that belong to a choice other than the buffered one are passed through. The
	 * aggregator must remember only enough of them to decide what a buffered choice may
	 * still report, so neither the byte limit nor the chunk limit applies to them. Each
	 * extension chunk is larger than the 1,024-byte limit and the three of them exceed
	 * the two-chunk limit; a nonzero count would fail the stream.
	 */
	@Test
	void passedThroughChunksDoNotCountAgainstTheRetentionLimits() {
		var bounded = new OpenRouterStreamingToolCallAggregator(new ObjectMapper(), 1024, 2, Duration.ofHours(1));
		List<ChatCompletionChunk> passedThrough = List.of(0, 1, 2)
			.stream()
			.map(index -> snapshot(index, Map.of("key" + index + "x".repeat(1024), index), textChoice(1, "text")))
			.toList();
		Flux<ChatCompletionChunk> source = Flux.concat(Flux.just(chunk(toolFragment(0, 0, "call-0", "lookup", "{}"))),
				Flux.fromIterable(passedThrough), Flux.just(chunk(finishChoice(0))));

		List<ChatCompletionChunk> emitted = bounded.aggregate(source).collectList().block(Duration.ofSeconds(5));

		assertThat(emitted).hasSize(4).containsSequence(passedThrough);
		ChatCompletionChunk finished = emitted.get(3);
		assertThat(finished.choices().get(0).delta().toolCalls()).singleElement()
			.satisfies(call -> assertThat(call.id()).isEqualTo("call-0"));
		assertThat(finished.extensions()).isEmpty();
	}

	@Test
	void choiceLessChunksWhileABufferIsOpenCountAgainstTheByteLimit() {
		var bounded = new OpenRouterStreamingToolCallAggregator(new ObjectMapper(), 1024, 8, Duration.ofHours(1));
		ChatCompletionChunk shared = snapshot(1, Map.of("key" + "x".repeat(1024), 1));

		StepVerifier
			.create(bounded.aggregate(
					Flux.just(chunk(toolFragment(0, 0, "call-0", "lookup", "{}")), shared, chunk(finishChoice(0)))))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit()).isEqualTo(Limit.STREAMING_TOOL_CALL_BYTES);
					assertThat(failure.getConfiguredLimit()).isEqualTo(1024);
					assertThat(failure.getObservedValue()).isGreaterThan(1024);
				}))
			.verify();
	}

	/**
	 * Extensions delivered on a chunk that is passed through supersede older values from
	 * a buffered choice. A key reappearing after every buffer finished, or after an
	 * in-stream error cleared the buffers, must not be compared against stale history.
	 */
	@Test
	void extensionsSupersedeBufferedValuesOnlyWhileTheChoicesThatCarryThemAreBuffered() {
		ChatCompletionChunk error = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", List.of(),
				null, new StreamError("upstream_error", "failed"));
		Flux<ChatCompletionChunk> source = Flux.just(
				snapshot(1, Map.of("first", "old"), toolFragment(0, 0, "call-0", "lookup", "{}")),
				snapshot(2, Map.of("shared", "old")),
				snapshot(3, Map.of("first", "new", "unbuffered", 3), textChoice(2, "text")),
				snapshot(4, Map.of("second", "old"), toolFragment(1, 0, "call-1", "lookup", "{}")),
				snapshot(5, Map.of("shared", "latest")), snapshot(6, Map.of("second", "new"), textChoice(2, ".")),
				chunk(finishChoice(0)), chunk(finishChoice(1)),
				snapshot(7, Map.of("second", "fresh"), toolFragment(0, 0, "call-2", "lookup", "{}")),
				snapshot(8, Map.of("second", "newer"), textChoice(2, ".")), error,
				snapshot(9, Map.of("second", "fresh"), toolFragment(0, 0, "call-3", "lookup", "{}")),
				chunk(finishChoice(0)));

		List<ChatCompletionChunk> completedCalls = this.aggregator.aggregate(source)
			.collectList()
			.block(Duration.ofSeconds(5))
			.stream()
			.filter(emitted -> emitted.choices() != null && !emitted.choices().isEmpty()
					&& emitted.choices().get(0).delta() != null && emitted.choices().get(0).delta().toolCalls() != null)
			.toList();

		assertThat(completedCalls).extracting(emitted -> emitted.choices().get(0).delta().toolCalls().get(0).id())
			.containsExactly("call-0", "call-1", "call-3");
		// "first" and "second" were superseded by newer values on passed-through chunks;
		// "shared" arrived while both choices were buffered, so its latest value reaches
		// both. After the error, nothing from the earlier round may remain.
		assertThat(completedCalls).extracting(ChatCompletionChunk::extensions)
			.containsExactly(Map.of("shared", "latest"), Map.of("shared", "latest"), Map.of("second", "fresh"));
	}

	@Test
	void interleavedChoicesCompleteIndependentlyAndKeepIndexedOrdering() {
		List<ChatCompletionChunk> aggregated = this.aggregator
			.aggregate(Flux.just(chunk(textChoice(1, "one "), textChoice(0, "zero ")),
					chunk(toolFragment(0, 0, "call-0", "weather", "{\"city\":")),
					chunk(toolFragment(1, 1, "call-1b", "time", "{\"zone\":")),
					chunk(toolFragment(1, 0, "call-1a", "weather", "{\"city\":")),
					chunk(toolFragment(0, 0, null, null, "\"Berlin\"}")), chunk(finishChoice(0)),
					chunk(toolFragment(1, 1, null, null, "\"UTC\"}")),
					chunk(toolFragment(1, 0, null, null, "\"Paris\"}")), chunk(finishChoice(1))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).hasSize(3);
		assertThat(aggregated.get(0).choices()).extracting(Choice::index).containsExactly(0, 1);
		assertThat(aggregated.get(0).choices()).extracting(choice -> choice.delta().content())
			.containsExactly("zero ", "one ");

		Choice choice0 = aggregated.get(1).choices().get(0);
		assertThat(choice0.index()).isZero();
		assertThat(choice0.delta().toolCalls().get(0).function().arguments()).isEqualTo("{\"city\":\"Berlin\"}");

		Choice choice1 = aggregated.get(2).choices().get(0);
		assertThat(choice1.index()).isEqualTo(1);
		assertThat(choice1.delta().toolCalls()).extracting(ToolCall::index).containsExactly(0, 1);
		assertThat(choice1.delta().toolCalls()).extracting(call -> call.function().arguments())
			.containsExactly("{\"city\":\"Paris\"}", "{\"zone\":\"UTC\"}");

		assertThat(aggregated.stream()
			.map(ResponseMappers::chunk)
			.map(ChatResponse::getResults)
			.map(generations -> generations.stream()
				.map(generation -> generation.getMetadata().<Integer>get("openrouter.choice_index"))
				.toList()))
			.containsExactly(List.of(0, 1), List.of(0), List.of(1));
	}

	@Test
	void inStreamErrorClearsEveryBufferedChoice() {
		ChatCompletionChunk error = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", List.of(),
				null, new StreamError("upstream_error", "failed"));

		List<ChatCompletionChunk> aggregated = this.aggregator
			.aggregate(Flux.just(chunk(toolFragment(0, 0, "stale-0", "weather", "stale-0")),
					chunk(toolFragment(1, 0, "stale-1", "weather", "stale-1")), error,
					chunk(toolFragment(0, 0, "fresh", "weather", "fresh")), chunk(finishChoice(0))))
			.collectList()
			.block(Duration.ofSeconds(5));

		assertThat(aggregated).hasSize(2);
		assertThat(aggregated.get(0)).isSameAs(error);
		assertThat(aggregated.get(1).choices().get(0).delta().toolCalls().get(0).function().arguments())
			.isEqualTo("fresh");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "{", "{}" })
	void unexpectedCompletionNeverFlushesToolArguments(String arguments) {
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "weather", arguments)))))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@Test
	void completedChoiceDoesNotAuthorizeAnotherChoice() {
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "weather", "{}")),
					chunk(toolFragment(1, 0, "call-1", "weather", "{")), chunk(finishChoice(0)))))
			.assertNext(value -> assertThat(value.choices()).extracting(Choice::index).containsExactly(0))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "stop", "length", "content_filter" })
	void otherFinishReasonsDoNotAuthorizeToolCalls(String reason) {
		StepVerifier
			.create(this.aggregator.aggregate(Flux.just(chunk(toolFragment(0, 0, "call-0", "weather", "{}")),
					chunk(new Choice(0, null, null, reason, null)))))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@Test
	void cancellationReleasesStateBeforeAnotherSubscription() {
		AtomicInteger subscriptions = new AtomicInteger();
		AtomicBoolean firstSubscriptionCancelled = new AtomicBoolean();
		Flux<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.defer(() -> {
			if (subscriptions.incrementAndGet() == 1) {
				return Flux.just(chunk(toolFragment(0, 0, "stale", "weather", "stale")))
					.concatWith(Flux.never())
					.doOnCancel(() -> firstSubscriptionCancelled.set(true));
			}
			return freshCall();
		}));

		StepVerifier.create(aggregated).thenCancel().verify();

		assertThat(firstSubscriptionCancelled).isTrue();
		assertOnlyTheFreshCallIsAssembled(aggregated);
	}

	@Test
	void upstreamErrorReleasesStateBeforeAnotherSubscription() {
		RuntimeException failure = new RuntimeException("stream failed");
		AtomicInteger subscriptions = new AtomicInteger();
		Flux<ChatCompletionChunk> aggregated = this.aggregator.aggregate(Flux.defer(() -> {
			if (subscriptions.incrementAndGet() == 1) {
				return Flux.just(chunk(toolFragment(0, 0, "stale", "weather", "stale")))
					.concatWith(Flux.error(failure));
			}
			return freshCall();
		}));

		StepVerifier.create(aggregated).expectErrorSatisfies(error -> assertThat(error).isSameAs(failure)).verify();

		assertOnlyTheFreshCallIsAssembled(aggregated);
	}

	private static Flux<ChatCompletionChunk> freshCall() {
		return Flux.just(chunk(toolFragment(0, 0, "fresh", "weather", "fresh")), chunk(finishChoice(0)));
	}

	private static void assertOnlyTheFreshCallIsAssembled(Flux<ChatCompletionChunk> aggregated) {
		StepVerifier.create(aggregated).assertNext(chunk -> {
			assertThat(chunk.choices().get(0).delta().toolCalls()).singleElement().satisfies(call -> {
				assertThat(call.id()).isEqualTo("fresh");
				assertThat(call.function().arguments()).isEqualTo("fresh");
			});
		}).verifyComplete();
	}

	@Test
	void preservesChoiceErrorAndPartialOutputWhileToolCallChunksAreBuffered() {
		Choice toolCall = new Choice(0, null, new Delta("assistant", "partial ", null,
				List.of(new ToolCall("call-1", "function", new FunctionCall("lookup", "{"), 0))), null, null);
		ChoiceError error = new ChoiceError("503", "Provider failed during tool call",
				Map.of("error_type", "provider_unavailable"));
		// Deliberately omit finish_reason: the embedded error itself must close the
		// aggregation buffer.
		Choice failed = new Choice(0, null, new Delta(null, "output", null, null), null, null, error);
		ChatCompletionChunk first = chunk(toolCall);
		ChatCompletionChunk terminal = chunk(failed);

		StepVerifier
			.create(ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(Flux.just(first, terminal))))
			.expectErrorSatisfies(thrown -> {
				assertThat(thrown).isInstanceOf(OpenRouterTransientChoiceException.class);
				OpenRouterTransientChoiceException exception = (OpenRouterTransientChoiceException) thrown;
				assertThat(exception.getErrorDetails().partialOutput()).isEqualTo("partial output");
			})
			.verify();
	}

	private static ChatCompletionChunk snapshot(int totalTokens, Map<String, Object> extensions, Choice... choices) {
		return new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", List.of(choices),
				new Usage(null, null, totalTokens, null, null, null, null, null, null), null, extensions);
	}

	private static List<Integer> totalTokens(List<ChatCompletionChunk> chunks) {
		return chunks.stream()
			.map(ChatCompletionChunk::usage)
			.filter(Objects::nonNull)
			.map(Usage::totalTokens)
			.toList();
	}

	private static List<Object> revisions(List<ChatCompletionChunk> chunks) {
		return chunks.stream().map(chunk -> chunk.extensions().get("revision")).filter(Objects::nonNull).toList();
	}

}
