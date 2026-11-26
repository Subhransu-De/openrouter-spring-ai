package de.subhransu.openrouter.springai.starter.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterLogprobs;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Typed log probability, logit bias, verbosity, prompt-cache and prediction options on
 * the wire, sent by the auto-configured chat model to the OpenRouter mock, and the typed
 * log probabilities read back from calls and streams.
 */
@Tag("http")
@Tag("api.wire")
class OpenRouterTypedRequestOptionsIntegrationTests {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String QUESTION = "Is the car ready?";

	private static final Map<String, Object> LOGPROBS_SCENE = Map.of("sceneId", "logprobs-check");

	// The attribution stubs answer both request modes, in calls and streams.
	private static final Map<String, Object> ATTRIBUTION = Map.of("sceneId", "attribution-check-in");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
				"spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(),
				"spring.ai.openrouter.chat.model=synthetic/chat",
				"spring.ai.openrouter.chat.prompt-cache-key=garage-cache",
				"spring.ai.openrouter.chat.prompt-cache-options.mode=explicit",
				"spring.ai.openrouter.chat.prompt-cache-options.ttl=12h");

	@BeforeEach
	void resetMock() {
		OpenRouterMock.reset();
	}

	@Test
	void chatCompletionsSendDefaultsAndOverridesAndReturnTypedLogprobsInCallsAndStreams() {
		OpenRouterChatOptions.Builder request = OpenRouterChatOptions.builder()
			.metadata(LOGPROBS_SCENE)
			.topLogprobs(1)
			.promptCacheKey("conversation-9")
			.prediction("Ready.");
		run(chatDefaults(), context -> {
			ChatClient chat = ChatClient.create(context.getBean(ChatModel.class));
			ChatResponse called = chat.prompt(QUESTION).options(request).call().chatResponse();
			List<ChatResponse> streamed = chat.prompt(QUESTION)
				.options(request)
				.stream()
				.chatResponse()
				.collectList()
				.block(Duration.ofSeconds(10));

			assertThat(called).isNotNull();
			assertThat(streamed).isNotNull().isNotEmpty();
			OpenRouterLogprobs fromCall = OpenRouterLogprobs.from(called.getResult().getOutput()).orElseThrow();
			// Each streamed message holds the tokens so far, so the last one holds them
			// all.
			List<OpenRouterLogprobs> soFar = streamed.stream()
				.flatMap(response -> OpenRouterLogprobs.from(response.getResult().getOutput()).stream())
				.toList();
			assertThat(soFar.get(0).content()).extracting(OpenRouterLogprobs.Token::token).containsExactly("Ready");
			OpenRouterLogprobs fromStream = soFar.get(soFar.size() - 1);
			for (OpenRouterLogprobs logprobs : List.of(fromCall, fromStream)) {
				assertThat(logprobs.content()).extracting(OpenRouterLogprobs.Token::token)
					.containsExactly("Ready", ".");
				assertThat(logprobs.content().get(0).logprob()).isEqualTo(-0.01);
				assertThat(logprobs.content().get(0).bytes()).containsExactly(82, 101, 97, 100, 121);
				assertThat(logprobs.content().get(0).topLogprobs()).extracting(OpenRouterLogprobs.Alternative::token)
					.containsExactly("Ready", "Done");
				assertThat(logprobs.content().get(0).topLogprobs().get(1).bytes()).isNull();
				assertThat(logprobs.refusal()).isEmpty();
			}
			// OpenRouter ends a stream with a finish chunk and then a usage chunk that
			// repeats the
			// finish reason. The text must not repeat, and the usage must come from the
			// last chunk.
			assertThat(streamed.stream()
				.flatMap(response -> response.getResults().stream())
				.map(generation -> generation.getOutput().getText())
				.filter(Objects::nonNull)
				.collect(Collectors.joining())).isEqualTo("Ready.");
			for (ChatResponse response : List.of(called, streamed.get(streamed.size() - 1))) {
				OpenRouterUsage usage = OpenRouterUsage.from(response);
				assertThat(usage.getPromptTokens()).isEqualTo(12);
				assertThat(usage.getCompletionTokens()).isEqualTo(2);
				assertThat(usage.getCost()).isEqualTo(0.0000029);
			}
		});

		List<JsonNode> requests = OpenRouterMock.requests("/chat/completions");
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).path("stream").asBoolean()).isTrue();
		assertThat(requests).allSatisfy(body -> {
			assertThat(body.path("logprobs").asBoolean()).isTrue();
			assertThat(body.path("top_logprobs").asInt()).isEqualTo(1);
			assertThat(body.path("logit_bias")).isEqualTo(JSON.readTree("{\"50256\":-100}"));
			assertThat(body.path("verbosity").asString()).isEqualTo("low");
			assertThat(body.path("prompt_cache_key").asString()).isEqualTo("conversation-9");
			assertThat(body.path("prediction"))
				.isEqualTo(JSON.readTree("{\"type\":\"content\",\"content\":\"Ready.\"}"));
			assertThat(body.path("prompt_cache_options"))
				.isEqualTo(JSON.readTree("{\"mode\":\"explicit\",\"ttl\":\"12h\"}"));
		});
	}

	@Test
	void responsesSendTheSharedOptionsInCallsAndStreams() {
		OpenRouterChatOptions.Builder request = OpenRouterChatOptions.builder()
			.requestMode(OpenRouterRequestMode.RESPONSES)
			.metadata(ATTRIBUTION)
			.topLogprobs(2);
		run(new String[0], context -> {
			ChatClient chat = ChatClient.create(context.getBean(ChatModel.class));
			chat.prompt(QUESTION).options(request).call().content();
			chat.prompt(QUESTION).options(request).stream().content().blockLast(Duration.ofSeconds(10));
		});

		List<JsonNode> requests = OpenRouterMock.requests("/responses");
		assertThat(requests).hasSize(2);
		assertThat(requests.get(1).path("stream").asBoolean()).isTrue();
		assertThat(requests).allSatisfy(body -> {
			assertThat(body.path("top_logprobs").asInt()).isEqualTo(2);
			assertThat(body.path("prompt_cache_key").asString()).isEqualTo("garage-cache");
			assertThat(body.path("prompt_cache_options"))
				.isEqualTo(JSON.readTree("{\"mode\":\"explicit\",\"ttl\":\"12h\"}"));
			assertThat(body.has("logprobs")).isFalse();
		});
	}

	@Test
	void aTypedOptionAndTheSameDefaultExtraBodyKeyAreRejectedBeforeHttp() {
		run(new String[] { "spring.ai.openrouter.chat.extra-body.[top_logprobs]=3",
				"spring.ai.openrouter.chat.extra-body.[logprobs]=true" }, context -> {
					ChatClient chat = ChatClient.create(context.getBean(ChatModel.class));
					assertThatIllegalArgumentException()
						.isThrownBy(() -> chat.prompt(QUESTION)
							.options(OpenRouterChatOptions.builder().metadata(LOGPROBS_SCENE).topLogprobs(1))
							.call()
							.content())
						.withMessage(
								"extraBody top_logprobs 3 conflicts with the typed option 1; set only one of them");
				});

		assertThat(OpenRouterMock.requests("/chat/completions")).isEmpty();
	}

	private void run(String[] properties, Consumer<ApplicationContext> test) {
		this.contextRunner.withPropertyValues(properties).run(context -> {
			if (context.getStartupFailure() != null) {
				throw context.getStartupFailure();
			}
			test.accept(context);
		});
		assertThat(OpenRouterMock.unmatched()).isEmpty();
	}

	private static String[] chatDefaults() {
		String prefix = "spring.ai.openrouter.chat.";
		return new String[] { prefix + "logprobs=true", prefix + "top-logprobs=2", prefix + "logit-bias.[50256]=-100",
				prefix + "verbosity=low" };
	}

}
