package de.subhransu.openrouter.springai.audio;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.support.OptionChecks;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.tts.TextToSpeechOptions;

/**
 * Options for OpenRouter's text-to-speech endpoint ({@code POST /audio/speech}). The
 * portable {@code format} is sent as {@code response_format}. OpenRouter documents
 * {@code mp3} and {@code pcm} (16-bit little-endian), and the endpoint defaults to
 * {@code pcm}. The OpenRouter-specific fields are {@code instructions}, the
 * {@code provider} routing preferences, {@code user}, {@code session_id} and
 * {@code trace}.
 *
 * <p>
 * Of the provider preferences, the endpoint accepts {@code allowFallbacks},
 * {@code dataCollection}, {@code order} and {@code ignore}. The others are rejected
 * before the request. Voice cloning through {@code input_references} and multi-speaker
 * input are not supported yet.
 *
 * @author Subhransu De
 */
public class OpenRouterAudioSpeechOptions implements TextToSpeechOptions {

	/** The {@code response_format} values that OpenRouter documents. */
	public static final List<String> FORMATS = List.of("mp3", "pcm");

	private @Nullable String model;

	private @Nullable String voice;

	private @Nullable String format;

	private @Nullable Double speed;

	private @Nullable String instructions;

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
	 * Convert portable options. The model, voice, format and speed carry over. A format
	 * that OpenRouter does not document is rejected.
	 * @param options the options to convert, or {@code null}
	 * @return OpenRouter options, or {@code null} for {@code null}
	 */
	public static @Nullable OpenRouterAudioSpeechOptions fromOptions(@Nullable TextToSpeechOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterAudioSpeechOptions openRouterOptions) {
			return openRouterOptions.mutate().build();
		}
		return builder().model(options.getModel())
			.voice(options.getVoice())
			.format(options.getFormat())
			.speed(options.getSpeed())
			.build();
	}

	/**
	 * Return these options with each value that the request options set replaced. A
	 * request {@code provider} or {@code trace} replaces the default as a whole.
	 * @param runtimeOptions the request options, or {@code null}
	 * @return the merged options
	 */
	public OpenRouterAudioSpeechOptions merge(@Nullable OpenRouterAudioSpeechOptions runtimeOptions) {
		if (runtimeOptions == null) {
			return mutate().build();
		}
		return mutate().model(value(runtimeOptions.model, this.model))
			.voice(value(runtimeOptions.voice, this.voice))
			.format(value(runtimeOptions.format, this.format))
			.speed(value(runtimeOptions.speed, this.speed))
			.instructions(value(runtimeOptions.instructions, this.instructions))
			.provider(value(runtimeOptions.provider, this.provider))
			.user(value(runtimeOptions.user, this.user))
			.sessionId(value(runtimeOptions.sessionId, this.sessionId))
			.trace(value(runtimeOptions.trace, this.trace))
			.build();
	}

	private static <T> @Nullable T value(@Nullable T runtimeValue, @Nullable T defaultValue) {
		return runtimeValue != null ? runtimeValue : defaultValue;
	}

	@Override
	public @Nullable String getModel() {
		return this.model;
	}

	@Override
	public @Nullable String getVoice() {
		return this.voice;
	}

	/**
	 * Return the audio format, sent as {@code response_format}.
	 * @return {@code mp3}, {@code pcm}, or {@code null} for the endpoint default
	 * ({@code pcm})
	 */
	@Override
	public @Nullable String getFormat() {
		return this.format;
	}

	@Override
	public @Nullable Double getSpeed() {
		return this.speed;
	}

	public @Nullable String getInstructions() {
		return this.instructions;
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

	public static final class Builder implements TextToSpeechOptions.Builder {

		private final OpenRouterAudioSpeechOptions options;

		private Builder() {
			this.options = new OpenRouterAudioSpeechOptions();
		}

		private Builder(OpenRouterAudioSpeechOptions source) {
			this();
			this.options.model = source.model;
			this.options.voice = source.voice;
			this.options.format = source.format;
			this.options.speed = source.speed;
			this.options.instructions = source.instructions;
			this.options.provider = source.provider;
			this.options.user = source.user;
			this.options.sessionId = source.sessionId;
			this.options.trace = source.trace;
		}

		@Override
		public Builder model(@Nullable String model) {
			this.options.model = model;
			return this;
		}

		/**
		 * Set the provider-specific voice. Most providers require one.
		 * @param voice the voice, or {@code null}; not empty
		 * @return this builder
		 */
		@Override
		public Builder voice(@Nullable String voice) {
			if (voice != null && voice.isEmpty()) {
				throw new IllegalArgumentException("speech voice must not be empty; leave it unset instead");
			}
			this.options.voice = voice;
			return this;
		}

		/**
		 * Set the audio format, sent as {@code response_format}.
		 * @param format {@code mp3} or {@code pcm}, or {@code null} for the endpoint
		 * default ({@code pcm})
		 * @return this builder
		 */
		@Override
		public Builder format(@Nullable String format) {
			if (format != null && !FORMATS.contains(format)) {
				throw new IllegalArgumentException("speech format " + OptionChecks.describe(format)
						+ " is not supported; use " + String.join(" or ", FORMATS));
			}
			this.options.format = format;
			return this;
		}

		/**
		 * Set the playback speed multiplier. Providers without speed control ignore it or
		 * reject a value other than 1.
		 * @param speed a positive, finite multiplier, or {@code null}
		 * @return this builder
		 */
		@Override
		public Builder speed(@Nullable Double speed) {
			if (speed != null && (!Double.isFinite(speed) || speed <= 0)) {
				throw new IllegalArgumentException("speech speed must be a positive finite number, but was " + speed);
			}
			this.options.speed = speed;
			return this;
		}

		/**
		 * Set delivery instructions such as tone or pacing. Providers that do not support
		 * them ignore them.
		 * @param instructions the instructions, or {@code null}
		 * @return this builder
		 */
		public Builder instructions(@Nullable String instructions) {
			this.options.instructions = instructions;
			return this;
		}

		/**
		 * Set the provider routing preferences. The endpoint accepts
		 * {@code allowFallbacks}, {@code dataCollection}, {@code order} and
		 * {@code ignore}. A request that sets another preference is rejected.
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

		@Override
		public OpenRouterAudioSpeechOptions build() {
			return new Builder(this.options).options;
		}

	}

}
