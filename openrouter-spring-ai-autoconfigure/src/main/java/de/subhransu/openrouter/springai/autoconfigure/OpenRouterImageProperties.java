package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(OpenRouterImageProperties.CONFIG_PREFIX)
public class OpenRouterImageProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.image";

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
			.providerOptions(this.providerOptions)
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

	public @Nullable Map<String, @Nullable Object> getProviderOptions() {
		return this.providerOptions;
	}

	public void setProviderOptions(@Nullable Map<String, @Nullable Object> providerOptions) {
		this.providerOptions = providerOptions;
	}

}
