package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// JSON request body of POST /audio/transcriptions.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record TranscriptionRequest(String model, @JsonProperty("input_audio") InputAudio inputAudio,
		@Nullable String language, @Nullable Double temperature,
		@JsonProperty("response_format") @Nullable String responseFormat,
		@JsonProperty("timestamp_granularities") @Nullable List<String> timestampGranularities,
		@Nullable Boolean diarize, @Nullable List<String> keyterms, @Nullable ProviderPreferences provider,
		@Nullable String user, @JsonProperty("session_id") @Nullable String sessionId,
		@Nullable Map<String, @Nullable Object> trace) {

	/**
	 * Audio to transcribe: base64 {@code data} with its {@code format}, or a {@code url}
	 * that the upstream provider downloads, with an optional {@code format}.
	 *
	 * @param data base64-encoded audio bytes, not a data URL
	 * @param format the audio format, such as {@code wav} or {@code mp3}
	 * @param url an HTTP(S) URL of the audio file
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record InputAudio(@Nullable String data, @Nullable String format, @Nullable String url) {

		public static InputAudio inline(String data, String format) {
			return new InputAudio(data, format, null);
		}

		public static InputAudio remote(String url, @Nullable String format) {
			return new InputAudio(null, format, url);
		}

	}

}
