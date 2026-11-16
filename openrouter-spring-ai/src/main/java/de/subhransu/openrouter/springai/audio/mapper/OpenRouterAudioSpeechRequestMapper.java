package de.subhransu.openrouter.springai.audio.mapper;

import de.subhransu.openrouter.springai.api.dto.SpeechRequest;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechOptions;
import de.subhransu.openrouter.springai.chat.mapper.TraceMapper;
import java.util.List;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.util.Assert;

/**
 * Maps a Spring AI speech prompt and merged options to the OpenRouter speech request.
 *
 * @author Subhransu De
 */
public final class OpenRouterAudioSpeechRequestMapper {

	private static final List<String> PROVIDER_FIELDS = List.of(AudioProviderMapper.ALLOW_FALLBACKS,
			AudioProviderMapper.DATA_COLLECTION, AudioProviderMapper.ORDER, AudioProviderMapper.IGNORE);

	public SpeechRequest map(TextToSpeechPrompt prompt, OpenRouterAudioSpeechOptions options) {
		String model = options.getModel();
		// OpenRouter has no default speech model; fail before the round trip.
		Assert.hasText(model, "Speech model must not be empty");
		String text = prompt.getInstructions().getText();
		Assert.hasText(text, "Speech text must not be empty");
		return new SpeechRequest(model, text, options.getVoice(), options.getFormat(), options.getSpeed(),
				options.getInstructions(),
				AudioProviderMapper.map(options.getProvider(), "/audio/speech", PROVIDER_FIELDS), options.getUser(),
				options.getSessionId(), TraceMapper.map(options.getTrace()));
	}

}
