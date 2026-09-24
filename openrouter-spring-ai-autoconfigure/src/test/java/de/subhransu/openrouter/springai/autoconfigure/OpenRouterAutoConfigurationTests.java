package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingResponseMapper;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingToolCallAggregator;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class OpenRouterAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	@ParameterizedTest
	@ValueSource(strings = { "0B", "-1B", "2147483647B", "2147483648B", "3GB" })
	void rejectsUnsupportedBodySizesDuringBinding(String value) {
		for (String property : new String[] { "max-response-body-size", "max-error-body-size" }) {
			contextRunner
				.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection." + property + "=" + value)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
						.hasStackTraceContaining("spring.ai.openrouter.connection." + property
								+ " must be between 1 and 2147483646 bytes");
				});
		}
	}

	@Test
	void createsApiAndChatModelWhenApiKeyIsConfigured() {
		contextRunner.withPropertyValues(API_KEY_PROPERTY).run(context -> {
			assertThat(context).hasSingleBean(OpenRouterApi.class);
			assertThat(context).hasSingleBean(OpenRouterChatModel.class);
		});
	}

	@Test
	void bindsConnectionTimeoutProperty() {
		contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.timeout=45s")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterConnectionProperties.class);
				assertThat(context.getBean(OpenRouterConnectionProperties.class).getTimeout())
					.isEqualTo(java.time.Duration.ofSeconds(45));
				assertThat(context).hasSingleBean(OpenRouterApi.class);
			});
	}

	@Test
	void bindsAndWiresResponseAndToolCallLimits() {
		contextRunner
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection.max-response-body-size=96MB",
					"spring.ai.openrouter.connection.max-error-body-size=32KB",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-size=2MB",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks=200",
					"spring.ai.openrouter.chat.tool-call-aggregation.max-duration=30s")
			.run(context -> {
				OpenRouterConnectionProperties connection = context.getBean(OpenRouterConnectionProperties.class);
				assertThat(connection.getMaxResponseBodySize().toMegabytes()).isEqualTo(96);
				assertThat(connection.getMaxErrorBodySize().toKilobytes()).isEqualTo(32);

				OpenRouterChatProperties chat = context.getBean(OpenRouterChatProperties.class);
				assertThat(chat.getToolCallAggregation().getMaxSize().toMegabytes()).isEqualTo(2);
				assertThat(chat.getToolCallAggregation().getMaxChunks()).isEqualTo(200);
				assertThat(chat.getToolCallAggregation().getMaxDuration()).isEqualTo(Duration.ofSeconds(30));

				OpenRouterStreamingToolCallAggregator aggregator = (OpenRouterStreamingToolCallAggregator) org.springframework.test.util.ReflectionTestUtils
					.getField(context.getBean(OpenRouterChatModel.class), "streamingToolCallAggregator");
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxBytes"))
					.isEqualTo(2L * 1024 * 1024);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxChunks"))
					.isEqualTo(200);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(aggregator, "maxDuration"))
					.isEqualTo(Duration.ofSeconds(30));
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "max-size=1B", "max-chunks=1", "max-duration=1s" })
	void appliesBoundLimitsToResponsesStreams(String setting) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any())).thenReturn(Flux.defer(() -> Flux
			.just(new ResponsesStreamEvent("response.reasoning_text.delta", "synthetic", null, null, null))
			.concatWith(Mono.delay(Duration.ofSeconds(2))
				.map(ignored -> new ResponsesStreamEvent("response.completed", null, null, null, null)))));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.chat.tool-call-aggregation." + setting)
			.run(context -> {
				var options = OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build();
				StepVerifier
					.withVirtualTime(
							() -> context.getBean(OpenRouterChatModel.class).stream(new Prompt("synthetic", options)))
					.thenAwait(Duration.ofSeconds(3))
					.thenConsumeWhile(response -> true)
					.expectErrorSatisfies(error -> assertThat(error)
						.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
							assertThat(failure.getEndpoint()).isEqualTo("/responses");
							assertThat(failure.getLimit().getProperty()).endsWith(setting.split("=")[0]);
						}))
					.verify();
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "max-size=1B", "max-choices=1" })
	void appliesBoundChatStateLimitsWithoutTools(String setting) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		var json = new ObjectMapper();
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(
				json.readValue("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning\":\"a\"}}]}",
						ChatCompletionChunk.class),
				json.readValue("{\"choices\":[{\"index\":1,\"delta\":{\"reasoning\":\"b\"}}]}",
						ChatCompletionChunk.class)));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.chat.streaming-state." + setting)
			.run(context -> StepVerifier
				.create(context.getBean(OpenRouterChatModel.class)
					.stream(new Prompt("synthetic", OpenRouterChatOptions.builder().model("synthetic").build())))
				.thenConsumeWhile(response -> true)
				.expectErrorSatisfies(error -> assertThat(error)
					.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
						assertThat(failure.getEndpoint()).isEqualTo("/chat/completions");
						assertThat(failure.getLimit().getProperty())
							.isEqualTo("spring.ai.openrouter.chat.streaming-state." + setting.split("=")[0]);
					}))
				.verify());
	}

	@ParameterizedTest
	@ValueSource(strings = { "max-size=0B", "max-size=-1B", "max-choices=0", "max-choices=-1" })
	void rejectsInvalidChatStateLimits(String setting) {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.chat.streaming-state." + setting)
			.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void chatStateDefaultsMatchMapperDefaults() {
		var defaults = new OpenRouterChatProperties().getStreamingState();
		assertThat(defaults.getMaxSize().toBytes())
			.isEqualTo(OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_BYTES);
		assertThat(defaults.getMaxChoices()).isEqualTo(OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_CHOICES);
	}

	@Test
	void injectsUserDefinedToolCallingManagerIntoChatModel() {
		contextRunner.withUserConfiguration(ToolCallingManagerConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterChatModel chatModel = context.getBean(OpenRouterChatModel.class);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatModel, "toolCallingManager"))
					.isSameAs(context.getBean(org.springframework.ai.model.tool.ToolCallingManager.class));
			});
	}

	@Test
	void injectsUserDefinedObservationRegistryIntoChatModel() {
		contextRunner.withUserConfiguration(ObservationRegistryConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				OpenRouterChatModel chatModel = context.getBean(OpenRouterChatModel.class);
				assertThat(org.springframework.test.util.ReflectionTestUtils.getField(chatModel, "observationRegistry"))
					.isSameAs(context.getBean(io.micrometer.observation.ObservationRegistry.class));
			});
	}

	@Test
	void backsOffWhenUserDefinesOwnChatModel() {
		contextRunner.withUserConfiguration(CustomChatModelConfiguration.class)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				assertThat(context).doesNotHaveBean(OpenRouterChatModel.class);
				assertThat(context).hasSingleBean(ChatModel.class);
			});
	}

	@Configuration(proxyBeanMethods = false)
	static class ToolCallingManagerConfiguration {

		@Bean
		org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
			return exception -> "custom failure";
		}

		@Bean
		org.springframework.ai.model.tool.ToolCallingManager customToolCallingManager(
				org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor processor) {
			return org.springframework.ai.model.tool.ToolCallingManager.builder()
				.toolExecutionExceptionProcessor(processor)
				.build();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class ObservationRegistryConfiguration {

		@Bean
		io.micrometer.observation.ObservationRegistry customObservationRegistry() {
			return io.micrometer.observation.ObservationRegistry.create();
		}

	}

	@Configuration(proxyBeanMethods = false)
	static class CustomChatModelConfiguration {

		@Bean
		ChatModel customChatModel() {
			return org.mockito.Mockito.mock(ChatModel.class);
		}

	}

}
