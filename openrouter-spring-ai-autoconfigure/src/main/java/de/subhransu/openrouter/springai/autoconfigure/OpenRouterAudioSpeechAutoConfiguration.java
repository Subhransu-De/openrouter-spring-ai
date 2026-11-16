package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.observation.TextToSpeechModelObservationConvention;
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
@ConditionalOnClass({ OpenRouterAudioSpeechModel.class, TextToSpeechModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.AUDIO_SPEECH_MODEL,
		havingValue = OpenRouterIdentifiers.PROVIDER_ID, matchIfMissing = true)
@EnableConfigurationProperties(OpenRouterAudioSpeechProperties.class)
public final class OpenRouterAudioSpeechAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(TextToSpeechModel.class)
	OpenRouterAudioSpeechModel openRouterAudioSpeechModel(OpenRouterApi openRouterApi,
			OpenRouterAudioSpeechProperties speechProperties, ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<TextToSpeechModelObservationConvention> observationConventionProvider) {
		OpenRouterAudioSpeechModel speechModel = OpenRouterAudioSpeechModel.builder()
			.openRouterApi(openRouterApi)
			.options(speechProperties.toOptions())
			.retryTemplate(retryTemplateProvider.getIfUnique())
			.observationRegistry(observationRegistryProvider.getIfUnique())
			.build();
		observationConventionProvider.ifAvailable(speechModel::setObservationConvention);
		return speechModel;
	}

}
