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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
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
