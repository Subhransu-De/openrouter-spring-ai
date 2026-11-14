package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.DeprecatedConfigurationProperty;

@ConfigurationProperties(OpenRouterImageProperties.CONFIG_PREFIX)
public class OpenRouterImageProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.image";

	private static final Log logger = LogFactory.getLog(OpenRouterImageProperties.class);

	/**
	 * Image model id in vendor/model form.
	 */
	private @Nullable String model;

	/**
	 * Maximum number of images to generate. Providers may return fewer.
	 */
	private @Nullable Integer n;

	/**
	 * Image width in pixels. Set together with height.
	 */
	private @Nullable Integer width;

	/**
	 * Image height in pixels. Set together with width.
	 */
	private @Nullable Integer height;

	/**
	 * Provider resolution preset.
	 */
	private @Nullable String resolution;

	/**
	 * Aspect ratio, such as 16:9.
	 */
	private @Nullable String aspectRatio;

	/**
	 * Image quality level, such as high.
	 */
	private @Nullable String quality;

	/**
	 * Image encoding, such as png or webp. Does not choose URL or base64 delivery.
	 */
	private @Nullable String outputFormat;

	/**
	 * Background handling, such as transparent.
	 */
	private @Nullable String background;

	/**
	 * Compression level for lossy output formats.
	 */
	private @Nullable Integer outputCompression;

	/**
	 * Seed for repeatable generation, where the upstream provider supports it.
	 */
	private @Nullable Integer seed;

	/**
	 * Comma-separated list of reference images, as HTTP(S) or base64 data URLs, for
	 * image-to-image generation.
	 */
	private @Nullable List<String> inputReferences;

	/**
	 * OpenRouter provider object as a raw map with wire field names, sent unchanged.
	 * Settings for one upstream provider go under options, keyed by the provider slug. A
	 * list binds as a map keyed by index, and properties files and environment variables
	 * give booleans and numbers as strings, so set such values in Java.
	 */
	private @Nullable Map<String, @Nullable Object> providerExtraBody;

	/**
	 * Old name of provider-extra-body, still bound until 1.0.0.
	 */
	private @Nullable Map<String, @Nullable Object> providerOptions;

	public OpenRouterImageOptions toOptions() {
		return OpenRouterImageOptions.builder()
			.model(this.model)
			.n(this.n)
			.width(this.width)
			.height(this.height)
			.resolution(this.resolution)
			.aspectRatio(this.aspectRatio)
			.quality(this.quality)
			.outputFormat(this.outputFormat)
			.background(this.background)
			.outputCompression(this.outputCompression)
			.seed(this.seed)
			.inputReferences(this.inputReferences)
			.providerExtraBody(providerExtraBody())
			.build();
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable Integer getN() {
		return this.n;
	}

	public void setN(@Nullable Integer n) {
		this.n = n;
	}

	public @Nullable Integer getWidth() {
		return this.width;
	}

	public void setWidth(@Nullable Integer width) {
		this.width = width;
	}

	public @Nullable Integer getHeight() {
		return this.height;
	}

	public void setHeight(@Nullable Integer height) {
		this.height = height;
	}

	public @Nullable String getResolution() {
		return this.resolution;
	}

	public void setResolution(@Nullable String resolution) {
		this.resolution = resolution;
	}

	public @Nullable String getAspectRatio() {
		return this.aspectRatio;
	}

	public void setAspectRatio(@Nullable String aspectRatio) {
		this.aspectRatio = aspectRatio;
	}

	public @Nullable String getQuality() {
		return this.quality;
	}

	public void setQuality(@Nullable String quality) {
		this.quality = quality;
	}

	public @Nullable String getOutputFormat() {
		return this.outputFormat;
	}

	public void setOutputFormat(@Nullable String outputFormat) {
		this.outputFormat = outputFormat;
	}

	public @Nullable String getBackground() {
		return this.background;
	}

	public void setBackground(@Nullable String background) {
		this.background = background;
	}

	public @Nullable Integer getOutputCompression() {
		return this.outputCompression;
	}

	public void setOutputCompression(@Nullable Integer outputCompression) {
		this.outputCompression = outputCompression;
	}

	public @Nullable Integer getSeed() {
		return this.seed;
	}

	public void setSeed(@Nullable Integer seed) {
		this.seed = seed;
	}

	public @Nullable List<String> getInputReferences() {
		return this.inputReferences;
	}

	public void setInputReferences(@Nullable List<String> inputReferences) {
		this.inputReferences = inputReferences;
	}

	public @Nullable Map<String, @Nullable Object> getProviderExtraBody() {
		return this.providerExtraBody;
	}

	public void setProviderExtraBody(@Nullable Map<String, @Nullable Object> providerExtraBody) {
		this.providerExtraBody = providerExtraBody;
	}

	/**
	 * Return the value of the old {@code provider-options} key.
	 * @return the provider object, or {@code null}
	 * @deprecated since 0.2.0 for removal in 1.0.0; use {@link #getProviderExtraBody()}
	 */
	@Deprecated(since = "0.2.0", forRemoval = true)
	@DeprecatedConfigurationProperty(replacement = CONFIG_PREFIX + ".provider-extra-body", since = "0.2.0")
	public @Nullable Map<String, @Nullable Object> getProviderOptions() {
		return this.providerOptions;
	}

	/**
	 * Set the value of the old {@code provider-options} key.
	 * @param providerOptions the provider object
	 * @deprecated since 0.2.0 for removal in 1.0.0; use
	 * {@link #setProviderExtraBody(Map)}
	 */
	@Deprecated(since = "0.2.0", forRemoval = true)
	public void setProviderOptions(@Nullable Map<String, @Nullable Object> providerOptions) {
		this.providerOptions = providerOptions;
	}

	private @Nullable Map<String, @Nullable Object> providerExtraBody() {
		if (this.providerOptions == null) {
			return this.providerExtraBody;
		}
		if (this.providerExtraBody != null) {
			throw new IllegalStateException("Set only " + CONFIG_PREFIX + ".provider-extra-body; " + CONFIG_PREFIX
					+ ".provider-options is its old name");
		}
		logger.warn(CONFIG_PREFIX + ".provider-options is deprecated; rename it to " + CONFIG_PREFIX
				+ ".provider-extra-body");
		return this.providerOptions;
	}

}
