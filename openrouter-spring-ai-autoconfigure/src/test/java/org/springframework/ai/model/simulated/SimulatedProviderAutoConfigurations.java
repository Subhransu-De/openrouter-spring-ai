package org.springframework.ai.model.simulated;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Test stand-ins for official Spring AI provider embedding and image auto-configurations,
 * shaped and named like the chat stand-in in
 * {@link SimulatedProviderChatAutoConfiguration}.
 */
public final class SimulatedProviderAutoConfigurations {

	private SimulatedProviderAutoConfigurations() {
	}

	/**
	 * Embedding auto-configuration shaped like {@code OpenAiEmbeddingAutoConfiguration}.
	 */
	@AutoConfiguration
	@ConditionalOnProperty(name = SpringAIModelProperties.EMBEDDING_MODEL, havingValue = "simulated",
			matchIfMissing = true)
	public static final class Embedding {

		@Bean
		@ConditionalOnMissingBean
		SimulatedEmbeddingModel simulatedEmbeddingModel() {
			return new SimulatedEmbeddingModel();
		}

	}

	/**
	 * Text embedding auto-configuration shaped like
	 * {@code GoogleGenAiTextEmbeddingAutoConfiguration}, which selects on
	 * {@code spring.ai.model.embedding.text} instead of
	 * {@code spring.ai.model.embedding}.
	 */
	@AutoConfiguration
	@ConditionalOnProperty(name = SpringAIModelProperties.TEXT_EMBEDDING_MODEL, havingValue = "simulated",
			matchIfMissing = true)
	public static final class TextEmbedding {

		@Bean
		@ConditionalOnMissingBean
		SimulatedTextEmbeddingModel simulatedTextEmbeddingModel() {
			return new SimulatedTextEmbeddingModel();
		}

	}

	/**
	 * Image auto-configuration shaped like {@code OpenAiImageAutoConfiguration}.
	 */
	@AutoConfiguration
	@ConditionalOnProperty(name = SpringAIModelProperties.IMAGE_MODEL, havingValue = "simulated", matchIfMissing = true)
	public static final class Image {

		@Bean
		@ConditionalOnMissingBean
		SimulatedImageModel simulatedImageModel() {
			return new SimulatedImageModel();
		}

	}

	/**
	 * Embedding model registered by the simulated provider.
	 */
	public static final class SimulatedEmbeddingModel implements EmbeddingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			throw new UnsupportedOperationException("not called");
		}

		@Override
		public float[] embed(Document document) {
			throw new UnsupportedOperationException("not called");
		}

	}

	/**
	 * Text embedding model registered by the simulated provider.
	 */
	public static final class SimulatedTextEmbeddingModel implements EmbeddingModel {

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			throw new UnsupportedOperationException("not called");
		}

		@Override
		public float[] embed(Document document) {
			throw new UnsupportedOperationException("not called");
		}

	}

	/**
	 * Image model registered by the simulated provider.
	 */
	public static final class SimulatedImageModel implements ImageModel {

		@Override
		public ImageResponse call(ImagePrompt request) {
			throw new UnsupportedOperationException("not called");
		}

	}

}
