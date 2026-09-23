package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ChatStreamBudgetTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final long DIAGNOSTIC = 4L * OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH;

	@Test
	void acceptsExactUtf8BoundaryAndCancelsBeforeNextSnapshot() {
		ChatCompletionChunk chunk = chunk("{\"index\":0,\"delta\":{\"reasoning\":\"é😀\"}}");
		long charge = JSON.writeValueAsBytes(Map.of(ReasoningMetadata.REASONING, "é😀")).length;
		AtomicBoolean cancelled = new AtomicBoolean();
		var stream = new OpenRouterStreamingResponseMapper(DIAGNOSTIC + charge * 2, 1)
			.map(Flux.just(chunk, chunk, chunk).doOnCancel(() -> cancelled.set(true)));
		for (int subscription = 0; subscription < 2; subscription++) {
			cancelled.set(false);
			StepVerifier.create(stream)
				.assertNext(response -> assertThat(response.getResult().getOutput().getMetadata())
					.containsEntry(ReasoningMetadata.REASONING, "é😀"))
				.assertNext(response -> assertThat(response.getResult().getOutput().getMetadata())
					.containsEntry(ReasoningMetadata.REASONING, "é😀é😀"))
				.expectErrorSatisfies(error -> {
					assertThat(error).isInstanceOf(OpenRouterLimitExceededException.class);
					var limit = (OpenRouterLimitExceededException) error;
					assertThat(limit.getLimit()).isEqualTo(Limit.CHAT_STATE_BYTES);
					assertThat(limit.getConfiguredLimit()).isEqualTo(DIAGNOSTIC + charge * 2);
					assertThat(limit.getObservedValue()).isEqualTo(DIAGNOSTIC + charge * 3);
				})
				.verify();
			assertThat(cancelled).isTrue();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"index\":0,\"delta\":{\"reasoning\":\"x\"}}",
			"{\"index\":0,\"delta\":{\"reasoning_details\":[{\"type\":\"reasoning.encrypted\",\"data\":\"x\"}]}}",
			"{\"index\":0,\"delta\":{\"annotations\":[{\"opaque\":\"x\"}]}}",
			"{\"index\":0,\"delta\":{},\"logprobs\":{\"content\":[{\"token\":\"x\"}]}}" })
	void smallMetadataDeltasCannotBypassBudget(String choice) {
		AtomicBoolean cancelled = new AtomicBoolean();
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper(DIAGNOSTIC + 256, 1)
				.map(Flux.range(0, 1200).map(index -> chunk(choice)).doOnCancel(() -> cancelled.set(true))))
			.thenConsumeWhile(response -> true)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
		assertThat(cancelled).isTrue();
	}

	@Test
	void rootExtensionsAreChargedWithoutChoices() {
		var chunk = JSON.readValue("{\"choices\":[],\"opaque\":\"é\"}", ChatCompletionChunk.class);
		long charge = JSON.writeValueAsBytes(chunk.extensions()).length;
		StepVerifier.create(new OpenRouterStreamingResponseMapper(charge, 1).map(Flux.just(chunk, chunk)))
			.expectNextCount(1)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
		StepVerifier
			.create(new OpenRouterStreamingResponseMapper(100, 1).map(Flux.range(0, 100)
				.map(index -> JSON.readValue("{\"choices\":[],\"key" + index + "\":true}", ChatCompletionChunk.class))))
			.thenConsumeWhile(response -> true)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
	}

	@Test
	void boundsActiveChoicesAndReleasesFinishedChoices() {
		var first = chunk("{\"index\":0,\"delta\":{\"content\":\"a\"}}");
		var second = chunk("{\"index\":1,\"delta\":{\"content\":\"b\"}}");
		var finish = chunk("{\"index\":0,\"finish_reason\":\"stop\"}");
		var mapper = new OpenRouterStreamingResponseMapper(DIAGNOSTIC, 1);
		StepVerifier.create(mapper.map(Flux.just(first, second)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(((OpenRouterLimitExceededException) error).getLimit())
				.isEqualTo(Limit.CHAT_STATE_CHOICES))
			.verify();
		StepVerifier.create(mapper.map(Flux.just(first, finish, second))).expectNextCount(3).verifyComplete();
		StepVerifier.create(new OpenRouterStreamingResponseMapper(DIAGNOSTIC, 2).map(Flux.just(first, second)))
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
		var mapper = new OpenRouterStreamingResponseMapper(2 * (DIAGNOSTIC + charge), 2);
		StepVerifier.create(mapper.map(Flux.just(first, second, first)))
			.expectNextCount(2)
			.expectError(OpenRouterLimitExceededException.class)
			.verify();
		var secondFinished = chunk("{\"index\":1,\"delta\":{\"reasoning\":\"b\"},\"finish_reason\":\"stop\"}");
		var responses = mapper.map(Flux.just(first, second, finish, secondFinished, first)).collectList().block();
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
			.create(new OpenRouterStreamingResponseMapper(DIAGNOSTIC, 1)
				.map(Flux.range(0, 10000).map(index -> chunk).doOnCancel(() -> cancelled.set(true))), 0)
			.thenRequest(2000)
			.expectNextCount(2000)
			.thenCancel()
			.verify();
		assertThat(cancelled).isTrue();
		StepVerifier.create(new OpenRouterStreamingResponseMapper(DIAGNOSTIC, 1).map(Flux.just(chunk, chunk)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hello"))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("hello"))
			.verifyComplete();
	}

	@Test
	void rejectsNonPositiveLimits() {
		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper(0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper(1, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static ChatCompletionChunk chunk(String choice) {
		return JSON.readValue("{\"choices\":[" + choice + "]}", ChatCompletionChunk.class);
	}

}
