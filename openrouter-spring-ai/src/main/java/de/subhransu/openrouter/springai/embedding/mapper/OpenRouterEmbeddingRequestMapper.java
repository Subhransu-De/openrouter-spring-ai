package de.subhransu.openrouter.springai.embedding.mapper;

import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

public final class OpenRouterEmbeddingRequestMapper {

	public EmbeddingsRequest map(List<String> inputs, OpenRouterEmbeddingOptions options) {
		// The response decoder only understands JSON float arrays; a base64
		// encoding_format would deserialize into garbage, so reject it up front rather
		// than fail deep inside Jackson.
		if (StringUtils.hasText(options.getEncodingFormat()) && !"float".equals(options.getEncodingFormat())) {
			throw new IllegalArgumentException("Unsupported encoding_format '" + options.getEncodingFormat()
					+ "': only 'float' embeddings can be decoded");
		}
		return new EmbeddingsRequest(options.getModel(), inputs, options.getEncodingFormat(), options.getDimensions(),
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
