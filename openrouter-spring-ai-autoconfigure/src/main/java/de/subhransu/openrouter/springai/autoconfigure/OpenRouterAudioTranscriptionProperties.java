package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionModel;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterTimestampGranularity;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionFormat;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(OpenRouterAudioTranscriptionProperties.CONFIG_PREFIX)
public class OpenRouterAudioTranscriptionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.audio.transcription";

	/**
	 * Transcription model id in vendor/model form.
	 */
	private @Nullable String model;

	/**
	 * ISO-639-1 language of the audio, such as en. The provider detects it when unset.
	 */
	private @Nullable String language;

	/**
	 * Sampling temperature from 0 to 1.
	 */
	private @Nullable Double temperature;

	/**
	 * Response format: json, or verbose-json for timestamps, words and speaker labels.
	 */
	private @Nullable OpenRouterTranscriptionFormat responseFormat;

	/**
	 * Comma-separated timestamp detail levels, word and segment. Requires verbose-json.
	 */
	private @Nullable List<OpenRouterTimestampGranularity> timestampGranularities;

	/**
	 * Whether to label each word with its speaker. Requires verbose-json and a provider
	 * that supports it.
	 */
	private @Nullable Boolean diarize;

	/**
	 * Comma-separated domain terms, names or phrases to bias recognition toward.
	 */
	private @Nullable List<String> keyterms;

	/**
	 * Audio format, such as wav or mp3. When unset, the extension of the resource's
	 * filename is used.
	 */
	private @Nullable String audioFormat;

	/**
	 * Whether to send an HTTP(S) resource as a URL that the upstream provider downloads,
	 * instead of reading it and sending the bytes inline.
	 */
	private @Nullable Boolean sendAudioUrl;

	/**
	 * Largest audio resource sent inline. Raise it for OpenAI and Groq models, which
	 * accept larger base64 bodies.
	 */
	private DataSize maxInlineAudioSize = DataSize
		.ofBytes(OpenRouterAudioTranscriptionModel.DEFAULT_MAX_INLINE_AUDIO_BYTES);

	/**
	 * End-user identifier for OpenRouter's logs. It is not sent to the upstream provider.
	 */
	private @Nullable String user;

	/**
	 * Default session id for observability grouping, sent as session_id. At most 256
	 * characters.
	 */
	private @Nullable String sessionId;

	/**
	 * Observability metadata sent as trace. A request trace replaces it as a whole.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterTrace trace;

	/**
	 * Provider preferences that OpenRouter applies to transcription.
	 */
	private final Provider provider = new Provider();

	public OpenRouterAudioTranscriptionOptions toOptions() {
		return OpenRouterAudioTranscriptionOptions.builder()
			.model(this.model)
			.language(this.language)
			.temperature(this.temperature)
			.responseFormat(this.responseFormat)
			.timestampGranularities(this.timestampGranularities)
			.diarize(this.diarize)
			.keyterms(this.keyterms)
			.audioFormat(this.audioFormat)
			.sendAudioUrl(this.sendAudioUrl)
			.user(this.user)
			.sessionId(this.sessionId)
			.trace(this.trace)
			.provider(this.provider.toPreferences())
			.build();
	}

	int maxInlineAudioBytes() {
		long bytes = this.maxInlineAudioSize.toBytes();
		if (bytes <= 0 || bytes >= Integer.MAX_VALUE) {
			throw new IllegalStateException(CONFIG_PREFIX + ".max-inline-audio-size must be greater than zero and "
					+ "less than 2 GB, but was " + this.maxInlineAudioSize);
		}
		return (int) bytes;
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable String getLanguage() {
		return this.language;
	}

	public void setLanguage(@Nullable String language) {
		this.language = language;
	}

	public @Nullable Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(@Nullable Double temperature) {
		this.temperature = temperature;
	}

	public @Nullable OpenRouterTranscriptionFormat getResponseFormat() {
		return this.responseFormat;
	}

	public void setResponseFormat(@Nullable OpenRouterTranscriptionFormat responseFormat) {
		this.responseFormat = responseFormat;
	}

	public @Nullable List<OpenRouterTimestampGranularity> getTimestampGranularities() {
		return this.timestampGranularities;
	}

	public void setTimestampGranularities(@Nullable List<OpenRouterTimestampGranularity> timestampGranularities) {
		this.timestampGranularities = timestampGranularities;
	}

	public @Nullable Boolean getDiarize() {
		return this.diarize;
	}

	public void setDiarize(@Nullable Boolean diarize) {
		this.diarize = diarize;
	}

	public @Nullable List<String> getKeyterms() {
		return this.keyterms;
	}

	public void setKeyterms(@Nullable List<String> keyterms) {
		this.keyterms = keyterms;
	}

	public @Nullable String getAudioFormat() {
		return this.audioFormat;
	}

	public void setAudioFormat(@Nullable String audioFormat) {
		this.audioFormat = audioFormat;
	}

	public @Nullable Boolean getSendAudioUrl() {
		return this.sendAudioUrl;
	}

	public void setSendAudioUrl(@Nullable Boolean sendAudioUrl) {
		this.sendAudioUrl = sendAudioUrl;
	}

	public DataSize getMaxInlineAudioSize() {
		return this.maxInlineAudioSize;
	}

	public void setMaxInlineAudioSize(DataSize maxInlineAudioSize) {
		this.maxInlineAudioSize = maxInlineAudioSize;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public void setUser(@Nullable String user) {
		this.user = user;
	}

	public @Nullable String getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(@Nullable String sessionId) {
		this.sessionId = sessionId;
	}

	public @Nullable OpenRouterTrace getTrace() {
		return this.trace;
	}

	public void setTrace(@Nullable OpenRouterTrace trace) {
		this.trace = trace;
	}

	public Provider getProvider() {
		return this.provider;
	}

	public static class Provider {

		/**
		 * Data collection policy that upstream providers must meet: allow or deny. The
		 * only routing preference OpenRouter applies to transcription.
		 */
		private @Nullable String dataCollection;

		@Nullable OpenRouterProviderPreferences toPreferences() {
			return this.dataCollection == null ? null
					: OpenRouterProviderPreferences.builder().dataCollection(this.dataCollection).build();
		}

		public @Nullable String getDataCollection() {
			return this.dataCollection;
		}

		public void setDataCollection(@Nullable String dataCollection) {
			this.dataCollection = dataCollection;
		}

	}

}
