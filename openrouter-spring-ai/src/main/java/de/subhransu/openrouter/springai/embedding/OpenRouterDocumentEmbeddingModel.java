package de.subhransu.openrouter.springai.embedding;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.DocumentEmbeddingModel;
import org.springframework.ai.embedding.DocumentEmbeddingRequest;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResultMetadata;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;

/**
 * OpenRouter {@link DocumentEmbeddingModel} for text and image documents, backed by
 * {@code POST /embeddings}. Each document becomes one multimodal input entry and one
 * embedding: a text document a {@code text} part, an image document an {@code image_url}
 * part. Other media types are rejected before the request.
 * <p>
 * It uses the options, metadata mode, retries, observations and dimension discovery of
 * {@link OpenRouterEmbeddingModel}. Only some OpenRouter models accept images; others
 * answer with a provider error.
 *
 * @author Subhransu De
 */
public class OpenRouterDocumentEmbeddingModel implements DocumentEmbeddingModel {

	private final OpenRouterEmbeddingModel embeddingModel;

	private OpenRouterDocumentEmbeddingModel(OpenRouterEmbeddingModel embeddingModel) {
		this.embeddingModel = embeddingModel;
	}

	@Override
	public EmbeddingResponse call(DocumentEmbeddingRequest request) {
		Assert.notNull(request, "DocumentEmbeddingRequest must not be null");
		List<Document> documents = request.getInstructions();
		EmbeddingResponse response = this.embeddingModel.callDocuments(documents, request.getOptions());
		List<Embedding> embeddings = new ArrayList<>(documents.size());
		for (Embedding embedding : response.getResults()) {
			Document document = documents.get(embedding.getIndex());
			embeddings.add(new Embedding(embedding.getOutput(), embedding.getIndex(), resultMetadata(document)));
		}
		return new EmbeddingResponse(embeddings, response.getMetadata());
	}

	/**
	 * Returns configured dimensions, or discovers them once with a text request, as
	 * {@link OpenRouterEmbeddingModel#dimensions()} does.
	 */
	@Override
	public int dimensions() {
		return this.embeddingModel.dimensions();
	}

	/**
	 * Returns a copy of this model's default options, the starting point for per-request
	 * options built with {@code mutate()}.
	 * @return the default options
	 */
	public OpenRouterEmbeddingOptions getOptions() {
		return this.embeddingModel.getOptions();
	}

	public void setObservationConvention(EmbeddingModelObservationConvention observationConvention) {
		this.embeddingModel.setObservationConvention(observationConvention);
	}

	private static EmbeddingResultMetadata resultMetadata(Document document) {
		Media media = document.getMedia();
		if (media == null) {
			return new EmbeddingResultMetadata(document.getId(), EmbeddingResultMetadata.ModalityType.TEXT,
					MimeTypeUtils.TEXT_PLAIN, document.getText());
		}
		return new EmbeddingResultMetadata(document.getId(), EmbeddingResultMetadata.ModalityType.IMAGE,
				media.getMimeType(), media.getData());
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private final OpenRouterEmbeddingModel.Builder delegate = OpenRouterEmbeddingModel.builder();

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.delegate.openRouterApi(openRouterApi);
			return this;
		}

		/**
		 * Set the default options of the model. Options in a request override them.
		 * @param options the default options, or {@code null} for the built-in defaults
		 * @return this builder
		 */
		public Builder options(@Nullable OpenRouterEmbeddingOptions options) {
			this.delegate.options(options);
			return this;
		}

		/**
		 * Selects how text documents include their metadata. Defaults to
		 * {@link MetadataMode#NONE}. Image documents embed only the image.
		 * @param metadataMode the metadata policy for text documents
		 * @return this builder
		 */
		public Builder metadataMode(MetadataMode metadataMode) {
			this.delegate.metadataMode(metadataMode);
			return this;
		}

		public Builder retryTemplate(@Nullable RetryTemplate retryTemplate) {
			this.delegate.retryTemplate(retryTemplate);
			return this;
		}

		public Builder observationRegistry(@Nullable ObservationRegistry observationRegistry) {
			this.delegate.observationRegistry(observationRegistry);
			return this;
		}

		public OpenRouterDocumentEmbeddingModel build() {
			return new OpenRouterDocumentEmbeddingModel(this.delegate.build());
		}

	}

}
