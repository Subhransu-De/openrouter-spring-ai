package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.observation.ImageModelObservationConvention;
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
@ConditionalOnClass({ OpenRouterImageModel.class, ImageModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.IMAGE_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
		matchIfMissing = true)
@EnableConfigurationProperties(OpenRouterImageProperties.class)
public final class OpenRouterImageAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(ImageModel.class)
	OpenRouterImageModel openRouterImageModel(OpenRouterApi openRouterApi, OpenRouterImageProperties imageProperties,
			ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<ImageModelObservationConvention> observationConventionProvider) {
		OpenRouterImageModel imageModel = OpenRouterImageModel.builder()
			.openRouterApi(openRouterApi)
			.defaultOptions(imageProperties.toOptions())
			.retryTemplate(retryTemplateProvider.getIfUnique())
			.observationRegistry(observationRegistryProvider.getIfUnique())
			.build();
		observationConventionProvider.ifAvailable(imageModel::setObservationConvention);
		return imageModel;
	}

}
