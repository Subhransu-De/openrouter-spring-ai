package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Named;
import java.util.stream.Stream;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ResponsesStreamLimitsTests {

	private static final String REASONING_DELTA = "response.reasoning_text.delta";

	private final ObjectMapper json = new ObjectMapper();

	private final ResponsesStreamEvent done = event("""
			{"type":"response.completed","response":{"status":"completed","output":[]}}
			""");

	@ParameterizedTest
	@ValueSource(strings = { REASONING_DELTA, "response.reasoning_summary_text.delta",
			"response.function_call_arguments.delta", "response.refusal.delta", "response.output_text.delta" })
	void manySmallFragmentsRespectExactByteBoundary(String type) {
		ResponsesStreamEvent fragment = new ResponsesStreamEvent(type, "é", null, null, null);
		var terminal = "response.output_text.delta".equals(type) ? textDone(fragment.delta().repeat(10)) : this.done;
		long bytes = 10L * this.json.writeValueAsBytes(fragment).length + this.json.writeValueAsBytes(terminal).length;
		Flux<ResponsesStreamEvent> events = Flux.range(0, 10).map(i -> fragment).concatWithValues(terminal);
		StepVerifier.create(mapper(bytes, 11).map(events)).expectNextCount(11).verifyComplete();
		StepVerifier.create(mapper(bytes - 1, 11).map(events))
			.expectNextCount(10)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_BYTES, bytes - 1))
			.verify();
	}

	// The other boundary tests measure the charge with the same ObjectMapper the mapper
	// uses; this one fixes it to literal byte counts of ASCII JSON, so a change in what
	// is charged cannot pass by changing the serializer with it.
	@Test
	void chargeEqualsTheLiteralUtf8LengthOfEachSerializedEvent() {
		String fragmentJson = "{\"type\":\"response.reasoning_text.delta\",\"delta\":\"a\"}";
		String doneJson = "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}";
		assertThat(fragmentJson.getBytes(StandardCharsets.UTF_8)).hasSize(52);
		assertThat(this.json.writeValueAsString(new ResponsesStreamEvent(REASONING_DELTA, "a", null, null, null)))
			.isEqualTo(fragmentJson);
		assertThat(this.json.writeValueAsString(this.done)).isEqualTo(doneJson);
		long bytes = 52 + doneJson.getBytes(StandardCharsets.UTF_8).length;
		Flux<ResponsesStreamEvent> events = Flux.just(event(fragmentJson), this.done);

		StepVerifier.create(mapper(bytes, 2).map(events)).expectNextCount(2).verifyComplete();
		StepVerifier.create(mapper(bytes - 1, 2).map(events))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_BYTES, bytes - 1))
			.verify();
	}

	@Test
	void chunksIncludeTextAndKeepalives() {
		var fragment = new ResponsesStreamEvent(REASONING_DELTA, "a", null, null, null);
		var ping = new ResponsesStreamEvent("ping", null, null, null, null);
		Flux<ResponsesStreamEvent> events = Flux.just(
				new ResponsesStreamEvent("response.output_text.delta", "a", null, null, null), fragment, ping,
				textDone("a"));
		StepVerifier.create(mapper(10000, 4).map(events)).expectNextCount(4).verifyComplete();
		StepVerifier.create(mapper(10000, 3).map(events))
			.expectNextCount(3)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_CHUNKS, 3))
			.verify();
	}

	@Test
	void terminalSnapshotCannotBypassItemLimitOrReleaseTools() {
		var terminal = event("""
				{"type":"response.completed","response":{"status":"completed","output":[
				{"type":"reasoning","id":"r1","encrypted_content":"synthetic"},
				{"type":"function_call","call_id":"c1","name":"lookup","arguments":"{}"}]}}
				""");
		StepVerifier.create(mapper(10000, 1).map(Flux.just(terminal)))
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_ITEMS, 1))
			.verify();
		StepVerifier.create(mapper(10000, 2).map(Flux.just(terminal))).assertNext(response -> {
			assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
			assertThat(response.getResult().getOutput().getMetadata().get("openrouter.responses.output_items"))
				.isEqualTo(terminal.response().output());
		}).verifyComplete();
	}

	@Test
	void manyDoneItemsAndLargeUnfinishedCallAreBounded() {
		var item = event("""
				{"type":"response.output_item.done","item":{"type":"reasoning","id":"r1"}}
				""");
		StepVerifier.create(mapper(10000, 3).map(Flux.range(0, 4).map(i -> item)))
			.expectNextCount(3)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_CHUNKS, 3))
			.verify();
		var argument = new ResponsesStreamEvent("response.function_call_arguments.delta", "x".repeat(2048), null, null,
				null);
		StepVerifier.create(mapper(1024, 10).map(Flux.just(argument)))
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_BYTES, 1024))
			.verify();
	}

	static Stream<Arguments> upstreamsAfterTheFirstEvent() {
		Supplier<Flux<ResponsesStreamEvent>> silent = () -> Flux.never();
		Supplier<Flux<ResponsesStreamEvent>> keepalives = () -> Flux.interval(Duration.ofSeconds(1))
			.map(i -> new ResponsesStreamEvent("ping", null, null, null, null));
		return Stream.of(Arguments.of(Named.of("silent upstream", silent)),
				Arguments.of(Named.of("upstream sending keep-alives", keepalives)));
	}

	@ParameterizedTest
	@MethodSource("upstreamsAfterTheFirstEvent")
	void absoluteDeadlineCancelsSilentOrActiveUpstream(Supplier<Flux<ResponsesStreamEvent>> tail) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.withVirtualTime(() -> mapper(10000, 100).map(
					Flux.just(new ResponsesStreamEvent("response.function_call_arguments.delta", "{", null, null, null))
						.concatWith(tail.get())
						.doOnCancel(() -> cancelled.set(true))))
			.expectNextCount(1)
			.thenAwait(Duration.ofSeconds(5))
			.thenConsumeWhile(response -> true)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_DURATION, 5000))
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void cancelledSubscriptionDoesNotContaminateOtherSubscriptions() {
		var mapper = mapper(10000, 2);
		var fragment = new ResponsesStreamEvent(REASONING_DELTA, "synthetic", null, null, null);
		var stream = mapper.map(Flux.just(fragment, this.done));
		StepVerifier.create(stream).expectNextCount(1).thenCancel().verify();

		StepVerifier.create(Flux.merge(stream, stream)).expectNextCount(4).verifyComplete();
	}

	@Test
	void failedSubscriptionDoesNotContaminateOtherSubscriptions() {
		var mapper = mapper(10000, 2);
		var fragment = new ResponsesStreamEvent(REASONING_DELTA, "synthetic", null, null, null);
		StepVerifier
			.create(mapper.map(Flux.just(fragment).concatWith(Mono.error(new IllegalStateException("synthetic")))))
			.expectNextCount(1)
			.expectError(IllegalStateException.class)
			.verify();

		var stream = mapper.map(Flux.just(fragment, this.done));
		StepVerifier.create(Flux.merge(stream, stream)).expectNextCount(4).verifyComplete();
	}

	@Test
	void deadlineStartsWithStateAndTerminalCompletionCancelsUpstream() {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.withVirtualTime(() -> mapper(10000, 10)
				.map(Flux.just(new ResponsesStreamEvent("response.output_text.delta", "synthetic", null, null, null))
					.concatWith(Mono.delay(Duration.ofSeconds(1))
						.map(ignored -> new ResponsesStreamEvent(REASONING_DELTA, "reasoning", null, null, null)))
					.concatWithValues(textDone("synthetic"))
					.concatWith(Flux.never())
					.doOnCancel(() -> cancelled.set(true))))
			.expectNextCount(1)
			.thenAwait(Duration.ofSeconds(1))
			.expectNextCount(2)
			.verifyComplete();
		assertThat(cancelled).isTrue();
	}

	@Test
	void overlappingSubscriptionsRetainIndependentReasoning() {
		StepVerifier.withVirtualTime(() -> {
			var mapper = mapper(10000, 3);
			var events = Flux
				.just(new ResponsesStreamEvent(REASONING_DELTA, "a", null, null, null),
						new ResponsesStreamEvent(REASONING_DELTA, "b", null, null, null), this.done)
				.delayElements(Duration.ofSeconds(1));
			var responses = mapper.map(events)
				.map(response -> response.getResult().getOutput().getMetadata().get("openrouter.reasoning"));
			return Flux.merge(responses, responses);
		}).thenAwait(Duration.ofSeconds(3)).expectNext("a", "a", "ab", "ab", "ab", "ab").verifyComplete();
	}

	@Test
	void modelBuilderAppliesChunkLimitToResponses() {
		var model = OpenRouterChatModel.builder()
			.openRouterApi(responsesApi(Flux.just(reasoningFragment(), this.done)))
			.toolCallAggregationMaxChunks(1)
			.build();
		StepVerifier.create(model.stream(responsesPrompt()))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_CHUNKS, 1))
			.verify();
	}

	@Test
	void modelBuilderAppliesSizeLimitToResponses() {
		long fragmentBytes = this.json.writeValueAsBytes(reasoningFragment()).length;
		var atLimit = OpenRouterChatModel.builder()
			.openRouterApi(responsesApi(Flux.just(reasoningFragment(), this.done)))
			.toolCallAggregationMaxBytes(fragmentBytes + this.json.writeValueAsBytes(this.done).length)
			.build();
		StepVerifier.create(atLimit.stream(responsesPrompt())).expectNextCount(2).verifyComplete();

		var overLimit = OpenRouterChatModel.builder()
			.openRouterApi(responsesApi(Flux.just(reasoningFragment(), this.done)))
			.toolCallAggregationMaxBytes(fragmentBytes)
			.build();
		StepVerifier.create(overLimit.stream(responsesPrompt()))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_BYTES, fragmentBytes))
			.verify();
	}

	@Test
	void modelBuilderAppliesDurationLimitToResponses() {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.withVirtualTime(() -> OpenRouterChatModel.builder()
				.openRouterApi(responsesApi(
						Flux.just(reasoningFragment()).concatWith(Flux.never()).doOnCancel(() -> cancelled.set(true))))
				.toolCallAggregationMaxDuration(Duration.ofSeconds(7))
				.build()
				.stream(responsesPrompt()))
			.expectNextCount(1)
			.expectNoEvent(Duration.ofSeconds(6))
			.thenAwait(Duration.ofSeconds(1))
			.expectErrorSatisfies(error -> assertLimit(error, Limit.RESPONSES_STATE_DURATION, 7000))
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void rejectsInvalidLimits() {
		assertThatThrownBy(() -> mapper(0, 1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Maximum Responses state size must be greater than zero");
		assertThatThrownBy(() -> mapper(1, 0)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Maximum Responses state chunk count must be greater than zero");
		assertThatThrownBy(() -> new OpenRouterResponsesStreamingResponseMapper(this.json, 1, 1, Duration.ZERO,
				OpenRouterDiagnosticSanitizer.keyless()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Maximum Responses state duration must be greater than zero");
	}

	private ResponsesStreamEvent reasoningFragment() {
		return new ResponsesStreamEvent(REASONING_DELTA, "synthetic", null, null, null);
	}

	private static OpenRouterApi responsesApi(Flux<ResponsesStreamEvent> events) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any())).thenReturn(events);
		return api;
	}

	private static Prompt responsesPrompt() {
		return new Prompt("synthetic",
				OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build());
	}

	private OpenRouterResponsesStreamingResponseMapper mapper(long bytes, int chunks) {
		return new OpenRouterResponsesStreamingResponseMapper(this.json, bytes, chunks, Duration.ofSeconds(5),
				OpenRouterDiagnosticSanitizer.keyless());
	}

	private ResponsesStreamEvent textDone(String text) {
		return event("""
				{"type":"response.completed","response":{"status":"completed","output":[
				{"type":"message","content":[{"type":"output_text","text":%s}]}]}}
				""".formatted(this.json.writeValueAsString(text)));
	}

	private ResponsesStreamEvent event(String json) {
		return this.json.readValue(json, ResponsesStreamEvent.class);
	}

	private void assertLimit(Throwable error, Limit limit, long configured) {
		assertThat(error).isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
			assertThat(failure.getLimit()).isEqualTo(limit);
			assertThat(failure.getConfiguredLimit()).isEqualTo(configured);
			assertThat(failure.getObservedValue()).isGreaterThanOrEqualTo(configured);
			assertThat(failure.getEndpoint()).isEqualTo("/responses");
		});
	}

}
