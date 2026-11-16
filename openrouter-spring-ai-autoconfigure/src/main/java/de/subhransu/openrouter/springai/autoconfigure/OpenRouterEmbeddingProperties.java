package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(OpenRouterEmbeddingProperties.CONFIG_PREFIX)
public class OpenRouterEmbeddingProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.embedding";

	/**
	 * Document metadata to include in the embedded text.
	 */
	private MetadataMode metadataMode = MetadataMode.NONE;

	/**
	 * Embedding model id in vendor/model form.
	 */
	private @Nullable String model;

	/**
	 * Number of output dimensions, for models that support shortened embeddings.
	 */
	private @Nullable Integer dimensions;

	/**
	 * Embedding encoding format. Only float embeddings are decoded.
	 */
	private @Nullable String encodingFormat;

	/**
	 * End-user identifier forwarded to OpenRouter.
	 */
	private @Nullable String user;

	/**
	 * Default session id for observability grouping, sent as session_id. At most 256
	 * characters.
	 */
	private @Nullable String sessionId;

	/**
	 * Observability metadata sent as trace. A request trace replaces it as a whole.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterTrace trace;

	/**
	 * Provider routing preferences.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterProviderPreferences provider;

	public OpenRouterEmbeddingOptions toOptions() {
		return OpenRouterEmbeddingOptions.builder()
			.model(this.model)
			.dimensions(this.dimensions)
			.encodingFormat(this.encodingFormat)
			.user(this.user)
			.sessionId(this.sessionId)
			.trace(this.trace)
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

	public @Nullable String getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(@Nullable String sessionId) {
		this.sessionId = sessionId;
	}

	public @Nullable OpenRouterTrace getTrace() {
		return this.trace;
	}

	public void setTrace(@Nullable OpenRouterTrace trace) {
		this.trace = trace;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(@Nullable OpenRouterProviderPreferences provider) {
		this.provider = provider;
	}

}
