package de.subhransu.openrouter.springai.image;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.OptionChecks;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.List;
import java.util.Map;
import org.springframework.ai.image.ImageOptions;

/**
 * Options for OpenRouter's unified Image API ({@code POST /images}). Exposes the
 * normalized OpenRouter fields: {@code n}, {@code size} (via width/height),
 * {@code resolution}, {@code aspect_ratio}, {@code quality}, {@code output_format},
 * {@code background}, {@code output_compression}, {@code seed}, {@code input_references}
 * (reference images for image-to-image work) and the {@code provider} object.
 *
 * <p>
 * Images always come back base64-encoded, so the portable {@code responseFormat} is fixed
 * to {@code b64_json}; OpenRouter has no equivalent of the portable {@code style} knob,
 * so it is always {@code null}.
 *
 * @author Subhransu De
 */
public class OpenRouterImageOptions implements ImageOptions {

	private @Nullable String model;

	private @Nullable Integer n;

	private @Nullable Integer width;

	private @Nullable Integer height;

	private @Nullable String resolution;

	private @Nullable String aspectRatio;

	private @Nullable String quality;

	private @Nullable String outputFormat;

	private @Nullable String background;

	private @Nullable Integer outputCompression;

	private @Nullable Integer seed;

	private @Nullable List<String> inputReferences;

	private @Nullable Map<String, @Nullable Object> provider;

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder(this);
	}

	public static @Nullable OpenRouterImageOptions fromOptions(@Nullable ImageOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterImageOptions openRouterOptions) {
			return openRouterOptions.mutate().build();
		}
		if (options.getResponseFormat() != null && !"b64_json".equals(options.getResponseFormat())) {
			throw new IllegalArgumentException(
					"OpenRouter image responseFormat must be null or 'b64_json'; URL output cannot be requested");
		}
		if (options.getStyle() != null) {
			throw new IllegalArgumentException(
					"OpenRouter does not support portable image style; leave style unset and put provider-specific settings in provider.options");
		}
		return OpenRouterImageOptions.builder()
			.model(options.getModel())
			.n(options.getN())
			.width(options.getWidth())
			.height(options.getHeight())
			.build();
	}

	public OpenRouterImageOptions merge(@Nullable OpenRouterImageOptions runtimeOptions) {
		if (runtimeOptions == null) {
			return this.mutate().build();
		}
		return this.mutate()
			.model(value(runtimeOptions.model, this.model))
			.n(value(runtimeOptions.n, this.n))
			.width(value(runtimeOptions.width, this.width))
			.height(value(runtimeOptions.height, this.height))
			.resolution(value(runtimeOptions.resolution, this.resolution))
			.aspectRatio(value(runtimeOptions.aspectRatio, this.aspectRatio))
			.quality(value(runtimeOptions.quality, this.quality))
			.outputFormat(value(runtimeOptions.outputFormat, this.outputFormat))
			.background(value(runtimeOptions.background, this.background))
			.outputCompression(value(runtimeOptions.outputCompression, this.outputCompression))
			.seed(value(runtimeOptions.seed, this.seed))
			.inputReferences(value(runtimeOptions.inputReferences, this.inputReferences))
			.provider(value(runtimeOptions.provider, this.provider))
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
	public @Nullable Integer getN() {
		return this.n;
	}

	@Override
	public @Nullable Integer getWidth() {
		return this.width;
	}

	@Override
	public @Nullable Integer getHeight() {
		return this.height;
	}

	/**
	 * Always {@code b64_json}: OpenRouter's Image API returns base64-encoded bytes.
	 */
	@Override
	public String getResponseFormat() {
		return "b64_json";
	}

	/**
	 * Always {@code null}: OpenRouter's Image API has no portable style parameter; use
	 * {@link #getQuality() quality} or the {@code options.<provider-slug>} settings of
	 * {@link #getProvider() provider} instead.
	 */
	@Override
	public @Nullable String getStyle() {
		return null;
	}

	public @Nullable String getResolution() {
		return this.resolution;
	}

	public @Nullable String getAspectRatio() {
		return this.aspectRatio;
	}

	public @Nullable String getQuality() {
		return this.quality;
	}

	public @Nullable String getOutputFormat() {
		return this.outputFormat;
	}

	public @Nullable String getBackground() {
		return this.background;
	}

	public @Nullable Integer getOutputCompression() {
		return this.outputCompression;
	}

	public @Nullable Integer getSeed() {
		return this.seed;
	}

	public @Nullable List<String> getInputReferences() {
		return this.inputReferences;
	}

	public @Nullable Map<String, @Nullable Object> getProvider() {
		return this.provider;
	}

	/**
	 * Return the OpenRouter {@code provider} object.
	 * @return the provider object, or {@code null}
	 * @deprecated since 0.2.0 for removal in 0.3.0; use {@link #getProvider()}
	 */
	@Deprecated(since = "0.2.0", forRemoval = true)
	public @Nullable Map<String, @Nullable Object> getProviderOptions() {
		return getProvider();
	}

	public static final class Builder {

		private final OpenRouterImageOptions options;

		private Builder() {
			this.options = new OpenRouterImageOptions();
		}

		private Builder(OpenRouterImageOptions source) {
			this();
			this.options.model = source.model;
			this.options.n = source.n;
			this.options.width = source.width;
			this.options.height = source.height;
			this.options.resolution = source.resolution;
			this.options.aspectRatio = source.aspectRatio;
			this.options.quality = source.quality;
			this.options.outputFormat = source.outputFormat;
			this.options.background = source.background;
			this.options.outputCompression = source.outputCompression;
			this.options.seed = source.seed;
			this.options.inputReferences = OptionSnapshots.list(source.inputReferences);
			this.options.provider = OptionSnapshots.map(source.provider);
		}

		public Builder model(@Nullable String model) {
			this.options.model = model;
			return this;
		}

		public Builder n(@Nullable Integer n) {
			this.options.n = n;
			return this;
		}

		public Builder width(@Nullable Integer width) {
			this.options.width = width;
			return this;
		}

		public Builder height(@Nullable Integer height) {
			this.options.height = height;
			return this;
		}

		public Builder resolution(@Nullable String resolution) {
			this.options.resolution = resolution;
			return this;
		}

		public Builder aspectRatio(@Nullable String aspectRatio) {
			this.options.aspectRatio = aspectRatio;
			return this;
		}

		public Builder quality(@Nullable String quality) {
			this.options.quality = quality;
			return this;
		}

		public Builder outputFormat(@Nullable String outputFormat) {
			this.options.outputFormat = outputFormat;
			return this;
		}

		public Builder background(@Nullable String background) {
			this.options.background = background;
			return this;
		}

		public Builder outputCompression(@Nullable Integer outputCompression) {
			this.options.outputCompression = outputCompression;
			return this;
		}

		public Builder seed(@Nullable Integer seed) {
			this.options.seed = seed;
			return this;
		}

		public Builder inputReferences(@Nullable List<String> inputReferences) {
			this.options.inputReferences = OptionSnapshots
				.list(OptionChecks.noNullElements("inputReferences", inputReferences));
			return this;
		}

		/**
		 * Set the OpenRouter {@code provider} object, sent unchanged. Unlike the typed
		 * {@code provider} of chat and embedding options, this is a raw map with wire
		 * field names. Put settings for one upstream provider under
		 * {@code options.<provider-slug>}.
		 * @param provider the {@code provider} object, or {@code null}
		 * @return this builder
		 */
		public Builder provider(@Nullable Map<String, @Nullable Object> provider) {
			this.options.provider = OptionSnapshots.map(provider);
			return this;
		}

		/**
		 * Set the OpenRouter {@code provider} object.
		 * @param providerOptions the {@code provider} object, or {@code null}
		 * @return this builder
		 * @deprecated since 0.2.0 for removal in 0.3.0; use {@link #provider(Map)}
		 */
		@Deprecated(since = "0.2.0", forRemoval = true)
		public Builder providerOptions(@Nullable Map<String, @Nullable Object> providerOptions) {
			return provider(providerOptions);
		}

		public OpenRouterImageOptions build() {
			// Detached snapshot so later builder mutation cannot leak into built options.
			return new Builder(this.options).options;
		}

	}

}
