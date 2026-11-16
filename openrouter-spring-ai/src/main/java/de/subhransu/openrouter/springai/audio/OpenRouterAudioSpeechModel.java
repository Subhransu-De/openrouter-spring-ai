package de.subhransu.openrouter.springai.audio;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.audio.mapper.OpenRouterAudioSpeechRequestMapper;
import de.subhransu.openrouter.springai.internal.Retries;
import de.subhransu.openrouter.springai.internal.StreamRetries;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.audio.tts.Speech;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechOptions;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.audio.tts.observation.DefaultTextToSpeechModelObservationConvention;
import org.springframework.ai.audio.tts.observation.TextToSpeechModelObservationContext;
import org.springframework.ai.audio.tts.observation.TextToSpeechModelObservationConvention;
import org.springframework.ai.audio.tts.observation.TextToSpeechModelObservationDocumentation;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

/**
 * OpenRouter {@link TextToSpeechModel} backed by {@code POST /audio/speech}.
 *
 * <p>
 * {@link #call(TextToSpeechPrompt)} returns the complete audio in one {@link Speech}.
 * {@link #stream(TextToSpeechPrompt)} emits one response per chunk as the bytes arrive,
 * without buffering the whole body. Concatenate the chunks in order to get the audio. A
 * stream that fails after its first chunk ends with the error, not with completion.
 *
 * @author Subhransu De
 */
public class OpenRouterAudioSpeechModel implements TextToSpeechModel {

	private static final TextToSpeechModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultTextToSpeechModelObservationConvention();

	private static final String PROVIDER_NAME = "openrouter";

	private final OpenRouterApi openRouterApi;

	private final OpenRouterAudioSpeechOptions defaultOptions;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private TextToSpeechModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterAudioSpeechRequestMapper requestMapper = new OpenRouterAudioSpeechRequestMapper();

	private OpenRouterAudioSpeechModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.mutate().build()
				: OpenRouterAudioSpeechOptions.builder().build();
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
	}

	@Override
	public TextToSpeechResponse call(TextToSpeechPrompt prompt) {
		OpenRouterAudioSpeechOptions options = buildRequestOptions(prompt.getOptions());
		TextToSpeechModelObservationContext observationContext = observationContext(prompt, options, false);
		return TextToSpeechModelObservationDocumentation.TEXT_TO_SPEECH_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				var request = this.requestMapper.map(prompt, options);
				byte[] audio = Retries.invoke(this.retryTemplate, () -> this.openRouterApi.speech(request));
				TextToSpeechResponse response = new TextToSpeechResponse(List.of(new Speech(audio)));
				observationContext.setResponse(response);
				return response;
			});
	}

	/**
	 * Stream the audio. Each element holds the next chunk of bytes in arrival order.
	 * Rate-limit rejections are retried before the first chunk only. Cancelling the
	 * subscription closes the connection.
	 */
	@Override
	public Flux<TextToSpeechResponse> stream(TextToSpeechPrompt prompt) {
		OpenRouterAudioSpeechOptions options = buildRequestOptions(prompt.getOptions());
		return Flux.deferContextual(contextView -> {
			TextToSpeechModelObservationContext observationContext = observationContext(prompt, options, true);
			Observation observation = TextToSpeechModelObservationDocumentation.TEXT_TO_SPEECH_MODEL_OPERATION
				.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
						this.observationRegistry);
			Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
			observation.parentObservation(parentObservation);
			// Open the parent's scope while starting so tracing derives the span parent
			// from the reactive context instead of whatever scope is on this thread.
			try (Observation.Scope ignored = parentObservation != null ? parentObservation.openScope()
					: Observation.Scope.NOOP) {
				observation.start();
			}
			return Flux
				.defer(() -> StreamRetries.stream(
						this.openRouterApi.speechStream(this.requestMapper.map(prompt, options)), this.retryTemplate))
				.map(chunk -> new TextToSpeechResponse(List.of(new Speech(chunk))))
				.doOnNext(observationContext::setResponse)
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
		});
	}

	/**
	 * Returns a copy of this model's default options, the starting point for per-request
	 * options built with {@code mutate()}.
	 * @return the default options
	 */
	@Override
	public OpenRouterAudioSpeechOptions getOptions() {
		return this.defaultOptions.mutate().build();
	}

	public void setObservationConvention(TextToSpeechModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private TextToSpeechModelObservationContext observationContext(TextToSpeechPrompt prompt,
			OpenRouterAudioSpeechOptions options, boolean streaming) {
		return TextToSpeechModelObservationContext.builder()
			.prompt(new TextToSpeechPrompt(prompt.getInstructions(), options))
			.provider(PROVIDER_NAME)
			.streaming(streaming)
			.build();
	}

	private OpenRouterAudioSpeechOptions buildRequestOptions(@Nullable TextToSpeechOptions runtimeOptions) {
		return runtimeOptions == null ? this.defaultOptions.mutate().build()
				: this.defaultOptions.merge(OpenRouterAudioSpeechOptions.fromOptions(runtimeOptions));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterAudioSpeechOptions defaultOptions;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

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
		public Builder options(@Nullable OpenRouterAudioSpeechOptions options) {
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

		public OpenRouterAudioSpeechModel build() {
			return new OpenRouterAudioSpeechModel(this);
		}

	}

}
