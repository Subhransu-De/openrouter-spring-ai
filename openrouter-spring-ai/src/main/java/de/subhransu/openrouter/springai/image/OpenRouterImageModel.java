package de.subhransu.openrouter.springai.image;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageRequestMapper;
import de.subhransu.openrouter.springai.image.mapper.OpenRouterImageResponseMapper;
import de.subhransu.openrouter.springai.internal.Retries;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImageOptions;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.observation.DefaultImageModelObservationConvention;
import org.springframework.ai.image.observation.ImageModelObservationContext;
import org.springframework.ai.image.observation.ImageModelObservationConvention;
import org.springframework.ai.image.observation.ImageModelObservationDocumentation;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

/**
 * OpenRouter {@link ImageModel} backed by the unified Image API ({@code POST /images}).
 *
 * <p>
 * {@link #call(ImagePrompt)} performs a blocking generation. {@link #stream(ImagePrompt)}
 * exposes OpenRouter's SSE image streaming: partial-image previews and completed images
 * arrive as separate {@link ImageResponse} elements, distinguishable via
 * {@link OpenRouterImageGenerationMetadata#partialImageIndex()} and the
 * {@code openrouter.event_type} metadata entry. Spring AI has no streaming image
 * abstraction yet, so {@code stream} is an OpenRouter-specific extension.
 *
 * @author Subhransu De
 */
public class OpenRouterImageModel implements ImageModel {

	private static final ImageModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultImageModelObservationConvention();

	private static final String PROVIDER_NAME = "openrouter";

	private final OpenRouterApi openRouterApi;

	private final OpenRouterImageOptions defaultOptions;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private ImageModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterImageRequestMapper requestMapper = new OpenRouterImageRequestMapper();

	private final OpenRouterImageResponseMapper responseMapper = new OpenRouterImageResponseMapper();

	private OpenRouterImageModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.copy()
				: OpenRouterImageOptions.builder().build();
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
	}

	@Override
	public ImageResponse call(ImagePrompt prompt) {
		OpenRouterImageOptions options = buildRequestOptions(prompt.getOptions());
		ImageModelObservationContext observationContext = ImageModelObservationContext.builder()
			.imagePrompt(new ImagePrompt(prompt.getInstructions(), options))
			.provider(PROVIDER_NAME)
			.build();
		return ImageModelObservationDocumentation.IMAGE_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				ImagesRequest request = this.requestMapper.map(prompt, options, false);
				ImagesResponse imagesResponse = Retries.invoke(this.retryTemplate,
						() -> this.openRouterApi.images(request));
				ImageResponse response = this.responseMapper.map(imagesResponse);
				observationContext.setResponse(response);
				return response;
			});
	}

	/**
	 * Stream a generation over SSE. With providers that stream natively, partial previews
	 * precede completed images. With providers that do not, OpenRouter answers with the
	 * complete generation in one JSON document, emitted as one completed element per
	 * image.
	 */
	public Flux<ImageResponse> stream(ImagePrompt prompt) {
		OpenRouterImageOptions options = buildRequestOptions(prompt.getOptions());
		return Flux.deferContextual(contextView -> {
			ImageModelObservationContext observationContext = ImageModelObservationContext.builder()
				.imagePrompt(new ImagePrompt(prompt.getInstructions(), options))
				.provider(PROVIDER_NAME)
				.build();
			Observation observation = ImageModelObservationDocumentation.IMAGE_MODEL_OPERATION.observation(
					this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry);
			Observation parentObservation = contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null);
			observation.parentObservation(parentObservation);
			// Open the parent's scope while starting so tracing derives the span parent
			// from the reactive context instead of whatever scope is on this thread.
			try (Observation.Scope ignored = parentObservation != null ? parentObservation.openScope()
					: Observation.Scope.NOOP) {
				observation.start();
			}
			return Flux.defer(() -> {
				ImagesRequest request = this.requestMapper.map(prompt, options, true);
				return this.openRouterApi.imagesStream(request);
			})
				.map(this.responseMapper::map)
				.doOnNext(observationContext::setResponse)
				.doOnError(observation::error)
				.doFinally(signal -> observation.stop())
				.contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));
		});
	}

	public void setObservationConvention(ImageModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private OpenRouterImageOptions buildRequestOptions(ImageOptions runtimeOptions) {
		return runtimeOptions == null ? this.defaultOptions.copy()
				: this.defaultOptions.merge(OpenRouterImageOptions.fromOptions(runtimeOptions));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterImageOptions defaultOptions;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.openRouterApi = openRouterApi;
			return this;
		}

		public Builder defaultOptions(@Nullable OpenRouterImageOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
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

		public OpenRouterImageModel build() {
			return new OpenRouterImageModel(this);
		}

	}

}
