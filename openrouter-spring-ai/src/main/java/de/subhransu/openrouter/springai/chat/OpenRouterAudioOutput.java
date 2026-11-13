package de.subhransu.openrouter.springai.chat;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.AssistantMessage;

/**
 * The transcript and details of a choice's output audio. The audio bytes are the
 * message's {@code Media}; read them with {@link OpenRouterMedia#bytes}.
 *
 * @param transcript the spoken text, which is not part of the message text
 * @param format the requested audio format, such as {@code pcm16}
 * @param id the provider's audio identifier, or {@code null} when not sent
 * @param expiresAt when the provider discards the audio, in epoch seconds, or
 * {@code null} when not sent
 * @author Subhransu De
 */
public record OpenRouterAudioOutput(String transcript, String format, @Nullable String id, @Nullable Long expiresAt) {

	/**
	 * Read the output audio details from the assistant message that carries the completed
	 * audio.
	 * @param message the assistant message to read
	 * @return the audio details, or empty when the message carries no output audio
	 */
	public static Optional<OpenRouterAudioOutput> from(AssistantMessage message) {
		if (!(message.getMetadata().get(OpenRouterMetadata.AUDIO) instanceof Map<?, ?> audio)) {
			return Optional.empty();
		}
		Object id = audio.get("id");
		Object expiresAt = audio.get("expires_at");
		return Optional.of(new OpenRouterAudioOutput(text(audio.get("transcript")), text(audio.get("format")),
				id instanceof String value ? value : null,
				expiresAt instanceof Number value ? value.longValue() : null));
	}

	private static String text(@Nullable Object value) {
		return value instanceof String text ? text : "";
	}

}
