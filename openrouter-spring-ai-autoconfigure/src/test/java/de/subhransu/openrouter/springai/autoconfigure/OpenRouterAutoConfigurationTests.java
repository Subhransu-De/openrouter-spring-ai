package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OpenRouterAutoConfigurationTests {

	private static final String API_KEY_PROPERTY = "spring.ai.openrouter.api-key=test-key";

	private static final String AGGREGATION_PREFIX = "spring.ai.openrouter.chat.tool-call-aggregation.";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class));

	static Stream<Arguments> unsupportedBodySizes() {
		return Stream.of("max-response-body-size", "max-error-body-size")
			.flatMap(property -> Stream.of("0B", "-1B", "2147483647B", "2147483648B", "3GB")
				.map(value -> Arguments.of(Named.of(property + "=" + value, property), value)));
	}

	@ParameterizedTest
	@MethodSource("unsupportedBodySizes")
	void rejectsUnsupportedBodySizesDuringBinding(String property, String value) {
		this.contextRunner
			.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.connection." + property + "=" + value)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalArgumentException.class)
					.hasStackTraceContaining(
							"spring.ai.openrouter.connection." + property + " must be between 1 and 2147483646 bytes");
			});
	}

	static Stream<Arguments> chatCompletionsAggregationLimits() {
		return Stream.of(
				Arguments.of(Named.of("max-size", "max-size=64B"),
						OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES),
				Arguments.of(Named.of("max-chunks", "max-chunks=1"),
						OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_CHUNKS),
				Arguments.of(Named.of("max-duration", "max-duration=1s"),
						OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_DURATION));
	}

	@ParameterizedTest
	@MethodSource("chatCompletionsAggregationLimits")
	void aBoundToolCallAggregationLimitStopsAChatCompletionsStreamThatExceedsIt(String setting,
			OpenRouterLimitExceededException.Limit expected) {
		// One tool-call fragment larger than 64 bytes, then a finish chunk two seconds
		// later: a second chunk, and more than the one second the call may stay
		// incomplete.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.defer(() -> Flux.just(toolFragment("x".repeat(200)))
			.concatWith(Mono.delay(Duration.ofSeconds(2)).map(ignored -> finishChunk()))));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, AGGREGATION_PREFIX + setting)
			.run(context -> StepVerifier
				.withVirtualTime(() -> context.getBean(OpenRouterChatModel.class)
					.stream(new Prompt("synthetic", OpenRouterChatOptions.builder().model("synthetic").build())))
				.thenAwait(Duration.ofSeconds(3))
				.thenConsumeWhile(response -> true)
				.expectErrorSatisfies(error -> assertThat(error)
					.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
						assertThat(failure.getEndpoint()).isEqualTo("/chat/completions");
						assertThat(failure.getLimit()).isEqualTo(expected);
						assertThat(failure.getLimit().getProperty())
							.isEqualTo(AGGREGATION_PREFIX + setting.split("=")[0]);
					}))
				.verify());
	}

	static Stream<Arguments> responsesAggregationLimits() {
		return Stream.of(
				Arguments.of(Named.of("max-size", "max-size=1B"),
						OpenRouterLimitExceededException.Limit.RESPONSES_STATE_BYTES),
				Arguments.of(Named.of("max-chunks", "max-chunks=1"),
						OpenRouterLimitExceededException.Limit.RESPONSES_STATE_CHUNKS),
				Arguments.of(Named.of("max-duration", "max-duration=1s"),
						OpenRouterLimitExceededException.Limit.RESPONSES_STATE_DURATION));
	}

	@ParameterizedTest
	@MethodSource("responsesAggregationLimits")
	void appliesBoundLimitsToResponsesStreams(String setting, OpenRouterLimitExceededException.Limit expected) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responsesStream(any())).thenReturn(Flux.defer(() -> Flux
			.just(new ResponsesStreamEvent("response.reasoning_text.delta", "synthetic", null, null, null))
			.concatWith(Mono.delay(Duration.ofSeconds(2))
				.map(ignored -> new ResponsesStreamEvent("response.completed", null, null, null, null)))));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY, AGGREGATION_PREFIX + setting)
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
							assertThat(failure.getLimit()).isEqualTo(expected);
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
	@CsvSource(delimiter = '|',
			value = { "max-size=0B | Maximum Chat Completions state size must be greater than zero",
					"max-size=-1B | Maximum Chat Completions state size must be greater than zero",
					"max-choices=0 | Maximum Chat Completions active choices must be greater than zero",
					"max-choices=-1 | Maximum Chat Completions active choices must be greater than zero" })
	void rejectsInvalidChatStateLimits(String setting, String reason) {
		this.contextRunner.withPropertyValues(API_KEY_PROPERTY, "spring.ai.openrouter.chat.streaming-state." + setting)
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure()).rootCause()
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining(reason);
			});
	}

	@Test
	void unsetToolCallAggregationLimitsStillBoundAStreamAtTheDefaultSize() {
		// No limit property is set: the first fragment is one byte over the one mebibyte
		// default, so the default must have reached the aggregator.
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any()))
			.thenReturn(Flux.just(toolFragment("x".repeat(1024 * 1024 + 1)), finishChunk()));
		this.contextRunner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(API_KEY_PROPERTY)
			.run(context -> {
				StepVerifier
					.create(context.getBean(OpenRouterChatModel.class)
						.stream(new Prompt("synthetic", OpenRouterChatOptions.builder().model("synthetic").build())))
					.thenConsumeWhile(response -> true)
					.expectErrorSatisfies(
							error -> assertThat(error).isInstanceOfSatisfying(OpenRouterLimitExceededException.class,
									failure -> assertThat(failure.getLimit())
										.isEqualTo(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES)))
					.verify();
			});
	}

	private static ChatCompletionChunk toolFragment(String arguments) {
		return chunk(new Choice(0, null,
				new Delta("assistant", null, null,
						List.of(new ToolCall("call-1", "function", new FunctionCall("lookup", arguments), 0))),
				null, null));
	}

	private static ChatCompletionChunk finishChunk() {
		return chunk(new Choice(0, null, new Delta(null, null, null, null), "tool_calls", "tool_calls"));
	}

	private static ChatCompletionChunk chunk(Choice choice) {
		return new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, "synthetic", "synthetic",
				List.of(choice), null, null);
	}

}
