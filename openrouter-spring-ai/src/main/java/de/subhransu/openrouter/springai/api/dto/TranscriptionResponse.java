package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import org.jspecify.annotations.Nullable;

// Response of POST /audio/transcriptions. Fields other than text and usage are present
// only for verbose_json, and only when the provider returns them.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TranscriptionResponse(@Nullable String text, @Nullable TranscriptionUsage usage, @Nullable String task,
		@Nullable String language, @Nullable Double duration, @Nullable Double confidence,
		@JsonProperty("language_confidence") @Nullable Double languageConfidence,
		@Nullable List<@Nullable Segment> segments, @Nullable List<@Nullable Word> words,
		@Nullable List<@Nullable Entity> entities) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TranscriptionUsage(@Nullable Double seconds, @Nullable Double cost,
			@JsonProperty("input_tokens") @Nullable Integer inputTokens,
			@JsonProperty("output_tokens") @Nullable Integer outputTokens,
			@JsonProperty("total_tokens") @Nullable Integer totalTokens) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Segment(@Nullable Integer id, @Nullable Double start, @Nullable Double end, @Nullable String text,
			@Nullable Integer speaker, @JsonProperty("speaker_label") @Nullable String speakerLabel,
			@Nullable Integer channel, @Nullable Integer seek, @Nullable List<@Nullable Integer> tokens,
			@Nullable Double temperature, @JsonProperty("avg_logprob") @Nullable Double avgLogprob,
			@JsonProperty("compression_ratio") @Nullable Double compressionRatio,
			@JsonProperty("no_speech_prob") @Nullable Double noSpeechProb) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Word(@Nullable String word, @Nullable Double start, @Nullable Double end, @Nullable String type,
			@Nullable Double confidence, @Nullable Integer speaker,
			@JsonProperty("speaker_label") @Nullable String speakerLabel, @Nullable Integer channel) {

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Entity(@Nullable String text, @Nullable String type,
			@JsonProperty("start_char") @Nullable Integer startChar,
			@JsonProperty("end_char") @Nullable Integer endChar) {

	}

}
