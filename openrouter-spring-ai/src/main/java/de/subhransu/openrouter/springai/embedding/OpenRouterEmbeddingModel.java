package de.subhransu.openrouter.springai.embedding;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingRequestMapper;
import de.subhransu.openrouter.springai.embedding.mapper.OpenRouterEmbeddingResponseMapper;
import de.subhransu.openrouter.springai.internal.Retries;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.observation.DefaultEmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationContext;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationDocumentation;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;

/**
 * OpenRouter {@link EmbeddingModel} backed by {@code POST /embeddings}.
 *
 * @author Subhransu De
 */
public class OpenRouterEmbeddingModel implements EmbeddingModel {

	private static final EmbeddingModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultEmbeddingModelObservationConvention();

	private static final String PROVIDER_NAME = "openrouter";

	private final OpenRouterApi openRouterApi;

	private final OpenRouterEmbeddingOptions defaultOptions;

	private final MetadataMode metadataMode;

	private int discoveredDimensions;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private EmbeddingModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterEmbeddingRequestMapper requestMapper = new OpenRouterEmbeddingRequestMapper();

	private final OpenRouterEmbeddingResponseMapper responseMapper = new OpenRouterEmbeddingResponseMapper();

	private OpenRouterEmbeddingModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.copy()
				: OpenRouterEmbeddingOptions.builder().build();
		this.metadataMode = builder.metadataMode;
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		OpenRouterEmbeddingOptions options = buildRequestOptions(request.getOptions());
		EmbeddingModelObservationContext observationContext = EmbeddingModelObservationContext.builder()
			.embeddingRequest(new EmbeddingRequest(request.getInstructions(), options))
			.provider(PROVIDER_NAME)
			.build();
		return EmbeddingModelObservationDocumentation.EMBEDDING_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				EmbeddingsResponse embeddingsResponse = Retries.invoke(this.retryTemplate, () -> this.openRouterApi
					.embeddings(this.requestMapper.map(request.getInstructions(), options)));
				EmbeddingResponse response = this.responseMapper.map(embeddingsResponse,
						request.getInstructions().size(), options.getDimensions());
				observationContext.setResponse(response);
				return response;
			});
	}

	@Override
	public float[] embed(Document document) {
		Assert.notNull(document, "Document must not be null");
		String content = getEmbeddingContent(document);
		Assert.notNull(content, "Embedding document content must not be null");
		return embed(content);
	}

	@Override
	public @Nullable String getEmbeddingContent(Document document) {
		Assert.notNull(document, "Document must not be null");
		return this.metadataMode == MetadataMode.NONE ? document.getText()
				: document.getFormattedContent(this.metadataMode);
	}

	/**
	 * Returns configured dimensions, or discovers them once for this model's fixed
	 * default options. Concurrent callers share successful discovery; failures can be
	 * retried. Per-request options do not change this model's default dimensions.
	 */
	@Override
	public synchronized int dimensions() {
		Integer configuredDimensions = this.defaultOptions.getDimensions();
		if (configuredDimensions != null) {
			Assert.isTrue(configuredDimensions > 0, "Dimensions must be positive");
			return configuredDimensions;
		}
		if (this.discoveredDimensions == 0) {
			int dimensions = EmbeddingModel.super.dimensions();
			Assert.state(dimensions > 0, "Discovered dimensions must be positive");
			this.discoveredDimensions = dimensions;
		}
		return this.discoveredDimensions;
	}

	public void setObservationConvention(EmbeddingModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private OpenRouterEmbeddingOptions buildRequestOptions(@Nullable EmbeddingOptions runtimeOptions) {
		return runtimeOptions == null ? this.defaultOptions.copy()
				: this.defaultOptions.merge(OpenRouterEmbeddingOptions.fromOptions(runtimeOptions));
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterEmbeddingOptions defaultOptions;

		private MetadataMode metadataMode = MetadataMode.NONE;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.openRouterApi = openRouterApi;
			return this;
		}

		public Builder defaultOptions(@Nullable OpenRouterEmbeddingOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/**
		 * Selects document metadata formatting. Defaults to {@link MetadataMode#NONE},
		 * which preserves raw document text without applying a content formatter.
		 * @param metadataMode the metadata policy for single and batched documents
		 * @return this builder
		 */
		public Builder metadataMode(MetadataMode metadataMode) {
			Assert.notNull(metadataMode, "MetadataMode must not be null");
			this.metadataMode = metadataMode;
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

		public OpenRouterEmbeddingModel build() {
			return new OpenRouterEmbeddingModel(this);
		}

	}

}
