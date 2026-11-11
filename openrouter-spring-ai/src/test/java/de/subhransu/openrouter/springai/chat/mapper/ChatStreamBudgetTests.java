package de.subhransu.openrouter.springai.chat.mapper;

import static de.subhransu.openrouter.springai.chat.mapper.StreamingMapperSupport.chatMapper;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingMapperSupport.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ChatStreamBudgetTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final long DIAGNOSTIC = 4L * OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH;

	private static final String UNICODE = "é😀";

	@Test
	void concurrentSubscriptionsToOneStreamEachGetTheirOwnBudgetAndReasoningState() {
		// The budget admits exactly three reasoning deltas. One shared budget (or one
		// shared reasoning accumulator) would be exhausted by the first few of 16
		// subscriptions that run in parallel on the same mapped stream.
		var chunk = chunk("{\"index\":0,\"delta\":{\"reasoning\":\"x\"}}");
		long charge = JSON.writeValueAsBytes(Map.of(ReasoningMetadata.REASONING, "x")).length;
		var stream = map(chatMapper(DIAGNOSTIC + charge * 3, 1), Flux.just(chunk, chunk, chunk));

		List<List<Object>> perSubscription = Flux.range(0, 16)
			.flatMap(index -> stream.subscribeOn(Schedulers.parallel())
				.map(response -> response.getResult().getOutput().getMetadata().get(ReasoningMetadata.REASONING))
				.collectList(), 16)
			.collectList()
			.block(Duration.ofSeconds(30));

		assertThat(perSubscription).hasSize(16)
			.allSatisfy(reasoning -> assertThat(reasoning).containsExactly("x", "xx", "xxx"));
	}

	@Test
	void acceptsExactUtf8BoundaryAndCancelsBeforeNextSnapshot() {
		Utf8Budget budget = new Utf8Budget();

		budget.assertExactBoundaryThenLimit();
	}

	@Test
	void aSubscriptionAfterAnExhaustedOneStartsWithAFreshBudget() {
		Utf8Budget budget = new Utf8Budget();
		budget.stream.onErrorResume(OpenRouterLimitExceededException.class, error -> Flux.empty())
			.collectList()
			.block(Duration.ofSeconds(5));

		budget.assertExactBoundaryThenLimit();
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"index\":0,\"delta\":{\"reasoning\":\"x\"}}",
			"{\"index\":0,\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.encrypted\",\"data\":\"x\"}]}}",
			"{\"index\":0,\"delta\":{\"annotations\":[{\"opaque\":\"x\"}]}}",
			"{\"index\":0,\"delta\":{},\"logprobs\":{\"content\":[{\"token\":\"x\"}]}}" })
	void smallMetadataDeltasCannotBypassBudget(String choice) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.create(map(chatMapper(DIAGNOSTIC + 256, 1),
					Flux.range(0, 1200).map(index -> chunk(choice)).doOnCancel(() -> cancelled.set(true))))
			.thenConsumeWhile(response -> true)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void rootExtensionsAreChargedWithoutChoices() {
		var chunk = JSON.readValue("{\"choices\":[],\"opaque\":\"é\"}", ChatCompletionChunk.class);
		long charge = JSON.writeValueAsBytes(chunk.extensions()).length;
		StepVerifier.create(map(chatMapper(charge, 1), Flux.just(chunk, chunk)))
			.expectNextCount(1)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
	}

	@Test
	void manySmallRootExtensionsCannotBypassTheBudget() {
		StepVerifier
			.create(map(chatMapper(100, 1), Flux.range(0, 100)
				.map(index -> JSON.readValue("{\"choices\":[],\"key" + index + "\":true}", ChatCompletionChunk.class))))
			.thenConsumeWhile(response -> true)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
	}

	@Test
	void boundsActiveChoices() {
		var first = chunk("{\"index\":0,\"delta\":{\"content\":\"a\"}}");
		var second = chunk("{\"index\":1,\"delta\":{\"content\":\"b\"}}");
		StepVerifier.create(map(chatMapper(DIAGNOSTIC, 1), Flux.just(first, second)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(((OpenRouterLimitExceededException) error).getLimit())
				.isEqualTo(Limit.CHAT_STATE_CHOICES))
			.verify();
	}

	@Test
	void releasesFinishedChoicesSoALaterChoiceFits() {
		var first = chunk("{\"index\":0,\"delta\":{\"content\":\"a\"}}");
		var second = chunk("{\"index\":1,\"delta\":{\"content\":\"b\"}}");
		var finish = chunk("{\"index\":0,\"finish_reason\":\"stop\"}");
		StepVerifier.create(map(chatMapper(DIAGNOSTIC, 1), Flux.just(first, finish, second)))
			.expectNextCount(3)
			.verifyComplete();
	}

	@Test
	void twoActiveChoicesStillRespectTheByteBudget() {
		var first = chunk("{\"index\":0,\"delta\":{\"content\":\"a\"}}");
		var second = chunk("{\"index\":1,\"delta\":{\"content\":\"b\"}}");
		StepVerifier.create(map(chatMapper(DIAGNOSTIC, 2), Flux.just(first, second)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(((OpenRouterLimitExceededException) error).getLimit())
				.isEqualTo(Limit.CHAT_STATE_BYTES))
			.verify();
	}

	@Test
	void releasesReasoningChargesAndKeepsChoiceSnapshotsIsolated() {
		var first = chunk("{\"index\":0,\"delta\":{\"reasoning\":\"a\"}}");
		var second = chunk("{\"index\":1,\"delta\":{\"reasoning\":\"b\"}}");
		var finish = chunk("{\"index\":0,\"finish_reason\":\"stop\"}");
		long charge = JSON.writeValueAsBytes(Map.of(ReasoningMetadata.REASONING, "a")).length;
		var mapper = chatMapper(2 * (DIAGNOSTIC + charge), 2);
		StepVerifier.create(map(mapper, Flux.just(first, second, first)))
			.expectNextCount(2)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
		var secondFinished = chunk("{\"index\":1,\"delta\":{\"reasoning\":\"b\"},\"finish_reason\":\"stop\"}");
		var responses = map(mapper, Flux.just(first, second, finish, secondFinished, first)).collectList()
			.block(Duration.ofSeconds(5));
		assertThat(responses).hasSize(5);
		assertThat(responses.get(0).getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
				"a");
		assertThat(responses.get(1).getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
				"b");
		assertThat(responses.get(2).getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
				"a");
		assertThat(responses.get(3).getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
				"bb");
		assertThat(responses.get(4).getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
				"a");
	}

	@Test
	void textRemainsIncrementalAndHonorsDemand() {
		AtomicBoolean cancelled = new AtomicBoolean();
		var chunk = chunk("{\"index\":0,\"delta\":{\"content\":\"hello\"}}");
		StepVerifier
			.create(map(chatMapper(DIAGNOSTIC, 1),
					Flux.range(0, 10000).map(index -> chunk).doOnCancel(() -> cancelled.set(true))), 0)
			.thenRequest(2000)
			.expectNextCount(2000)
			.thenCancel()
			.verify();
		assertThat(cancelled).isTrue();
		StepVerifier.create(map(chatMapper(DIAGNOSTIC, 1), Flux.just(chunk, chunk)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hello"))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hello"))
			.verifyComplete();
	}

	@Test
	void providerFailurePreservesEarlierAndCurrentDiagnosticsBeforeExtensionAdmission() {
		var first = chunk("{\"index\":0,\"delta\":{\"content\":\"before \"}}");
		var failed = JSON.readValue("""
				{"opaque":"extension exceeding the remaining budget",
				 "choices":[{"index":0,"delta":{"content":"failure"},
				 "error":{"code":"502","message":"synthetic"}}]}
				""", ChatCompletionChunk.class);
		StepVerifier.create(map(chatMapper(DIAGNOSTIC, 1), Flux.just(first, failed)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(
					OpenRouterTransientChoiceException.class,
					failure -> assertThat(failure.getErrorDetails().partialOutput()).isEqualTo("before failure")))
			.verify();
	}

	static Stream<Arguments> providerFailuresUnderTightLimits() {
		return Stream.of(
				Arguments.of(Named.of("502 with 1 byte and 2 choices", 502), 1L, 2,
						OpenRouterTransientChoiceException.class),
				Arguments.of(Named.of("502 with 4096 bytes and 1 choice", 502), 4096L, 1,
						OpenRouterTransientChoiceException.class),
				Arguments.of(Named.of("400 with 1 byte and 2 choices", 400), 1L, 2,
						OpenRouterNonTransientChoiceException.class),
				Arguments.of(Named.of("400 with 4096 bytes and 1 choice", 400), 4096L, 1,
						OpenRouterNonTransientChoiceException.class));
	}

	@ParameterizedTest
	@MethodSource("providerFailuresUnderTightLimits")
	void providerFailureTakesPrecedenceOverByteAndChoiceLimits(int status, long maxBytes, int maxChoices,
			Class<? extends Throwable> expected) {
		var failed = JSON.readValue("""
				{"choices":[{"index":0,"delta":{"content":"sibling"}},
				 {"index":1,"delta":{"content":"partial"},"error":{"code":"%s","message":"synthetic"}}]}
				""".formatted(status), ChatCompletionChunk.class);
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.create(map(chatMapper(maxBytes, maxChoices), Flux.just(failed).doOnCancel(() -> cancelled.set(true))))
			.expectError(expected)
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void rejectsNonPositiveLimits() {
		assertThatThrownBy(() -> chatMapper(0, 1)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Maximum Chat Completions state size must be greater than zero");
		assertThatThrownBy(() -> chatMapper(1, 0)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Maximum Chat Completions active choices must be greater than zero");
	}

	private static ChatCompletionChunk chunk(String choice) {
		return JSON.readValue("{\"choices\":[" + choice + "]}", ChatCompletionChunk.class);
	}

	/** A reasoning stream whose third snapshot is the first one over the budget. */
	private static final class Utf8Budget {

		private final long charge = JSON.writeValueAsBytes(Map.of(ReasoningMetadata.REASONING, UNICODE)).length;

		private final AtomicBoolean cancelled = new AtomicBoolean();

		private final Flux<ChatResponse> stream;

		Utf8Budget() {
			ChatCompletionChunk chunk = chunk("{\"index\":0,\"delta\":{\"reasoning\":\"" + UNICODE + "\"}}");
			this.stream = map(chatMapper(DIAGNOSTIC + this.charge * 2, 1),
					Flux.just(chunk, chunk, chunk).doOnCancel(() -> this.cancelled.set(true)));
		}

		void assertExactBoundaryThenLimit() {
			// Anchor the measured charge to a literal document: two-byte and four-byte
			// characters count as bytes, not as characters.
			String literal = "{\"" + ReasoningMetadata.REASONING + "\":\"" + UNICODE + "\"}";
			assertThat(this.charge).isEqualTo(literal.getBytes(StandardCharsets.UTF_8).length)
				.isGreaterThan(literal.length());
			this.cancelled.set(false);
			StepVerifier.create(this.stream)
				.assertNext(response -> assertThat(response.getResult().getOutput().getMetadata())
					.containsEntry(ReasoningMetadata.REASONING, UNICODE))
				.assertNext(response -> assertThat(response.getResult().getOutput().getMetadata())
					.containsEntry(ReasoningMetadata.REASONING, UNICODE + UNICODE))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(OpenRouterLimitExceededException.class);
					var limit = (OpenRouterLimitExceededException) error;
					assertThat(limit.getLimit()).isEqualTo(Limit.CHAT_STATE_BYTES);
					assertThat(limit.getConfiguredLimit()).isEqualTo(DIAGNOSTIC + this.charge * 2);
					assertThat(limit.getObservedValue()).isEqualTo(DIAGNOSTIC + this.charge * 3);
				})
				.verify();
			assertThat(this.cancelled).isTrue();
		}

	}

}
