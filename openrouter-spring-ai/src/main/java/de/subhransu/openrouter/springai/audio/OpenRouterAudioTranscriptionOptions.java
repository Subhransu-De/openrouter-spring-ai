package de.subhransu.openrouter.springai.audio;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.support.OptionChecks;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscriptionOptions;

/**
 * Options for OpenRouter's transcription endpoint ({@code POST /audio/transcriptions}).
 * Mirrors the request fields {@code model}, {@code language}, {@code temperature},
 * {@code response_format}, {@code timestamp_granularities}, {@code diarize},
 * {@code keyterms}, {@code provider}, {@code user}, {@code session_id} and {@code trace},
 * plus how the audio resource is sent.
 *
 * <p>
 * The audio is read and sent inline as base64 by default. Its format comes from
 * {@link Builder#audioFormat(String) audioFormat}, or else from the resource's filename
 * extension. With {@link Builder#sendAudioUrl(Boolean) sendAudioUrl}, an HTTP(S) resource
 * is sent as a URL that the upstream provider downloads instead.
 *
 * <p>
 * {@code diarize} and {@code timestampGranularities} need
 * {@link OpenRouterTranscriptionFormat#VERBOSE_JSON}; a request that sets them with
 * another format is rejected. Of the provider preferences, OpenRouter applies only
 * {@code dataCollection} to transcription, so the others are rejected.
 *
 * @author Subhransu De
 */
public class OpenRouterAudioTranscriptionOptions implements AudioTranscriptionOptions {

	private static final Pattern AUDIO_FORMAT = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9+._-]{0,15}$");

	private static final int MAX_KEYTERMS = 1000;

	private static final int MAX_KEYTERM_LENGTH = 100;

	private @Nullable String model;

	private @Nullable String language;

	private @Nullable Double temperature;

	private @Nullable OpenRouterTranscriptionFormat responseFormat;

	private @Nullable List<OpenRouterTimestampGranularity> timestampGranularities;

	private @Nullable Boolean diarize;

	private @Nullable List<String> keyterms;

	private @Nullable String audioFormat;

	private @Nullable Boolean sendAudioUrl;

	private @Nullable OpenRouterProviderPreferences provider;

	private @Nullable String user;

	private @Nullable String sessionId;

	private @Nullable OpenRouterTrace trace;

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder(this);
	}

	/**
	 * Convert portable options. Only the model carries over.
	 * @param options the options to convert, or {@code null}
	 * @return OpenRouter options, or {@code null} for {@code null}
	 */
	public static @Nullable OpenRouterAudioTranscriptionOptions fromOptions(
			@Nullable AudioTranscriptionOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterAudioTranscriptionOptions openRouterOptions) {
			return openRouterOptions.mutate().build();
		}
		return builder().model(options.getModel()).build();
	}

	/**
	 * Return these options with each value that the request options set replaced. A
	 * request list, {@code provider} or {@code trace} replaces the default as a whole.
	 * @param runtimeOptions the request options, or {@code null}
	 * @return the merged options
	 */
	public OpenRouterAudioTranscriptionOptions merge(@Nullable OpenRouterAudioTranscriptionOptions runtimeOptions) {
		if (runtimeOptions == null) {
			return mutate().build();
		}
		return mutate().model(value(runtimeOptions.model, this.model))
			.language(value(runtimeOptions.language, this.language))
			.temperature(value(runtimeOptions.temperature, this.temperature))
			.responseFormat(value(runtimeOptions.responseFormat, this.responseFormat))
			.timestampGranularities(value(runtimeOptions.timestampGranularities, this.timestampGranularities))
			.diarize(value(runtimeOptions.diarize, this.diarize))
			.keyterms(value(runtimeOptions.keyterms, this.keyterms))
			.audioFormat(value(runtimeOptions.audioFormat, this.audioFormat))
			.sendAudioUrl(value(runtimeOptions.sendAudioUrl, this.sendAudioUrl))
			.provider(value(runtimeOptions.provider, this.provider))
			.user(value(runtimeOptions.user, this.user))
			.sessionId(value(runtimeOptions.sessionId, this.sessionId))
			.trace(value(runtimeOptions.trace, this.trace))
			.build();
	}

	private static <T> @Nullable T value(@Nullable T runtimeValue, @Nullable T defaultValue) {
		return runtimeValue != null ? runtimeValue : defaultValue;
	}

	/**
	 * Return the transcription model.
	 * @return the model, or {@code null} when unset; a request needs one
	 */
	@Override
	@SuppressWarnings("NullAway") // Spring AI declares a non-null model; it is optional
									// here
	public @Nullable String getModel() {
		return this.model;
	}

	public @Nullable String getLanguage() {
		return this.language;
	}

	public @Nullable Double getTemperature() {
		return this.temperature;
	}

	public @Nullable OpenRouterTranscriptionFormat getResponseFormat() {
		return this.responseFormat;
	}

	public @Nullable List<OpenRouterTimestampGranularity> getTimestampGranularities() {
		return this.timestampGranularities;
	}

	public @Nullable Boolean getDiarize() {
		return this.diarize;
	}

	public @Nullable List<String> getKeyterms() {
		return this.keyterms;
	}

	public @Nullable String getAudioFormat() {
		return this.audioFormat;
	}

	public @Nullable Boolean getSendAudioUrl() {
		return this.sendAudioUrl;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public @Nullable String getSessionId() {
		return this.sessionId;
	}

	public @Nullable OpenRouterTrace getTrace() {
		return this.trace;
	}

	public static final class Builder {

		private final OpenRouterAudioTranscriptionOptions options;

		private Builder() {
			this.options = new OpenRouterAudioTranscriptionOptions();
		}

		private Builder(OpenRouterAudioTranscriptionOptions source) {
			this();
			this.options.model = source.model;
			this.options.language = source.language;
			this.options.temperature = source.temperature;
			this.options.responseFormat = source.responseFormat;
			this.options.timestampGranularities = OptionSnapshots.list(source.timestampGranularities);
			this.options.diarize = source.diarize;
			this.options.keyterms = OptionSnapshots.list(source.keyterms);
			this.options.audioFormat = source.audioFormat;
			this.options.sendAudioUrl = source.sendAudioUrl;
			this.options.provider = source.provider;
			this.options.user = source.user;
			this.options.sessionId = source.sessionId;
			this.options.trace = source.trace;
		}

		public Builder model(@Nullable String model) {
			this.options.model = model;
			return this;
		}

		/**
		 * Set the ISO-639-1 language of the audio, such as {@code en}. The provider
		 * detects it when unset.
		 * @param language the language code, or {@code null}
		 * @return this builder
		 */
		public Builder language(@Nullable String language) {
			this.options.language = language;
			return this;
		}

		/**
		 * Set the sampling temperature.
		 * @param temperature a value from 0 to 1, or {@code null}
		 * @return this builder
		 */
		public Builder temperature(@Nullable Double temperature) {
			if (temperature != null && !(temperature >= 0 && temperature <= 1)) {
				throw new IllegalArgumentException(
						"transcription temperature must be from 0 to 1, but was " + temperature);
			}
			this.options.temperature = temperature;
			return this;
		}

		public Builder responseFormat(@Nullable OpenRouterTranscriptionFormat responseFormat) {
			this.options.responseFormat = responseFormat;
			return this;
		}

		/**
		 * Set the timestamp detail levels. Requires
		 * {@link OpenRouterTranscriptionFormat#VERBOSE_JSON}.
		 * @param timestampGranularities the levels, or {@code null}
		 * @return this builder
		 */
		public Builder timestampGranularities(@Nullable List<OpenRouterTimestampGranularity> timestampGranularities) {
			this.options.timestampGranularities = OptionSnapshots
				.list(OptionChecks.noNullElements("timestampGranularities", timestampGranularities));
			return this;
		}

		/**
		 * Label each word with its speaker. Requires
		 * {@link OpenRouterTranscriptionFormat#VERBOSE_JSON}, and only some providers
		 * support it.
		 * @param diarize whether to label speakers, or {@code null}
		 * @return this builder
		 */
		public Builder diarize(@Nullable Boolean diarize) {
			this.options.diarize = diarize;
			return this;
		}

		/**
		 * Set domain terms, names or phrases to bias recognition toward. Only some
		 * providers support them.
		 * @param keyterms at most 1000 terms of 1 to 100 characters each, or {@code null}
		 * @return this builder
		 */
		public Builder keyterms(@Nullable List<String> keyterms) {
			OptionChecks.noNullElements("keyterms", keyterms);
			if (keyterms != null) {
				if (keyterms.size() > MAX_KEYTERMS) {
					throw new IllegalArgumentException(
							"keyterms must hold at most " + MAX_KEYTERMS + " terms, but held " + keyterms.size());
				}
				for (String keyterm : keyterms) {
					int length = keyterm.codePointCount(0, keyterm.length());
					if (length < 1 || length > MAX_KEYTERM_LENGTH) {
						throw new IllegalArgumentException("each keyterm must be 1 to " + MAX_KEYTERM_LENGTH
								+ " characters, but " + OptionChecks.describe(keyterm) + " has " + length);
					}
				}
			}
			this.options.keyterms = OptionSnapshots.list(keyterms);
			return this;
		}

		/**
		 * Set the audio format, such as {@code wav}, {@code mp3} or {@code pcm}
		 * (headerless 16-bit little-endian mono at 16 kHz). When unset, the extension of
		 * the resource's filename is used. Supported formats vary by provider.
		 * @param audioFormat the format, or {@code null}
		 * @return this builder
		 */
		public Builder audioFormat(@Nullable String audioFormat) {
			if (audioFormat != null && !AUDIO_FORMAT.matcher(audioFormat).matches()) {
				throw new IllegalArgumentException("audioFormat " + OptionChecks.describe(audioFormat)
						+ " must be 1 to 16 letters, digits or +._- and start with a letter or digit");
			}
			this.options.audioFormat = audioFormat;
			return this;
		}

		/**
		 * Send an HTTP(S) resource as a URL that the upstream provider downloads, instead
		 * of reading it and sending the bytes inline. The provider then fetches the URL
		 * itself, so it must be publicly reachable, and the inline size limit does not
		 * apply. Only some providers accept a URL. Other resources are rejected.
		 * @param sendAudioUrl whether to send the URL, or {@code null} for inline audio
		 * @return this builder
		 */
		public Builder sendAudioUrl(@Nullable Boolean sendAudioUrl) {
			this.options.sendAudioUrl = sendAudioUrl;
			return this;
		}

		/**
		 * Set the provider preferences. OpenRouter applies only {@code dataCollection} to
		 * transcription. A request that sets another preference is rejected.
		 * @param provider the preferences, or {@code null}
		 * @return this builder
		 */
		public Builder provider(@Nullable OpenRouterProviderPreferences provider) {
			this.options.provider = provider;
			return this;
		}

		/**
		 * End-user identifier for OpenRouter's logs, sent as {@code user}. OpenRouter
		 * does not forward it to the upstream provider.
		 */
		public Builder user(@Nullable String user) {
			this.options.user = user;
			return this;
		}

		/**
		 * Groups related requests for observability, sent as {@code session_id}. At most
		 * 256 characters.
		 */
		public Builder sessionId(@Nullable String sessionId) {
			this.options.sessionId = OptionChecks.sessionId(sessionId);
			return this;
		}

		/**
		 * Observability metadata, sent as {@code trace}. A per-request trace replaces the
		 * default trace as a whole.
		 */
		public Builder trace(@Nullable OpenRouterTrace trace) {
			this.options.trace = trace;
			return this;
		}

		public OpenRouterAudioTranscriptionOptions build() {
			return new Builder(this.options).options;
		}

	}

}
