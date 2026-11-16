package de.subhransu.openrouter.springai.audio;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionMetadata;

/**
 * Usage and {@code verbose_json} details of an OpenRouter transcription. Read it with
 * {@link #from(AudioTranscription)}. The verbose fields are {@code null}, and the lists
 * empty, unless the request used {@link OpenRouterTranscriptionFormat#VERBOSE_JSON} and
 * the provider returned them.
 *
 * @param usage the billed usage; {@code null} when OpenRouter sent none
 * @param task the task performed, such as {@code transcribe}
 * @param language the detected or requested language
 * @param duration the audio duration in seconds
 * @param confidence the provider's confidence in the whole transcript, from 0 to 1
 * @param languageConfidence the provider's confidence in the detected language, from 0 to
 * 1
 * @param segments the timestamped segments
 * @param words the timestamped words, with speaker labels when diarized
 * @param entities the detected entities
 * @author Subhransu De
 */
public record OpenRouterTranscriptionMetadata(@Nullable Usage usage, @Nullable String task, @Nullable String language,
		@Nullable Double duration, @Nullable Double confidence, @Nullable Double languageConfidence,
		List<Segment> segments, List<Word> words, List<Entity> entities) implements AudioTranscriptionMetadata {

	private static final OpenRouterTranscriptionMetadata EMPTY = new OpenRouterTranscriptionMetadata(null, null, null,
			null, null, null, List.of(), List.of(), List.of());

	public OpenRouterTranscriptionMetadata {
		segments = List.copyOf(segments);
		words = List.copyOf(words);
		entities = List.copyOf(entities);
	}

	/**
	 * Read the metadata of a transcription without a cast.
	 * @param transcription a transcription
	 * @return its metadata, or empty metadata when it carries no OpenRouter metadata
	 */
	public static OpenRouterTranscriptionMetadata from(AudioTranscription transcription) {
		return transcription.getMetadata() instanceof OpenRouterTranscriptionMetadata metadata ? metadata : EMPTY;
	}

	/**
	 * Usage of a transcription request.
	 *
	 * @param seconds the duration of the input audio in seconds
	 * @param cost the total cost in USD
	 * @param inputTokens the billed input tokens
	 * @param outputTokens the generated output tokens
	 * @param totalTokens the input and output tokens together
	 */
	public record Usage(@Nullable Double seconds, @Nullable Double cost, @Nullable Integer inputTokens,
			@Nullable Integer outputTokens, @Nullable Integer totalTokens) {

	}

	/**
	 * A timestamped transcript segment.
	 *
	 * @param id the segment index
	 * @param start the start time in seconds
	 * @param end the end time in seconds
	 * @param text the segment text
	 * @param speaker the speaker index, when diarized
	 * @param speakerLabel the provider's speaker label, when it uses strings
	 * @param channel the zero-based audio channel, when channels are transcribed apart
	 */
	public record Segment(@Nullable Integer id, @Nullable Double start, @Nullable Double end, @Nullable String text,
			@Nullable Integer speaker, @Nullable String speakerLabel, @Nullable Integer channel) {

	}

	/**
	 * A timestamped word, or a tagged non-speech sound.
	 *
	 * @param word the word, or a tag such as {@code (laughter)} for an audio event
	 * @param start the start time in seconds
	 * @param end the end time in seconds
	 * @param type {@code word}, {@code audio_event}, or {@code null} for a word
	 * @param confidence the provider's confidence from 0 to 1
	 * @param speaker the speaker index, when diarized
	 * @param speakerLabel the provider's speaker label, when it uses strings
	 * @param channel the zero-based audio channel, when channels are transcribed apart
	 */
	public record Word(@Nullable String word, @Nullable Double start, @Nullable Double end, @Nullable String type,
			@Nullable Double confidence, @Nullable Integer speaker, @Nullable String speakerLabel,
			@Nullable Integer channel) {

	}

	/**
	 * An entity detected in the transcript text.
	 *
	 * @param text the entity as it appears in the transcript
	 * @param type the provider's entity type
	 * @param startChar the zero-based offset of its first character in the text
	 * @param endChar the zero-based offset just past its last character
	 */
	public record Entity(@Nullable String text, @Nullable String type, @Nullable Integer startChar,
			@Nullable Integer endChar) {

	}

}
