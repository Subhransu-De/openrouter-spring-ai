package de.subhransu.openrouter.springai.chat;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.PromptMetadata;
import org.springframework.ai.chat.metadata.RateLimit;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/**
 * Micrometer observability contract: every {@code call()} and {@code stream()} is
 * recorded as a {@code gen_ai.client.operation} observation against the configured
 * {@link io.micrometer.observation.ObservationRegistry}, carrying the OpenRouter provider
 * tag, and stream errors are recorded on the observation instead of being dropped. This
 * is what makes ChatClient metrics and tracing work for this provider.
 */
class OpenRouterChatModelObservationTests {

	private static final String MODEL = "openai/gpt-5.4-mini";

	private static final String OBSERVATION_NAME = "gen_ai.client.operation";

	private static final String PROVIDER_KEY = "gen_ai.system";

	private final TestObservationRegistry observationRegistry = TestObservationRegistry.create();

	private OpenRouterChatModel model(OpenRouterApi api) {
		return OpenRouterChatModel.builder().openRouterApi(api).observationRegistry(this.observationRegistry).build();
	}

	private Prompt prompt() {
		return new Prompt(List.of(new UserMessage("hi")), OpenRouterChatOptions.builder().model(MODEL).build());
	}

	@Test
	void callRecordsAChatModelObservationWithProviderAndModel() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("gen-1", "chat.completion", 123L, MODEL,
				"openai",
				List.of(new Choice(0, new ChatMessage("assistant", "Hello.", null, null, null), null, "stop", "stop")),
				null));

		model(api).call(prompt());

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue(PROVIDER_KEY, "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void streamRecordsAnObservationStoppedAfterTheFluxCompletes() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, MODEL, "openai",
					List.of(new Choice(0, null, new Delta("assistant", "Hello.", null, null), "stop", "stop")), null,
					null)));

		model(api).stream(prompt()).collectList().block(Duration.ofSeconds(5));

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue(PROVIDER_KEY, "openrouter")
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@Test
	void streamErrorIsRecordedOnTheObservation() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux
			.error(new OpenRouterApiException("stream failed", HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":true}")));

		OpenRouterChatModel model = model(api);
		assertThatThrownBy(() -> model.stream(prompt()).collectList().block(Duration.ofSeconds(5)))
			.isInstanceOf(OpenRouterApiException.class);

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterApiException.class);
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void streamObservationKeepsChoicesSeparate(boolean packed) {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		Flux<ChatCompletionChunk> chunks = packed
				? chunks("""
						{"choices":[{"index":0,"delta":{"content":"A"}},{"index":1,"delta":{"content":"B"}}]}
						""",
						"""
								{"choices":[{"index":1,"delta":{"content":"Y"},"finish_reason":"length"},{"index":0,"delta":{"content":"X"},"finish_reason":"stop"}]}
								""")
				: chunks("""
						{"choices":[{"index":0,"delta":{"content":"A"}}]}
						""", """
						{"choices":[{"index":1,"delta":{"content":"B"}}]}
						""", """
						{"choices":[{"index":1,"delta":{"content":"Y"},"finish_reason":"length"}]}
						""", """
						{"choices":[{"index":0,"delta":{"content":"X"},"finish_reason":"stop"}]}
						""");
		when(api.chatCompletionStream(any())).thenReturn(chunks);
		Flux<ChatResponse> stream = model(api).stream(prompt());
		for (int subscription = 0; subscription < 2; subscription++) {
			StepVerifier.create(stream)
				.recordWith(ArrayList::new)
				.expectNextCount(packed ? 2 : 4)
				.consumeRecordedWith(responses -> assertThat(responses.stream()
					.flatMap(response -> response.getResults().stream())
					.map(generation -> generation.getOutput().getText()))
					.containsExactly("A", "B", packed ? "X" : "Y", packed ? "Y" : "X"))
				.verifyComplete();
		}
		assertThat(observed).hasSize(2);
		for (ChatResponse response : observed) {
			assertThat(response.getResults()).hasSize(2);
			assertThat(response.getResults()).extracting(generation -> generation.getOutput().getText())
				.containsExactly("AX", "BY");
			assertThat(response.getResults()).extracting(generation -> generation.getMetadata().getFinishReason())
				.containsExactly("STOP", "LENGTH");
			assertThat(response.getResults())
				.extracting(generation -> generation.getMetadata().get("openrouter.choice_index"))
				.containsExactly(0, 1);
		}
	}

	@Test
	void streamObservationRetainsProviderAndResponseExtensions() {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks("""
				{"id":"synthetic","model":"test-model","provider":"synthetic-provider","created":123,
				 "object":"chat.completion.chunk","routing":{"region":"synthetic"},
				 "choices":[{"index":0,"delta":{"content":"A"}}]}
				""", """
				{"choices":[{"index":0,"delta":{"content":"X"},"finish_reason":"stop"}]}
				""", """
				{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,"cost":0.003,
				 "prompt_tokens_details":{"cached_tokens":3,"cache_write_tokens":7}}}
				"""));
		StepVerifier.create(model(api).stream(prompt())).expectNextCount(3).verifyComplete();
		assertThat(observed).hasSize(1);
		var metadata = observed.get(0).getMetadata();
		assertThat(metadata.getId()).isEqualTo("synthetic");
		assertThat(metadata.getModel()).isEqualTo("test-model");
		assertThat((Object) metadata.get("openrouter.provider")).isEqualTo("synthetic-provider");
		assertThat((Object) metadata.get("openrouter.created")).isEqualTo(123L);
		assertThat((Object) metadata.get("openrouter.object")).isEqualTo("chat.completion.chunk");
		assertThat((Object) metadata.get("openrouter.response.extensions"))
			.isEqualTo(java.util.Map.of("routing", java.util.Map.of("region", "synthetic")));
		assertThat(metadata.getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class, usage -> {
			assertThat(usage.getCost()).isEqualTo(0.003);
			assertThat(usage.getCacheReadInputTokens()).isEqualTo(3L);
			assertThat(usage.getCacheWriteInputTokens()).isEqualTo(7L);
		});
	}

	@Test
	void singleChoiceObservationRetainsTextToolsAndReasoning() {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks("""
				{"choices":[{"delta":{"content":"A","reasoning":"thinking"}}]}
				""", """
				{"choices":[{"delta":{"content":"X","tool_calls":[{"index":0,"id":"synthetic-tool","type":"function",
				 "function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
				"""));
		StepVerifier.create(model(api).stream(prompt())).expectNextCount(2).verifyComplete();
		assertThat(observed).hasSize(1);
		assertThat(observed.get(0).getResults()).hasSize(1);
		var result = observed.get(0).getResult();
		assertThat(result.getOutput().getText()).isEqualTo("AX");
		assertThat(result.getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(result.getOutput().getMetadata()).containsEntry("openrouter.reasoning", "thinking");
		assertThat(result.getOutput().getToolCalls()).hasSize(1);
		assertThat(result.getOutput().getToolCalls().get(0).name()).isEqualTo("lookup");
	}

	@Test
	void disabledObservationLeavesChoiceDeltasUnchanged() {
		List<ChatResponse> observed = observedResponses();
		this.observationRegistry.observationConfig().observationPredicate((name, context) -> false);
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks("""
				{"choices":[{"index":0,"delta":{"content":"A"}},{"index":1,"delta":{"content":"B"}}]}
				"""));
		StepVerifier.create(model(api).stream(prompt())).assertNext(response -> {
			assertThat(response.getResults()).extracting(generation -> generation.getOutput().getText())
				.containsExactly("A", "B");
		}).verifyComplete();
		assertThat(observed).isEmpty();
	}

	@Test
	void singleChoiceAggregationMatchesSpringForThoughtsAndTools() {
		RateLimit rateLimit = mock(RateLimit.class);
		PromptMetadata promptMetadata = PromptMetadata.of(PromptMetadata.PromptFilterMetadata.from(0, "synthetic"));
		List<ChatResponse> chunks = List
			.of(new ChatResponse(
					List.of(new Generation(AssistantMessage.builder()
						.content("thinking")
						.properties(Map.of("isThought", true, "synthetic", "retained"))
						.build()))),
					new ChatResponse(
							List.of(new Generation(AssistantMessage.builder()
								.content("answer")
								.properties(Map.of("isThought", false, "synthetic", "retained"))
								.toolCalls(
										List.of(new AssistantMessage.ToolCall("synthetic", "function", "lookup", "{}")))
								.build(), ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())),
							ChatResponseMetadata.builder()
								.id("synthetic")
								.model("test-model")
								.rateLimit(rateLimit)
								.promptMetadata(promptMetadata)
								.build()));
		AtomicReference<ChatResponse> expected = new AtomicReference<>();
		StepVerifier.create(new MessageAggregator().aggregate(Flux.fromIterable(chunks), expected::set))
			.expectNextCount(2)
			.verifyComplete();
		OpenRouterChatObservationAggregator aggregator = new OpenRouterChatObservationAggregator();
		chunks.forEach(aggregator::add);
		ChatResponse actual = aggregator.response();
		assertThat(actual.getResults()).isEqualTo(expected.get().getResults());
		assertThat(actual.getMetadata().getId()).isEqualTo(expected.get().getMetadata().getId());
		assertThat(actual.getMetadata().getModel()).isEqualTo(expected.get().getMetadata().getModel());
		assertThat(actual.getMetadata().getRateLimit()).isSameAs(expected.get().getMetadata().getRateLimit());
		assertThat(actual.getMetadata().getPromptMetadata()).isSameAs(expected.get().getMetadata().getPromptMetadata());
		assertThat(actual.getMetadata().getUsage().getTotalTokens()).isZero();
		assertThat(actual.getMetadata().getUsage()).isEqualTo(expected.get().getMetadata().getUsage());
		assertThat(actual.getMetadata().getUsage().getNativeUsage())
			.isEqualTo(expected.get().getMetadata().getUsage().getNativeUsage());
	}

	private Flux<ChatCompletionChunk> chunks(String... json) {
		ObjectMapper mapper = new ObjectMapper();
		return Flux.fromArray(json).map(value -> mapper.readValue(value, ChatCompletionChunk.class));
	}

	private List<ChatResponse> observedResponses() {
		List<ChatResponse> observed = new ArrayList<>();
		this.observationRegistry.observationConfig()
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
		return observed;
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamObservationPreservesUsagePerSubscription(OpenRouterRequestMode mode) {
		List<ChatResponse> observed = new ArrayList<>();
		this.observationRegistry.observationConfig()
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
		ObjectMapper mapper = new ObjectMapper();
		OpenRouterApi api = mock(OpenRouterApi.class);
		AtomicInteger subscription = new AtomicInteger();
		String[] usages = { """
				{"input_tokens":12,"output_tokens":7,"total_tokens":19,"cost":0.003,
				 "input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":2}}
				""", """
				{"input_tokens":0,"output_tokens":0,"total_tokens":0,"cost":0,
				 "input_tokens_details":{"cached_tokens":0},"output_tokens_details":{"reasoning_tokens":0}}
				""", "null" };
		when(api.responsesStream(any()))
			.thenReturn(
					Flux.defer(() -> Flux.just(mapper.readValue("""
							{"type":"response.output_text.delta","delta":"Hello"}
							""", ResponsesStreamEvent.class),
							mapper.readValue(
									"""
											{"type":"response.completed","response":{"id":"synthetic","model":"test-model",
											 "status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Hello"}]}],"usage":%s}}
											"""
										.formatted(usages[subscription.getAndIncrement()]),
									ResponsesStreamEvent.class))));
		when(api.chatCompletionStream(any())).thenReturn(Flux.defer(() -> Flux.just(mapper.readValue("""
				{"id":"synthetic","model":"test-model","choices":[{"index":0,"delta":{"content":"Hello"}}]}
				""", ChatCompletionChunk.class), mapper.readValue("""
				{"id":"synthetic","model":"test-model","choices":[],"usage":%s}
				""".formatted(usages[subscription.getAndIncrement()]), ChatCompletionChunk.class))));
		Flux<ChatResponse> stream = model(api)
			.stream(new Prompt("synthetic", OpenRouterChatOptions.builder().requestMode(mode).build()));
		for (int i = 0; i < usages.length; i++) {
			StepVerifier.create(stream).expectNextCount(2).verifyComplete();
		}
		assertThat(observed).hasSize(3);
		for (int i = 0; i < 2; i++) {
			assertThat(observed.get(i).getResult().getOutput().getText()).isEqualTo("Hello");
			assertThat(observed.get(i).getMetadata().getId()).isEqualTo("synthetic");
			assertThat(observed.get(i).getMetadata().getModel()).isEqualTo("test-model");
			assertThat(observed.get(i).getMetadata().getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class,
					usage -> {
						Usage nativeUsage = (Usage) usage.getNativeUsage();
						assertThat(usage.getPromptTokens()).isEqualTo(nativeUsage.promptTokens());
						assertThat(usage.getCompletionTokens()).isEqualTo(nativeUsage.completionTokens());
						assertThat(usage.getTotalTokens()).isEqualTo(nativeUsage.totalTokens());
						assertThat(usage.getCachedTokens()).isEqualTo(nativeUsage.promptTokensDetails().cachedTokens());
						assertThat(usage.getCacheReadInputTokens())
							.isEqualTo(nativeUsage.promptTokensDetails().cachedTokens().longValue());
						assertThat(usage.getReasoningTokens())
							.isEqualTo(nativeUsage.completionTokensDetails().reasoningTokens());
						assertThat(usage.getCost()).isEqualTo(nativeUsage.cost());
					});
		}
		assertThat(observed.get(2).getMetadata().getUsage()).isNotInstanceOf(OpenRouterUsage.class);
	}

}
