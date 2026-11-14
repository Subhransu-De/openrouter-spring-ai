package de.subhransu.openrouter.springai.chat;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.Generation;

/**
 * The metadata keys that this library sets on Spring AI responses, and typed readers for
 * them. The readers never throw for an absent key or a value of another type: they return
 * {@link Optional#empty()} or the documented default instead.
 *
 * <p>
 * In a stream, the generation metadata holds each chunk's delta of {@link #REASONING},
 * while the assistant message metadata holds the text so far. Both hold the cumulative
 * {@link #REFUSAL}.
 *
 * @author Subhransu De
 */
public final class OpenRouterMetadata {

	/**
	 * Reasoning text ({@code String}). Generation and assistant message metadata, both
	 * request modes.
	 */
	public static final String REASONING = "openrouter.reasoning";

	/**
	 * Opaque Chat Completions reasoning details ({@code List}) replayed with the
	 * assistant message. Assistant message metadata.
	 */
	public static final String REASONING_DETAILS = "openrouter.reasoning_details";

	/**
	 * Opaque Responses output items ({@code List}) replayed with the assistant message.
	 * Assistant message metadata.
	 */
	public static final String RESPONSES_OUTPUT_ITEMS = "openrouter.responses.output_items";

	/**
	 * Opaque Responses reasoning items ({@code List}). Assistant message metadata.
	 */
	public static final String RESPONSES_REASONING_ITEMS = "openrouter.responses.reasoning_items";

	/**
	 * Provider refusal explanation ({@code String}), separate from the answer text.
	 * Generation and assistant message metadata, both request modes.
	 */
	public static final String REFUSAL = "openrouter.refusal";

	/**
	 * Output audio transcript, format, id and expiry ({@code Map}). Assistant message
	 * metadata of a streamed Chat Completions response. Read it with
	 * {@link OpenRouterAudioOutput#from(AssistantMessage)}.
	 */
	public static final String AUDIO = "openrouter.audio";

	/**
	 * Unknown Chat Completions message fields ({@code Map}). Assistant message metadata.
	 */
	public static final String MESSAGE_EXTENSIONS = "openrouter.message.extensions";

	/**
	 * Unknown Chat Completions choice fields, including log probabilities ({@code Map}).
	 * Assistant message metadata.
	 */
	public static final String CHOICE_EXTENSIONS = "openrouter.choice.extensions";

	/**
	 * Unknown Chat Completions tool call fields, keyed by call ID ({@code Map}).
	 * Assistant message metadata.
	 */
	public static final String TOOL_CALL_EXTENSIONS = "openrouter.tool_call.extensions";

	/**
	 * The model that answered ({@code String}). Chat Completions generation metadata.
	 */
	public static final String MODEL = "openrouter.model";

	/**
	 * The finish reason as the provider wrote it, before mapping ({@code String}).
	 * Generation metadata, both request modes.
	 */
	public static final String NATIVE_FINISH_REASON = "openrouter.native_finish_reason";

	/**
	 * The choice index ({@code Integer}). Generation metadata of streamed Chat
	 * Completions responses only; read it with {@link #choiceIndex(Generation)}.
	 */
	public static final String CHOICE_INDEX = "openrouter.choice_index";

	/**
	 * The Responses status ({@code String}). Responses generation metadata.
	 */
	public static final String RESPONSES_STATUS = "openrouter.responses.status";

	/**
	 * The typed Responses incomplete details. Responses generation metadata.
	 */
	public static final String RESPONSES_INCOMPLETE_DETAILS = "openrouter.responses.incomplete_details";

	/**
	 * Unknown Chat Completions response fields ({@code Map}). Chat response metadata.
	 */
	public static final String CHAT_COMPLETION_EXTENSIONS = "openrouter.response.extensions";

	/**
	 * The upstream provider that served the request ({@code String}). Chat Completions
	 * response metadata.
	 */
	public static final String PROVIDER = "openrouter.provider";

	/**
	 * The wire object type ({@code String}). Chat response metadata.
	 */
	public static final String OBJECT = "openrouter.object";

	/**
	 * The creation time in epoch seconds. Chat response metadata.
	 */
	public static final String CREATED = "openrouter.created";

	/**
	 * Image usage ({@link OpenRouterUsage}). Image response metadata; read it with
	 * {@link OpenRouterUsage#from(org.springframework.ai.image.ImageResponse)}.
	 */
	public static final String IMAGE_USAGE = "openrouter.image.usage";

	/**
	 * The image stream event type ({@code String}). Image response metadata of
	 * {@code stream()} responses.
	 */
	public static final String IMAGE_EVENT_TYPE = "openrouter.image.event_type";

	private OpenRouterMetadata() {
	}

	/**
	 * Read the reasoning text from the generation metadata. In a stream this is the
	 * chunk's delta.
	 * @param generation the generation to read
	 * @return the reasoning text, or empty when the model sent none
	 */
	public static Optional<String> reasoning(Generation generation) {
		return text(generation.getMetadata().get(REASONING));
	}

	/**
	 * Read the reasoning text from the assistant message metadata. In a stream this is
	 * the text so far.
	 * @param message the assistant message to read
	 * @return the reasoning text, or empty when the model sent none
	 */
	public static Optional<String> reasoning(AssistantMessage message) {
		return text(message.getMetadata().get(REASONING));
	}

	/**
	 * Read the provider's refusal explanation. In a stream this is the text so far.
	 * @param generation the generation to read
	 * @return the refusal, or empty when the model did not refuse
	 */
	public static Optional<String> refusal(Generation generation) {
		return text(generation.getMetadata().get(REFUSAL));
	}

	/**
	 * Read the provider's refusal explanation. In a stream this is the text so far.
	 * @param message the assistant message to read
	 * @return the refusal, or empty when the model did not refuse
	 */
	public static Optional<String> refusal(AssistantMessage message) {
		return text(message.getMetadata().get(REFUSAL));
	}

	/**
	 * Read the finish reason as the provider wrote it, before mapping.
	 * @param generation the generation to read
	 * @return the native finish reason, or empty when the provider sent none
	 */
	public static Optional<String> nativeFinishReason(Generation generation) {
		return text(generation.getMetadata().get(NATIVE_FINISH_REASON));
	}

	/**
	 * Read the Responses status, such as {@code completed} or {@code incomplete}.
	 * @param generation the generation to read
	 * @return the status, or empty outside Responses mode or before the terminal event
	 */
	public static Optional<String> responsesStatus(Generation generation) {
		return text(generation.getMetadata().get(RESPONSES_STATUS));
	}

	/**
	 * Read the choice index of a streamed Chat Completions generation.
	 * @param generation the generation to read
	 * @return the choice index; {@code 0} when the key is absent, which is the case for
	 * blocking calls, Responses mode and stream chunks without an index
	 */
	public static int choiceIndex(Generation generation) {
		return generation.getMetadata().get(CHOICE_INDEX) instanceof Integer index ? index : 0;
	}

	private static Optional<String> text(@Nullable Object value) {
		return value instanceof String text ? Optional.of(text) : Optional.empty();
	}

}
