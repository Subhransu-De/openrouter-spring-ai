package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;

final class FinishReasonMapper {

	private FinishReasonMapper() {
	}

	static boolean isToolCallCompletion(@Nullable String finishReason) {
		return "tool_calls".equals(finishReason) || "function_call".equals(finishReason);
	}

	static @Nullable String responses(@Nullable ResponsesResult response, @Nullable String fallbackStatus) {
		String status = response != null && response.status() != null ? response.status() : fallbackStatus;
		return "incomplete".equals(status) && response != null && response.incompleteDetails() != null
				&& response.incompleteDetails().reason() != null ? response.incompleteDetails().reason() : status;
	}

	static @Nullable String map(@Nullable String finishReason) {
		if (!StringUtils.hasText(finishReason)) {
			return finishReason;
		}
		return switch (finishReason) {
			case "stop" -> "STOP";
			case "completed" -> "STOP";
			case "length" -> "LENGTH";
			case "max_output_tokens" -> "LENGTH";
			case "tool_calls" -> "TOOL_CALLS";
			// Legacy OpenAI name still emitted by some providers routed through
			// OpenRouter.
			case "function_call" -> "TOOL_CALLS";
			case "content_filter" -> "CONTENT_FILTER";
			default -> finishReason;
		};
	}

}
