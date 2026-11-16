package de.subhransu.openrouter.springai.audio.mapper;

import de.subhransu.openrouter.springai.api.dto.TranscriptionResponse;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionMetadata;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;

/**
 * Maps the OpenRouter transcription response to Spring AI. The text becomes the output;
 * usage and the {@code verbose_json} details become
 * {@link OpenRouterTranscriptionMetadata}.
 *
 * @author Subhransu De
 */
public final class OpenRouterAudioTranscriptionResponseMapper {

	public AudioTranscriptionResponse map(TranscriptionResponse response) {
		String text = response.text();
		if (text == null) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.MISSING_FIELD,
					"/audio/transcriptions", "OpenRouter transcription response has no text");
		}
		TranscriptionResponse.TranscriptionUsage usage = response.usage();
		OpenRouterTranscriptionMetadata metadata = new OpenRouterTranscriptionMetadata(
				usage == null ? null
						: new OpenRouterTranscriptionMetadata.Usage(usage.seconds(), usage.cost(), usage.inputTokens(),
								usage.outputTokens(), usage.totalTokens()),
				response.task(), response.language(), response.duration(), response.confidence(),
				response.languageConfidence(),
				list(response.segments(),
						segment -> new OpenRouterTranscriptionMetadata.Segment(segment.id(), segment.start(),
								segment.end(), segment.text(), segment.speaker(), segment.speakerLabel(),
								segment.channel())),
				list(response.words(),
						word -> new OpenRouterTranscriptionMetadata.Word(word.word(), word.start(), word.end(),
								word.type(), word.confidence(), word.speaker(), word.speakerLabel(), word.channel())),
				list(response.entities(), entity -> new OpenRouterTranscriptionMetadata.Entity(entity.text(),
						entity.type(), entity.startChar(), entity.endChar())));
		return new AudioTranscriptionResponse(new AudioTranscription(text).withTranscriptionMetadata(metadata));
	}

	// Response arrays can hold null entries; drop them rather than fail the transcript.
	private static <S, T> List<T> list(@Nullable List<@Nullable S> values, Function<S, T> mapper) {
		return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(mapper).toList();
	}

}
