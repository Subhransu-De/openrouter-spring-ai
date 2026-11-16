package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(OpenRouterAudioSpeechProperties.CONFIG_PREFIX)
public class OpenRouterAudioSpeechProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.audio.speech";

	/**
	 * Text-to-speech model id in vendor/model form.
	 */
	private @Nullable String model;

	/**
	 * Provider-specific voice. Most providers require one.
	 */
	private @Nullable String voice;

	/**
	 * Audio format, sent as response_format: mp3 or pcm. The endpoint defaults to pcm.
	 */
	private @Nullable String format;

	/**
	 * Playback speed multiplier. Providers without speed control ignore it or reject a
	 * value other than 1.
	 */
	private @Nullable Double speed;

	/**
	 * Delivery instructions, such as tone or pacing, for providers that support them.
	 */
	private @Nullable String instructions;

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
	 * Provider preferences. OpenRouter applies only data-collection to audio requests.
	 */
	private final OpenRouterAudioProviderProperties provider = new OpenRouterAudioProviderProperties();

	public OpenRouterAudioSpeechOptions toOptions() {
		return OpenRouterAudioSpeechOptions.builder()
			.model(this.model)
			.voice(this.voice)
			.format(this.format)
			.speed(this.speed)
			.instructions(this.instructions)
			.user(this.user)
			.sessionId(this.sessionId)
			.trace(this.trace)
			.provider(this.provider.toPreferences())
			.build();
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable String getVoice() {
		return this.voice;
	}

	public void setVoice(@Nullable String voice) {
		this.voice = voice;
	}

	public @Nullable String getFormat() {
		return this.format;
	}

	public void setFormat(@Nullable String format) {
		this.format = format;
	}

	public @Nullable Double getSpeed() {
		return this.speed;
	}

	public void setSpeed(@Nullable Double speed) {
		this.speed = speed;
	}

	public @Nullable String getInstructions() {
		return this.instructions;
	}

	public void setInstructions(@Nullable String instructions) {
		this.instructions = instructions;
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

	public OpenRouterAudioProviderProperties getProvider() {
		return this.provider;
	}

}
