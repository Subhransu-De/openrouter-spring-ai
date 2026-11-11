package de.subhransu.openrouter.springai.chat;

import static io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
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
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.StepVerifierOptions;
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

	private Prompt keyValuePrompt(OpenRouterRequestMode mode) {
		return new Prompt(List.of(new UserMessage("hi")),
				OpenRouterChatOptions.builder()
					.model(MODEL)
					.requestMode(mode)
					.temperature(0.2)
					.maxTokens(50)
					.topP(0.9)
					.build());
	}

	private static ResponsesResult completedResponse() {
		return new ResponsesResult("gen-1", "response", 123L, "openai/gpt-5.4-mini-2026", "completed",
				List.of(new ResponsesOutputItem("item-1", "message", "completed", "assistant",
						List.of(new ResponsesContent("output_text", "Hello.")))),
				new Usage(10, 5, 15, null, null, null, null, null, null), null);
	}

	private void assertRequestAndResponseKeyValues() {
		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.operation.name", "chat")
			.hasLowCardinalityKeyValue(PROVIDER_KEY, "openrouter")
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasLowCardinalityKeyValue("gen_ai.response.model", "openai/gpt-5.4-mini-2026")
			.hasHighCardinalityKeyValue("gen_ai.request.temperature", "0.2")
			.hasHighCardinalityKeyValue("gen_ai.request.max_tokens", "50")
			.hasHighCardinalityKeyValue("gen_ai.request.top_p", "0.9")
			.hasHighCardinalityKeyValue("gen_ai.response.id", "gen-1")
			.hasHighCardinalityKeyValue("gen_ai.response.finish_reasons", "[\"STOP\"]")
			.hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "10")
			.hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "5")
			.hasHighCardinalityKeyValue("gen_ai.usage.total_tokens", "15")
			.hasBeenStarted()
			.hasBeenStopped();
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void callObservationCarriesRequestAndResponseKeyValues(OpenRouterRequestMode mode) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"openai/gpt-5.4-mini-2026", "openai",
				List.of(new Choice(0, new ChatMessage("assistant", "Hello.", null, null, null), null, "stop", "stop")),
				new Usage(10, 5, 15, null, null, null, null, null, null)));
		when(api.responses(any())).thenReturn(completedResponse());

		model(api).call(keyValuePrompt(mode));

		assertRequestAndResponseKeyValues();
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamObservationCarriesRequestAndResponseKeyValues(OpenRouterRequestMode mode) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		ObjectMapper mapper = new ObjectMapper();
		when(api.chatCompletionStream(any())).thenReturn(chunks(
				"""
						{"id":"gen-1","model":"openai/gpt-5.4-mini-2026","choices":[{"index":0,"delta":{"content":"Hello."},"finish_reason":"stop"}]}
						""",
				"""
						{"id":"gen-1","model":"openai/gpt-5.4-mini-2026","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
						"""));
		when(api.responsesStream(any())).thenReturn(Flux.just(
				mapper.readValue("{\"type\":\"response.output_text.delta\",\"delta\":\"Hello.\"}",
						ResponsesStreamEvent.class),
				new ResponsesStreamEvent("response.completed", null, null, completedResponse(), null)));

		StepVerifier.create(model(api).stream(keyValuePrompt(mode))).expectNextCount(2).verifyComplete();

		assertRequestAndResponseKeyValues();
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamFailureAfterAnEmittedElementIsRecordedAndStopsTheObservation(OpenRouterRequestMode mode) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterApiException failure = new OpenRouterApiException("stream failed", HttpStatus.BAD_GATEWAY,
				"{\"error\":true}");
		when(api.chatCompletionStream(any())).thenReturn(Flux
			.concat(chunks("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}"), Flux.error(failure)));
		when(api.responsesStream(any())).thenReturn(Flux.concat(
				Flux.just(new ObjectMapper().readValue(
						"{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}", ResponsesStreamEvent.class)),
				Flux.error(failure)));

		StepVerifier.create(model(api).stream(keyValuePrompt(mode)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(error).isSameAs(failure))
			.verify();

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isSameAs(failure);
	}

	@Test
	void callErrorIsRecordedOnTheObservationAndThrownUnchanged() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterApiException failure = new OpenRouterApiException("call failed", HttpStatus.BAD_REQUEST,
				"{\"error\":true}");
		when(api.chatCompletion(any())).thenThrow(failure);
		OpenRouterChatModel model = model(api);
		Prompt prompt = prompt();

		assertThatThrownBy(() -> model.call(prompt)).isSameAs(failure);

		assertThat(this.observationRegistry).hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.hasLowCardinalityKeyValue("gen_ai.request.model", MODEL)
			.hasBeenStarted()
			.hasBeenStopped()
			.thenError()
			.isSameAs(failure);
	}

	static Stream<Arguments> choiceLayouts() {
		return Stream.of(
				Arguments.of(Named.of("both choices packed in each chunk",
						new ChoiceLayout(new String[] { """
								{"choices":[{"index":0,"delta":{"content":"A"}},{"index":1,"delta":{"content":"B"}}]}
								""",
								"""
										{"choices":[{"index":1,"delta":{"content":"Y"},"finish_reason":"length"},{"index":0,"delta":{"content":"X"},"finish_reason":"stop"}]}
										""" },
								2, List.of("A", "B", "X", "Y")))),
				Arguments.of(Named.of("one choice per chunk", new ChoiceLayout(new String[] { """
						{"choices":[{"index":0,"delta":{"content":"A"}}]}
						""", """
						{"choices":[{"index":1,"delta":{"content":"B"}}]}
						""", """
						{"choices":[{"index":1,"delta":{"content":"Y"},"finish_reason":"length"}]}
						""", """
						{"choices":[{"index":0,"delta":{"content":"X"},"finish_reason":"stop"}]}
						""" }, 4, List.of("A", "B", "Y", "X")))));
	}

	@ParameterizedTest
	@MethodSource("choiceLayouts")
	void streamObservationKeepsChoicesSeparate(ChoiceLayout layout) {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks(layout.chunks()));
		Flux<ChatResponse> stream = model(api).stream(prompt());
		verifyEmissionOrder(stream, layout, "first subscription");
		verifyEmissionOrder(stream, layout, "second subscription");
		assertThat(observed).hasSize(2).allSatisfy(response -> {
			assertThat(response.getResults()).hasSize(2);
			assertThat(response.getResults()).extracting(generation -> generation.getOutput().getText())
				.containsExactly("AX", "BY");
			assertThat(response.getResults()).extracting(generation -> generation.getMetadata().getFinishReason())
				.containsExactly("STOP", "LENGTH");
			assertThat(response.getResults())
				.extracting(generation -> generation.getMetadata().get("openrouter.choice_index"))
				.containsExactly(0, 1);
		});
	}

	private static void verifyEmissionOrder(Flux<ChatResponse> stream, ChoiceLayout layout, String subscription) {
		StepVerifier.create(stream, StepVerifierOptions.create().scenarioName(subscription))
			.recordWith(ArrayList::new)
			.expectNextCount(layout.responseCount())
			.consumeRecordedWith(responses -> assertThat(responses.stream()
				.flatMap(response -> response.getResults().stream())
				.map(generation -> generation.getOutput().getText())).containsExactlyElementsOf(layout.emissionOrder()))
			.verifyComplete();
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

	// Spring AI's MessageAggregator is the oracle here on purpose: the observation must
	// see the same single-choice aggregate that ChatClient users get. A change in Spring
	// AI's aggregation fails this test and needs a conscious decision, not a silent copy.
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

	private void stubStreamWithUsagePerSubscription(OpenRouterApi api, OpenRouterRequestMode mode, String... usages) {
		ObjectMapper mapper = new ObjectMapper();
		AtomicInteger subscription = new AtomicInteger();
		if (mode == OpenRouterRequestMode.OPENAI_RESPONSES) {
			when(api.responsesStream(any())).thenReturn(
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
		}
		else {
			when(api.chatCompletionStream(any())).thenReturn(Flux.defer(() -> Flux.just(mapper.readValue("""
					{"id":"synthetic","model":"test-model","choices":[{"index":0,"delta":{"content":"Hello"}}]}
					""", ChatCompletionChunk.class), mapper.readValue("""
					{"id":"synthetic","model":"test-model","choices":[],"usage":%s}
					""".formatted(usages[subscription.getAndIncrement()]), ChatCompletionChunk.class))));
		}
	}

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void streamObservationPreservesUsagePerSubscription(OpenRouterRequestMode mode) {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		// Responses reports input and output tokens; Chat Completions reports prompt and
		// completion tokens. The accounting that reaches the observation is the same.
		boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
		String input = responses ? "input" : "prompt";
		String output = responses ? "output" : "completion";
		stubStreamWithUsagePerSubscription(api, mode, """
				{"%1$s_tokens":12,"%2$s_tokens":7,"total_tokens":19,"cost":0.003,
				 "%1$s_tokens_details":{"cached_tokens":3},"%2$s_tokens_details":{"reasoning_tokens":2}}
				""".formatted(input, output), """
				{"%1$s_tokens":0,"%2$s_tokens":0,"total_tokens":0,"cost":0,
				 "%1$s_tokens_details":{"cached_tokens":0},"%2$s_tokens_details":{"reasoning_tokens":0}}
				""".formatted(input, output), "null");
		Flux<ChatResponse> stream = model(api)
			.stream(new Prompt("synthetic", OpenRouterChatOptions.builder().requestMode(mode).build()));

		StepVerifier.create(stream).expectNextCount(2).verifyComplete();
		StepVerifier.create(stream).expectNextCount(2).verifyComplete();
		StepVerifier.create(stream).expectNextCount(2).verifyComplete();

		assertThat(observed).hasSize(3);
		assertThat(observed).allSatisfy(response -> {
			assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello");
			assertThat(response.getMetadata().getId()).isEqualTo("synthetic");
			assertThat(response.getMetadata().getModel()).isEqualTo("test-model");
		});
		assertThat(observed.get(0).getMetadata().getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class, usage -> {
			assertThat(usage.getPromptTokens()).isEqualTo(12);
			assertThat(usage.getCompletionTokens()).isEqualTo(7);
			assertThat(usage.getTotalTokens()).isEqualTo(19);
			assertThat(usage.getCachedTokens()).isEqualTo(3);
			assertThat(usage.getCacheReadInputTokens()).isEqualTo(3L);
			assertThat(usage.getReasoningTokens()).isEqualTo(2);
			assertThat(usage.getCost()).isEqualTo(0.003);
		});
		assertThat(observed.get(1).getMetadata().getUsage()).isInstanceOfSatisfying(OpenRouterUsage.class, usage -> {
			assertThat(usage.getPromptTokens()).isZero();
			assertThat(usage.getCompletionTokens()).isZero();
			assertThat(usage.getTotalTokens()).isZero();
			assertThat(usage.getCachedTokens()).isZero();
			assertThat(usage.getReasoningTokens()).isZero();
			assertThat(usage.getCost()).isZero();
		});
		assertThat(observed.get(2).getMetadata().getUsage()).isNotInstanceOf(OpenRouterUsage.class);
	}

	@Test
	void streamObservationReportsTheNewestUsageWhenAnEarlierUsageChunkPrecedesTheFinishChunk() {
		List<ChatResponse> observed = observedResponses();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(chunks("""
				{"choices":[{"index":0,"delta":{"content":"Hello"}}]}
				""", """
				{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
				""", """
				{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
				""", """
				{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}
				"""));

		StepVerifier.create(model(api).stream(prompt())).expectNextCount(4).verifyComplete();

		assertThat(observed).singleElement()
			.satisfies(response -> assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(19));
	}

	@ParameterizedTest
	@CsvSource({ "OPENAI_CHAT_COMPLETIONS,2,0", "OPENAI_RESPONSES,0,2" })
	void retriedCallIsOneObservationStartedAndStoppedOnce(OpenRouterRequestMode mode, int chatAttempts,
			int responsesAttempts) {
		List<Observation.Context> starts = new ArrayList<>();
		List<Observation.Context> stops = new ArrayList<>();
		this.observationRegistry.observationConfig().observationHandler(new ObservationHandler<>() {
			@Override
			public boolean supportsContext(Observation.Context context) {
				return context instanceof ChatModelObservationContext;
			}

			@Override
			public void onStart(Observation.Context context) {
				starts.add(context);
			}

			@Override
			public void onStop(Observation.Context context) {
				stops.add(context);
			}
		});
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterApiException transientFailure = new OpenRouterApiException("transient",
				HttpStatus.SERVICE_UNAVAILABLE, "{}");
		when(api.chatCompletion(any())).thenThrow(transientFailure)
			.thenReturn(
					new ChatCompletionResponse(
							"gen-1", "chat.completion", 123L, "m", "p", List.of(new Choice(0,
									new ChatMessage("assistant", "Hello.", null, null, null), null, "stop", "stop")),
							null));
		when(api.responses(any())).thenThrow(transientFailure).thenReturn(completedResponse());
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.observationRegistry(this.observationRegistry)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(1).delay(Duration.ZERO).build()))
			.build();

		ChatResponse response = model.call(keyValuePrompt(mode));

		assertThat(response.getResult().getOutput().getText()).isNotEmpty();
		verify(api, times(chatAttempts)).chatCompletion(any());
		verify(api, times(responsesAttempts)).responses(any());
		assertThat(starts).hasSize(1);
		assertThat(stops).containsExactlyElementsOf(starts);
		assertThat(this.observationRegistry).hasNumberOfObservationsEqualTo(1)
			.hasObservationWithNameEqualTo(OBSERVATION_NAME)
			.that()
			.doesNotHaveError();
	}

	private record ChoiceLayout(String[] chunks, int responseCount, List<String> emissionOrder) {
	}

}
