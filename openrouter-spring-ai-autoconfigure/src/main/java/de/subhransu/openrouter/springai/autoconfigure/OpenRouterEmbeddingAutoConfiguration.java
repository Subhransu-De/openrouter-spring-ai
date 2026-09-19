package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
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

@AutoConfiguration(after = { OpenRouterApiAutoConfiguration.class, SpringAiRetryAutoConfiguration.class })
@ConditionalOnClass({ OpenRouterEmbeddingModel.class, EmbeddingModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.EMBEDDING_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
		matchIfMissing = true)
@EnableConfigurationProperties(OpenRouterEmbeddingProperties.class)
public class OpenRouterEmbeddingAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(EmbeddingModel.class)
	OpenRouterEmbeddingModel openRouterEmbeddingModel(OpenRouterApi openRouterApi,
			OpenRouterEmbeddingProperties embeddingProperties, ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<EmbeddingModelObservationConvention> observationConventionProvider) {
		OpenRouterEmbeddingModel embeddingModel = OpenRouterEmbeddingModel.builder()
			.openRouterApi(openRouterApi)
			.defaultOptions(embeddingProperties.toOptions())
			.metadataMode(embeddingProperties.getMetadataMode())
			.retryTemplate(retryTemplateProvider.getIfAvailable())
			.observationRegistry(observationRegistryProvider.getIfAvailable())
			.build();
		observationConventionProvider.ifAvailable(embeddingModel::setObservationConvention);
		return embeddingModel;
	}

}
