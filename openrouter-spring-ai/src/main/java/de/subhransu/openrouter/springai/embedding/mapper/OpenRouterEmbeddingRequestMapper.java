package de.subhransu.openrouter.springai.embedding.mapper;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.EmbeddingInput;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.mapper.MediaUrlMapper;
import de.subhransu.openrouter.springai.chat.mapper.TraceMapper;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public final class OpenRouterEmbeddingRequestMapper {

	public EmbeddingsRequest map(List<String> inputs, OpenRouterEmbeddingOptions options) {
		return request(inputs, options);
	}

	/**
	 * Maps each document to one multimodal input entry: a text document to a {@code text}
	 * part, a media document to an {@code image_url} part.
	 * @param documents the documents, each embedded separately
	 * @param textContent the text to embed for a text document
	 * @param options the request options
	 * @return the request
	 * @throws IllegalArgumentException if a document carries non-image media
	 */
	public EmbeddingsRequest mapDocuments(List<Document> documents, Function<Document, @Nullable String> textContent,
			OpenRouterEmbeddingOptions options) {
		Assert.noNullElements(documents, "Documents must not contain null elements");
		List<EmbeddingInput> inputs = new ArrayList<>(documents.size());
		for (Document document : documents) {
			Media media = document.getMedia();
			ContentPart part;
			if (media != null) {
				part = ContentPart.image(MediaUrlMapper.imageUrl(media));
			}
			else {
				String text = textContent.apply(document);
				Assert.notNull(text, "Embedding document content must not be null");
				part = ContentPart.text(text);
			}
			inputs.add(new EmbeddingInput(List.of(part)));
		}
		return request(inputs, options);
	}

	private EmbeddingsRequest request(Object input, OpenRouterEmbeddingOptions options) {
		// OpenRouter requires a model for embeddings and has no account default for them,
		// unlike chat. Fail before the round trip, as the image mapper does.
		String model = options.getModel();
		Assert.hasText(model, "Embedding model must not be empty");
		// The response decoder only understands JSON float arrays; a base64
		// encoding_format would deserialize into garbage, so reject it up front rather
		// than fail deep inside Jackson.
		if (StringUtils.hasText(options.getEncodingFormat()) && !"float".equals(options.getEncodingFormat())) {
			throw new IllegalArgumentException("Unsupported encoding_format '" + options.getEncodingFormat()
					+ "': only 'float' embeddings can be decoded");
		}
		return new EmbeddingsRequest(model, input, options.getEncodingFormat(), options.getDimensions(),
				options.getUser(), mapProvider(options.getProvider()), options.getSessionId(),
				TraceMapper.map(options.getTrace()));
	}

	private @Nullable ProviderPreferences mapProvider(@Nullable OpenRouterProviderPreferences provider) {
		if (provider == null) {
			return null;
		}
		return new ProviderPreferences(provider.allowFallbacks(), provider.requireParameters(),
				provider.dataCollection(), provider.order(), provider.ignore(), provider.quantizations(),
				provider.sort());
	}

}
