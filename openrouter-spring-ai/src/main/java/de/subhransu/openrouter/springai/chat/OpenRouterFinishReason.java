package de.subhransu.openrouter.springai.chat;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.Generation;
import org.springframework.util.StringUtils;

/**
 * Why a generation ended. {@link Generation#getMetadata()} keeps returning the finish
 * reason as a {@code String}: the mapped spellings {@code STOP}, {@code LENGTH},
 * {@code TOOL_CALLS} and {@code CONTENT_FILTER}, and any other reason as the provider
 * wrote it, such as {@code error}. {@link #from(Generation)} reads either spelling.
 *
 * @author Subhransu De
 */
public enum OpenRouterFinishReason {

	/**
	 * The model finished its answer ({@code stop}, or the Responses status
	 * {@code completed}).
	 */
	STOP,

	/**
	 * The output hit the token limit ({@code length}, or the Responses incomplete reason
	 * {@code max_output_tokens}).
	 */
	LENGTH,

	/**
	 * The model asked to call tools ({@code tool_calls}, or the legacy
	 * {@code function_call}).
	 */
	TOOL_CALLS,

	/**
	 * A content filter stopped the output ({@code content_filter}).
	 */
	CONTENT_FILTER,

	/**
	 * The provider reported an error ({@code error}). The chat model raises
	 * {@code OpenRouterTransientChoiceException} or
	 * {@code OpenRouterNonTransientChoiceException} for such a choice instead of
	 * returning it, so only a response built elsewhere carries this reason.
	 */
	ERROR,

	/**
	 * No finish reason: an intermediate stream chunk, or a stream response the provider
	 * did not mark.
	 */
	NONE,

	/**
	 * Any other reason. {@code generation.getMetadata().getFinishReason()} holds it as
	 * the provider wrote it.
	 */
	OTHER;

	private static final Map<String, OpenRouterFinishReason> SPELLINGS = Map.ofEntries(Map.entry("STOP", STOP),
			Map.entry("stop", STOP), Map.entry("completed", STOP), Map.entry("LENGTH", LENGTH),
			Map.entry("length", LENGTH), Map.entry("max_output_tokens", LENGTH), Map.entry("TOOL_CALLS", TOOL_CALLS),
			Map.entry("tool_calls", TOOL_CALLS), Map.entry("function_call", TOOL_CALLS),
			Map.entry("CONTENT_FILTER", CONTENT_FILTER), Map.entry("content_filter", CONTENT_FILTER),
			Map.entry("ERROR", ERROR), Map.entry("error", ERROR));

	/**
	 * Read the finish reason of a generation.
	 * @param generation the generation to read
	 * @return the finish reason; {@link #NONE} when the generation has none
	 */
	public static OpenRouterFinishReason from(Generation generation) {
		return from(generation.getMetadata().getFinishReason());
	}

	/**
	 * Classify a finish reason in either spelling: the upper-case mapped value or the
	 * lower-case provider value.
	 * @param finishReason the mapped or provider finish reason
	 * @return the finish reason; {@link #NONE} for {@code null} or blank input
	 */
	public static OpenRouterFinishReason from(@Nullable String finishReason) {
		if (!StringUtils.hasText(finishReason)) {
			return NONE;
		}
		return SPELLINGS.getOrDefault(finishReason, OTHER);
	}

}
