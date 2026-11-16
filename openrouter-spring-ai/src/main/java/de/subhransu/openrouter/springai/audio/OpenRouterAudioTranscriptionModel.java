package de.subhransu.openrouter.springai.audio;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.audio.mapper.OpenRouterAudioTranscriptionRequestMapper;
import de.subhransu.openrouter.springai.audio.mapper.OpenRouterAudioTranscriptionResponseMapper;
import de.subhransu.openrouter.springai.internal.Retries;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.transcription.AudioTranscriptionOptions;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.transcription.observation.DefaultTranscriptionModelObservationConvention;
import org.springframework.ai.audio.transcription.observation.TranscriptionModelObservationContext;
import org.springframework.ai.audio.transcription.observation.TranscriptionModelObservationConvention;
import org.springframework.ai.audio.transcription.observation.TranscriptionModelObservationDocumentation;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;

/**
 * OpenRouter {@link TranscriptionModel} backed by {@code POST /audio/transcriptions}.
 *
 * <p>
 * The audio resource is read and sent inline as base64 JSON, up to a size limit that
 * defaults to {@value #DEFAULT_MAX_INLINE_AUDIO_BYTES} bytes, OpenRouter's documented
 * upload cap. Usage and {@code verbose_json} details are in
 * {@link OpenRouterTranscriptionMetadata}. OpenRouter has no streaming transcription, so
 * {@code stream} keeps Spring AI's default and fails with
 * {@link UnsupportedOperationException}.
 *
 * @author Subhransu De
 */
public class OpenRouterAudioTranscriptionModel implements TranscriptionModel {

	/**
	 * The default inline audio limit, 25 MiB. OpenAI and Groq models accept larger base64
	 * bodies; raise the limit with {@link Builder#maxInlineAudioBytes(int)} for them.
	 */
	public static final int DEFAULT_MAX_INLINE_AUDIO_BYTES = 25 * 1024 * 1024;

	private static final TranscriptionModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultTranscriptionModelObservationConvention();

	private static final String PROVIDER_NAME = "openrouter";

	private final OpenRouterApi openRouterApi;

	private final OpenRouterAudioTranscriptionOptions defaultOptions;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private TranscriptionModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterAudioTranscriptionRequestMapper requestMapper;

	private final OpenRouterAudioTranscriptionResponseMapper responseMapper = new OpenRouterAudioTranscriptionResponseMapper();

	private OpenRouterAudioTranscriptionModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.mutate().build()
				: OpenRouterAudioTranscriptionOptions.builder().build();
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
		this.requestMapper = new OpenRouterAudioTranscriptionRequestMapper(builder.maxInlineAudioBytes);
	}

	@Override
	public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) {
		OpenRouterAudioTranscriptionOptions options = buildRequestOptions(prompt.getOptions());
		TranscriptionModelObservationContext observationContext = TranscriptionModelObservationContext.builder()
			.prompt(new AudioTranscriptionPrompt(prompt.getInstructions(), options))
			.provider(PROVIDER_NAME)
			.build();
		return TranscriptionModelObservationDocumentation.TRANSCRIPTION_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				var request = this.requestMapper.map(prompt.getInstructions(), options);
				AudioTranscriptionResponse response = Retries.invoke(this.retryTemplate,
						() -> this.responseMapper.map(this.openRouterApi.transcription(request)));
				observationContext.setResponse(response);
				return response;
			});
	}

	/**
	 * Returns a copy of this model's default options, the starting point for per-request
	 * options built with {@code mutate()}.
	 * @return the default options
	 */
	public OpenRouterAudioTranscriptionOptions getOptions() {
		return this.defaultOptions.mutate().build();
	}

	public void setObservationConvention(TranscriptionModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private OpenRouterAudioTranscriptionOptions buildRequestOptions(
			@Nullable AudioTranscriptionOptions runtimeOptions) {
		return runtimeOptions == null ? this.defaultOptions.mutate().build()
				: this.defaultOptions.merge(OpenRouterAudioTranscriptionOptions.fromOptions(runtimeOptions));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterAudioTranscriptionOptions defaultOptions;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

		private int maxInlineAudioBytes = DEFAULT_MAX_INLINE_AUDIO_BYTES;

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.openRouterApi = openRouterApi;
			return this;
		}

		/**
		 * Set the default options of the model. Options in a request override them.
		 * @param options the default options, or {@code null} for the built-in defaults
		 * @return this builder
		 */
		public Builder options(@Nullable OpenRouterAudioTranscriptionOptions options) {
			this.defaultOptions = options;
			return this;
		}

		public Builder retryTemplate(@Nullable RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(@Nullable ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		/**
		 * Set the largest audio resource sent inline. A larger resource is rejected
		 * before the request.
		 * @param maxInlineAudioBytes the limit in bytes, greater than zero
		 * @return this builder
		 */
		public Builder maxInlineAudioBytes(int maxInlineAudioBytes) {
			this.maxInlineAudioBytes = maxInlineAudioBytes;
			return this;
		}

		public OpenRouterAudioTranscriptionModel build() {
			return new OpenRouterAudioTranscriptionModel(this);
		}

	}

}
