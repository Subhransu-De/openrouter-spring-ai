package de.subhransu.openrouter.springai.embedding;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import org.springframework.ai.embedding.EmbeddingOptions;

/**
 * Options for the OpenRouter embeddings endpoint. Mirrors the OpenRouter request fields:
 * {@code model}, {@code dimensions}, {@code encoding_format}, {@code user} and the
 * OpenRouter-specific {@code provider} routing preferences.
 *
 * @author Subhransu De
 */
public class OpenRouterEmbeddingOptions implements EmbeddingOptions {

	private String model;

	private Integer dimensions;

	private String encodingFormat;

	private String user;

	private OpenRouterProviderPreferences provider;

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder(this);
	}

	public OpenRouterEmbeddingOptions copy() {
		return this.mutate().build();
	}

	public static OpenRouterEmbeddingOptions fromOptions(EmbeddingOptions options) {
		if (options == null) {
			return null;
		}
		if (options instanceof OpenRouterEmbeddingOptions openRouterOptions) {
			return openRouterOptions.copy();
		}
		return OpenRouterEmbeddingOptions.builder()
			.model(options.getModel())
			.dimensions(options.getDimensions())
			.build();
	}

	public OpenRouterEmbeddingOptions merge(OpenRouterEmbeddingOptions runtimeOptions) {
		if (runtimeOptions == null) {
			return this.copy();
		}
		return this.mutate()
			.model(value(runtimeOptions.model, this.model))
			.dimensions(value(runtimeOptions.dimensions, this.dimensions))
			.encodingFormat(value(runtimeOptions.encodingFormat, this.encodingFormat))
			.user(value(runtimeOptions.user, this.user))
			.provider(value(runtimeOptions.provider, this.provider))
			.build();
	}

	private static <T> T value(T runtimeValue, T defaultValue) {
		return runtimeValue != null ? runtimeValue : defaultValue;
	}

	@Override
	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@Override
	public Integer getDimensions() {
		return this.dimensions;
	}

	public void setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
	}

	public String getEncodingFormat() {
		return this.encodingFormat;
	}

	public void setEncodingFormat(String encodingFormat) {
		this.encodingFormat = encodingFormat;
	}

	public String getUser() {
		return this.user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(OpenRouterProviderPreferences provider) {
		this.provider = provider;
	}

	public static final class Builder {

		private final OpenRouterEmbeddingOptions options;

		private Builder() {
			this.options = new OpenRouterEmbeddingOptions();
		}

		private Builder(OpenRouterEmbeddingOptions source) {
			this();
			this.options.setModel(source.getModel());
			this.options.setDimensions(source.getDimensions());
			this.options.setEncodingFormat(source.getEncodingFormat());
			this.options.setUser(source.getUser());
			this.options.setProvider(source.getProvider());
		}

		public Builder model(String model) {
			this.options.setModel(model);
			return this;
		}

		public Builder dimensions(Integer dimensions) {
			this.options.setDimensions(dimensions);
			return this;
		}

		public Builder encodingFormat(String encodingFormat) {
			this.options.setEncodingFormat(encodingFormat);
			return this;
		}

		public Builder user(String user) {
			this.options.setUser(user);
			return this;
		}

		public Builder provider(OpenRouterProviderPreferences provider) {
			this.options.setProvider(provider);
			return this;
		}

		public OpenRouterEmbeddingOptions build() {
			return new Builder(this.options).options;
		}

	}

}
