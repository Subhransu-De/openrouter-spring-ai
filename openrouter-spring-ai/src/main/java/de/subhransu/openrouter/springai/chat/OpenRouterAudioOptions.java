package de.subhransu.openrouter.springai.chat;

import java.util.Set;
import org.springframework.util.Assert;

/**
 * Voice and output encoding for streaming Chat Completions. Model support is required.
 *
 * @param voice provider-specific voice name
 * @param format one of wav, mp3, flac, opus, or pcm16
 * @author OpenRouter Spring AI contributors
 */
public record OpenRouterAudioOptions(String voice, String format) {

	public OpenRouterAudioOptions {
		Assert.hasText(voice, "Audio voice must not be blank");
		Assert.isTrue(format != null && Set.of("wav", "mp3", "flac", "opus", "pcm16").contains(format),
				"Audio format must be wav, mp3, flac, opus, or pcm16");
	}

}
