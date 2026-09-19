package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(OpenRouterEmbeddingProperties.CONFIG_PREFIX)
public class OpenRouterEmbeddingProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.embedding";

	private MetadataMode metadataMode = MetadataMode.NONE;

	private @Nullable String model;

	private @Nullable Integer dimensions;

	private @Nullable String encodingFormat;

	private @Nullable String user;

	private @Nullable OpenRouterProviderPreferences provider;

	public OpenRouterEmbeddingOptions toOptions() {
		return OpenRouterEmbeddingOptions.builder()
			.model(this.model)
			.dimensions(this.dimensions)
			.encodingFormat(this.encodingFormat)
			.user(this.user)
			.provider(this.provider)
			.build();
	}

	public MetadataMode getMetadataMode() {
		return this.metadataMode;
	}

	public void setMetadataMode(MetadataMode metadataMode) {
		this.metadataMode = metadataMode;
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

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

}
