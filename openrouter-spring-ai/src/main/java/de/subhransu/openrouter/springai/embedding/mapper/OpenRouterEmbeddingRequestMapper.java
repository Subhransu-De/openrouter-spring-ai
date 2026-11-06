package de.subhransu.openrouter.springai.embedding.mapper;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

public final class OpenRouterEmbeddingRequestMapper {

	public EmbeddingsRequest map(List<String> inputs, OpenRouterEmbeddingOptions options) {
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
		return new EmbeddingsRequest(model, inputs, options.getEncodingFormat(), options.getDimensions(),
				options.getUser(), mapProvider(options.getProvider()));
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
