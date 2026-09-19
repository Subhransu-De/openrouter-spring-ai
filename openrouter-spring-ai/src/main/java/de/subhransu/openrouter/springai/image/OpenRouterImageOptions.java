package de.subhransu.openrouter.springai.image;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.List;
import java.util.Map;
import org.springframework.ai.image.ImageOptions;

/**
 * Options for OpenRouter's unified Image API ({@code POST /images}). Exposes the
 * normalized OpenRouter fields: {@code n}, {@code size} (via width/height),
 * {@code resolution}, {@code aspect_ratio}, {@code quality}, {@code output_format},
 * {@code background}, {@code output_compression}, {@code seed}, {@code input_references}
 * (reference images for image-to-image work) and provider-specific passthrough options.
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

	private @Nullable Map<String, @Nullable Object> providerOptions;

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder(this);
	}

	public OpenRouterImageOptions copy() {
		return this.mutate().build();
	}

	public static @Nullable OpenRouterImageOptions fromOptions(@Nullable ImageOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterImageOptions openRouterOptions) {
			return openRouterOptions.copy();
		}
		if (options.getResponseFormat() != null && !"b64_json".equals(options.getResponseFormat())) {
			throw new IllegalArgumentException(
					"OpenRouter image responseFormat must be null or 'b64_json'; URL output cannot be requested");
		}
		if (options.getStyle() != null) {
			throw new IllegalArgumentException(
					"OpenRouter does not support portable image style; leave style unset and use providerOptions for provider-specific settings");
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
			return this.copy();
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
			.providerOptions(value(runtimeOptions.providerOptions, this.providerOptions))
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
	 * {@link #getQuality() quality} or provider passthrough options instead.
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

	public @Nullable Map<String, @Nullable Object> getProviderOptions() {
		return this.providerOptions;
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
			this.options.providerOptions = OptionSnapshots.map(source.providerOptions);
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
			this.options.inputReferences = OptionSnapshots.list(inputReferences);
			return this;
		}

		public Builder providerOptions(@Nullable Map<String, @Nullable Object> providerOptions) {
			this.options.providerOptions = OptionSnapshots.map(providerOptions);
			return this;
		}

		public OpenRouterImageOptions build() {
			// Detached snapshot so later builder mutation cannot leak into built options.
			return new Builder(this.options).options;
		}

	}

}
