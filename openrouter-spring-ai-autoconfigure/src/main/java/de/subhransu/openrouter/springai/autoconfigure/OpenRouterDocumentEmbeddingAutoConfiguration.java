package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.embedding.OpenRouterDocumentEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.DocumentEmbeddingModel;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.retry.RetryTemplate;

/**
 * Creates the OpenRouter {@link DocumentEmbeddingModel} when
 * {@code spring.ai.model.embedding.multimodal} names OpenRouter. It is opt-in, like the
 * audio models, and shares {@code spring.ai.openrouter.embedding.*} with the text model,
 * so text queries and image documents use the same model and vector space.
 *
 * @author Subhransu De
 */
@AutoConfiguration(after = { OpenRouterApiAutoConfiguration.class, SpringAiRetryAutoConfiguration.class })
@ConditionalOnClass({ OpenRouterDocumentEmbeddingModel.class, DocumentEmbeddingModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.MULTI_MODAL_EMBEDDING_MODEL,
		havingValue = OpenRouterIdentifiers.PROVIDER_ID)
@EnableConfigurationProperties(OpenRouterEmbeddingProperties.class)
public final class OpenRouterDocumentEmbeddingAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(DocumentEmbeddingModel.class)
	OpenRouterDocumentEmbeddingModel openRouterDocumentEmbeddingModel(OpenRouterApi openRouterApi,
			OpenRouterEmbeddingProperties embeddingProperties, ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<EmbeddingModelObservationConvention> observationConventionProvider) {
		OpenRouterDocumentEmbeddingModel embeddingModel = OpenRouterDocumentEmbeddingModel.builder()
			.openRouterApi(openRouterApi)
			.options(embeddingProperties.toOptions())
			.metadataMode(embeddingProperties.getMetadataMode())
			.retryTemplate(retryTemplateProvider.getIfUnique())
			.observationRegistry(observationRegistryProvider.getIfUnique())
			.build();
		observationConventionProvider.ifAvailable(embeddingModel::setObservationConvention);
		return embeddingModel;
	}

}
