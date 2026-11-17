package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ToolCall;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * Validates and converts completed Chat Completions tool calls. Blocking responses and
 * aggregated stream chunks share it, so both reject the same malformed calls.
 *
 * @author OpenRouter Spring AI contributors
 */
final class CompletedToolCalls {

	private CompletedToolCalls() {
	}

	static List<AssistantMessage.ToolCall> map(@Nullable List<? extends @Nullable ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return List.of();
		}
		return ResponseValues.CHAT_COMPLETIONS.items(toolCalls, "tool call")
			.stream()
			.map(CompletedToolCalls::map)
			.toList();
	}

	private static AssistantMessage.ToolCall map(ToolCall toolCall) {
		String type = ResponseValues.CHAT_COMPLETIONS.required(toolCall.type(), "tool call type");
		var function = ResponseValues.CHAT_COMPLETIONS.required(toolCall.function(), "tool call function");
		return new AssistantMessage.ToolCall(toolCall.id() != null ? toolCall.id() : "", type,
				ResponseValues.CHAT_COMPLETIONS.requiredText(function.name(), "tool call name"),
				ResponseValues.CHAT_COMPLETIONS.required(function.arguments(), "tool call arguments"));
	}

}
