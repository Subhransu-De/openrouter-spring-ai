package de.subhransu.openrouter.springai.chat;

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.model.Generation;

/**
 * The metadata keys that this library sets on Spring AI responses, and typed readers for
 * them. The readers never throw for an absent key or a value of another type: they return
 * {@link Optional#empty()} or the documented default instead.
 *
 * <p>
 * Reasoning is a {@link ReasoningPart} of the assistant message: read it with
 * {@link AssistantMessage#getReasoning()}. The part's {@link OpaquePayload} holds what
 * the provider needs back on the next turn, and the request mappers replay it from there.
 * The reasoning metadata keys are still written for one release, and read on replay only
 * for a message without reasoning parts, such as one stored by 0.3.0.
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
	 * The {@link OpaquePayload#provider() provider} of the payloads that Chat Completions
	 * mode produces. Only payloads of this provider are replayed in that mode.
	 * @since 0.4.0
	 */
	public static final String CHAT_COMPLETIONS_PROVIDER = "openrouter.chat.completions";

	/**
	 * The {@link OpaquePayload#provider() provider} of the payloads and
	 * {@link UnknownPart}s that Responses mode produces. Only parts of this provider are
	 * replayed in that mode.
	 * @since 0.4.0
	 */
	public static final String RESPONSES_PROVIDER = "openrouter.responses";

	/**
	 * The {@link OpaquePayload#kind() kind} of a Chat Completions {@link ReasoningPart}
	 * payload: the message's {@code reasoning_details} entries as a JSON array,
	 * signatures and encrypted entries included, sent back unchanged.
	 * @since 0.4.0
	 */
	public static final String REASONING_DETAILS_KIND = "reasoning_details";

	/**
	 * The {@link OpaquePayload#kind() kind} of a Responses payload: the output item that
	 * a reasoning, text or tool call part was mapped from, as JSON, sent back unchanged.
	 * @since 0.4.0
	 */
	public static final String RESPONSES_OUTPUT_ITEM_KIND = "output_item";

	/**
	 * Reasoning text ({@code String}). Generation and assistant message metadata, both
	 * request modes.
	 * @deprecated since 0.4.0 for removal in 0.5.0; read the text of
	 * {@link AssistantMessage#getReasoning()}, or in a stream the {@link ReasoningPart}
	 * of the chunk
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
	public static final String REASONING = "openrouter.reasoning";

	/**
	 * Opaque Chat Completions reasoning details ({@code List}) replayed with the
	 * assistant message. Assistant message metadata.
	 * @deprecated since 0.4.0 for removal in 0.5.0; the details are the
	 * {@link #REASONING_DETAILS_KIND} payload of the {@link ReasoningPart}
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
	public static final String REASONING_DETAILS = "openrouter.reasoning_details";

	/**
	 * Opaque Responses output items ({@code List}) replayed with the assistant message.
	 * Assistant message metadata.
	 * @deprecated since 0.4.0 for removal in 0.5.0; each item is the
	 * {@link #RESPONSES_OUTPUT_ITEM_KIND} payload of its part, or an {@link UnknownPart}
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
	public static final String RESPONSES_OUTPUT_ITEMS = "openrouter.responses.output_items";

	/**
	 * Opaque Responses reasoning items ({@code List}). Assistant message metadata.
	 * @deprecated since 0.4.0 for removal in 0.5.0; each item is the
	 * {@link #RESPONSES_OUTPUT_ITEM_KIND} payload of its {@link ReasoningPart}
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
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
	 * Assistant message metadata. Read the log probabilities with
	 * {@link OpenRouterLogprobs#from(AssistantMessage)}.
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
	 * @deprecated since 0.4.0 for removal in 0.5.0; read
	 * {@link AssistantMessage#getReasoning()} of the generation's output
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
	public static Optional<String> reasoning(Generation generation) {
		return text(generation.getMetadata().get(REASONING));
	}

	/**
	 * Read the reasoning text from the assistant message metadata. In a stream this is
	 * the text so far.
	 * @param message the assistant message to read
	 * @return the reasoning text, or empty when the model sent none
	 * @deprecated since 0.4.0 for removal in 0.5.0; read
	 * {@link AssistantMessage#getReasoning()}
	 */
	@Deprecated(since = "0.4.0", forRemoval = true)
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
