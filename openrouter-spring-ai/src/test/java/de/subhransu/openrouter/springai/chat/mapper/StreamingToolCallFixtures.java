package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * Synthetic streamed tool-call chunks shared by the aggregation tests.
 */
final class StreamingToolCallFixtures {

	static final String MODEL = "openai/gpt-5.4-mini";

	static final String CHUNK_OBJECT = "chat.completion.chunk";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private StreamingToolCallFixtures() {
	}

	static ChatCompletionChunk chunk(Choice... choices) {
		return chunk(List.of(choices));
	}

	static ChatCompletionChunk chunk(List<Choice> choices) {
		return new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", choices, null, null);
	}

	static Choice textChoice(int choiceIndex, String text) {
		return new Choice(choiceIndex, null, new Delta("assistant", text, null, null), null, null);
	}

	/**
	 * A fragment of one tool call. A null {@code id} marks a continuation fragment that
	 * carries no role, type or id.
	 */
	static Choice toolFragment(int choiceIndex, int toolIndex, String id, String name, String arguments) {
		ToolCall toolCall = new ToolCall(id, id != null ? "function" : null, new FunctionCall(name, arguments),
				toolIndex);
		return new Choice(choiceIndex, null, new Delta(id != null ? "assistant" : null, null, null, List.of(toolCall)),
				null, null);
	}

	static Choice finishChoice(int choiceIndex) {
		return finishChoice(choiceIndex, "tool_calls");
	}

	static Choice finishChoice(int choiceIndex, String finishReason) {
		return new Choice(choiceIndex, null, new Delta(null, null, null, null), finishReason, finishReason);
	}

	static ChatCompletionChunk wireChunk(String json) {
		return MAPPER.readValue(json, ChatCompletionChunk.class);
	}

}
