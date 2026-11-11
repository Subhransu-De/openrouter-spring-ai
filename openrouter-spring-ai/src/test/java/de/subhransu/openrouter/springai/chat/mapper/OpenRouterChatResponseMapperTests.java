package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Tool-call and choice validation of the synchronous Chat Completions mapper. Content,
 * metadata and usage mapping are covered by
 * {@code OpenRouterChatResponseFixtureMapperTests} and {@code UsageMapperTests}.
 */
class OpenRouterChatResponseMapperTests {

	private static ChatCompletionResponse toolCallResponse(String name, String finishReason) {
		return toolCallResponse(name, "{\"job\":\"a\"}", finishReason);
	}

	private static ChatCompletionResponse toolCallResponse(String name, String arguments, String finishReason) {
		var tool = new ToolCall("call-1", "function", new FunctionCall(name, arguments));
		return new ChatCompletionResponse(
				"gen-1", "chat.completion", 123L, "model", "provider", List.of(new Choice(0,
						new ChatMessage("assistant", null, null, null, List.of(tool)), null, finishReason, null)),
				null);
	}

	@ParameterizedTest
	@ValueSource(strings = { "tool_calls", "function_call" })
	void supportedSynchronousToolCallTerminatorsProduceExecutableCalls(String reason) {
		ChatResponse mapped = ResponseMappers.chat().map(toolCallResponse("weather", reason));

		assertThat(mapped.hasToolCalls()).isTrue();
		assertThat(mapped.getResult().getOutput().getToolCalls()).singleElement().satisfies(call -> {
			assertThat(call.id()).isEqualTo("call-1");
			assertThat(call.name()).isEqualTo("weather");
			assertThat(call.arguments()).isEqualTo("{\"job\":\"a\"}");
		});
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
	}

	@ParameterizedTest(name = "finish reason {0}")
	@NullSource
	@ValueSource(strings = { "stop", "length", "content_filter" })
	void unfinishedSynchronousToolCallsAreRejected(String reason) {
		ChatCompletionResponse response = toolCallResponse("weather", reason);

		assertThatThrownBy(() -> ResponseMappers.chat().map(response))
			.isInstanceOf(OpenRouterTruncatedResponseException.class);
	}

	@ParameterizedTest(name = "name \"{0}\"")
	@ValueSource(strings = { "", "  " })
	void blankToolCallNameFailsProtocolValidation(String name) {
		ChatCompletionResponse response = toolCallResponse(name, "tool_calls");

		assertThatThrownBy(() -> ResponseMappers.chat().map(response)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("nonblank tool call name");
	}

	@Test
	void toolCallWithoutArgumentsFailsProtocolValidation() {
		ChatCompletionResponse response = toolCallResponse("weather", null, "tool_calls");

		assertThatThrownBy(() -> ResponseMappers.chat().map(response)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("OpenRouter response requires tool call arguments");
	}

	// The mapper does not parse arguments; the tool callback rejects text that is not
	// JSON, and that failure goes through the tool failure policy.
	@ParameterizedTest(name = "arguments \"{0}\"")
	@ValueSource(strings = { "", "  ", "{", "not json" })
	void blankOrMalformedToolCallArgumentsReachTheCallbackUnchanged(String arguments) {
		ChatResponse mapped = ResponseMappers.chat().map(toolCallResponse("weather", arguments, "tool_calls"));

		assertThat(mapped.getResult().getOutput().getToolCalls()).singleElement()
			.satisfies(call -> assertThat(call.arguments()).isEqualTo(arguments));
	}

	static Stream<Arguments> responsesWithoutChoices() {
		return Stream.of(
				Arguments.of(Named.of("empty choices",
						new ChatCompletionResponse("gen-empty", "chat.completion", 1L, "m", "p", List.of(), null))),
				Arguments.of(Named.of("null choices",
						new ChatCompletionResponse("gen-null", "chat.completion", 1L, "m", "p", null, null))));
	}

	@ParameterizedTest
	@MethodSource("responsesWithoutChoices")
	void missingChoicesFailProtocolValidation(ChatCompletionResponse response) {
		assertThatThrownBy(() -> ResponseMappers.chat().map(response)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("choices");
	}

}
