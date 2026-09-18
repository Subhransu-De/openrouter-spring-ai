package de.subhransu.openrouter.springai.embedding;

import org.jspecify.annotations.Nullable;
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

	private @Nullable String model;

	private @Nullable Integer dimensions;

	private @Nullable String encodingFormat;

	private @Nullable String user;

	private @Nullable OpenRouterProviderPreferences provider;

	public static Builder builder() {
		return new Builder();
	}

	public Builder mutate() {
		return new Builder(this);
	}

	public OpenRouterEmbeddingOptions copy() {
		return this.mutate().build();
	}

	public static @Nullable OpenRouterEmbeddingOptions fromOptions(@Nullable EmbeddingOptions options) {
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

	public OpenRouterEmbeddingOptions merge(@Nullable OpenRouterEmbeddingOptions runtimeOptions) {
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

	private static <T> @Nullable T value(@Nullable T runtimeValue, @Nullable T defaultValue) {
		return runtimeValue != null ? runtimeValue : defaultValue;
	}

	@Override
	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	@Override
	public @Nullable Integer getDimensions() {
		return this.dimensions;
	}

	public void setDimensions(@Nullable Integer dimensions) {
		this.dimensions = dimensions;
	}

	public @Nullable String getEncodingFormat() {
		return this.encodingFormat;
	}

	public void setEncodingFormat(@Nullable String encodingFormat) {
		this.encodingFormat = encodingFormat;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public void setUser(@Nullable String user) {
		this.user = user;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(@Nullable OpenRouterProviderPreferences provider) {
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

		public Builder model(@Nullable String model) {
			this.options.setModel(model);
			return this;
		}

		public Builder dimensions(@Nullable Integer dimensions) {
			this.options.setDimensions(dimensions);
			return this;
		}

		public Builder encodingFormat(@Nullable String encodingFormat) {
			this.options.setEncodingFormat(encodingFormat);
			return this;
		}

		public Builder user(@Nullable String user) {
			this.options.setUser(user);
			return this;
		}

		public Builder provider(@Nullable OpenRouterProviderPreferences provider) {
			this.options.setProvider(provider);
			return this;
		}

		public OpenRouterEmbeddingOptions build() {
			return new Builder(this.options).options;
		}

	}

}
