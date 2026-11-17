package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * A completed tool call must pass the same checks in a blocking response and in a stream
 * chunk.
 */
@Tag("tools")
@Tag("tools.calls")
class CompletedToolCallValidationTests {

	static Stream<Arguments> malformedToolCalls() {
		return Stream.of(
				Arguments.of(new ToolCall("call-1", null, new FunctionCall("weather", "{}")), "tool call type"),
				Arguments.of(new ToolCall("call-1", "function", null), "tool call function"),
				Arguments.of(new ToolCall("call-1", "function", new FunctionCall(null, "{}")),
						"nonblank tool call name"),
				Arguments.of(new ToolCall("call-1", "function", new FunctionCall("", "{}")), "nonblank tool call name"),
				Arguments.of(new ToolCall("call-1", "function", new FunctionCall("  ", "{}")),
						"nonblank tool call name"),
				Arguments.of(new ToolCall("call-1", "function", new FunctionCall("weather", null)),
						"tool call arguments"));
	}

	@ParameterizedTest
	@MethodSource("malformedToolCalls")
	void blockingResponseRejectsAMalformedToolCall(ToolCall toolCall, String message) {
		assertThatThrownBy(() -> blocking(toolCall)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining(message);
	}

	@ParameterizedTest
	@MethodSource("malformedToolCalls")
	void streamChunkRejectsAMalformedToolCall(ToolCall toolCall, String message) {
		assertThatThrownBy(() -> streamed(toolCall)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining(message);
	}

	@org.junit.jupiter.api.Test
	void aMissingToolCallIdMapsToAnEmptyIdInBothPaths() {
		var toolCall = new ToolCall(null, "function", new FunctionCall("weather", "{}"));
		for (ChatResponse mapped : List.of(blocking(toolCall), streamed(toolCall))) {
			assertThat(mapped.getResult().getOutput().getToolCalls()).singleElement().satisfies(call -> {
				assertThat(call.id()).isEmpty();
				assertThat(call.name()).isEqualTo("weather");
			});
		}
	}

	private static ChatResponse blocking(ToolCall toolCall) {
		return new OpenRouterChatResponseMapper().map(new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"model", "provider", List.of(new Choice(0,
						new ChatMessage("assistant", null, null, null, List.of(toolCall)), null, "tool_calls", null)),
				null));
	}

	private static ChatResponse streamed(ToolCall toolCall) {
		return new OpenRouterStreamingResponseMapper().map(new ChatCompletionChunk("gen-1", "chat.completion.chunk",
				123L, "model", "provider", List.of(new Choice(0, null,
						new Delta("assistant", null, null, List.of(toolCall)), "tool_calls", "tool_calls")),
				null, null));
	}

}
