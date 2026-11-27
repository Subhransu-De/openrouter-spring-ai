package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.observation.DecisionModelObservationConvention;
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

// Opt-in, like the audio models: Spring AI's own decision providers also match an unset
// selector and back off only for their own type, and OpenRouter's Decisions API is in
// alpha with no default model.
@AutoConfiguration(after = { OpenRouterApiAutoConfiguration.class, SpringAiRetryAutoConfiguration.class })
@ConditionalOnClass({ OpenRouterDecisionModel.class, DecisionModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.DECISION_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID)
@EnableConfigurationProperties(OpenRouterDecisionProperties.class)
public final class OpenRouterDecisionAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(DecisionModel.class)
	OpenRouterDecisionModel openRouterDecisionModel(OpenRouterApi openRouterApi,
			OpenRouterDecisionProperties decisionProperties, ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<DecisionModelObservationConvention> observationConventionProvider) {
		OpenRouterDecisionModel decisionModel = OpenRouterDecisionModel.builder()
			.openRouterApi(openRouterApi)
			.options(decisionProperties.toOptions())
			.retryTemplate(retryTemplateProvider.getIfUnique())
			.observationRegistry(observationRegistryProvider.getIfUnique())
			.build();
		observationConventionProvider.ifAvailable(decisionModel::setObservationConvention);
		return decisionModel;
	}

}
