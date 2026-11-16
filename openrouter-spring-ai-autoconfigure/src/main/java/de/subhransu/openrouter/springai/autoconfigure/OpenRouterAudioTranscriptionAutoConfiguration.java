package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.transcription.observation.TranscriptionModelObservationConvention;
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
@ConditionalOnClass({ OpenRouterAudioTranscriptionModel.class, TranscriptionModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.AUDIO_TRANSCRIPTION_MODEL,
		havingValue = OpenRouterIdentifiers.PROVIDER_ID, matchIfMissing = true)
@EnableConfigurationProperties(OpenRouterAudioTranscriptionProperties.class)
public final class OpenRouterAudioTranscriptionAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(TranscriptionModel.class)
	OpenRouterAudioTranscriptionModel openRouterAudioTranscriptionModel(OpenRouterApi openRouterApi,
			OpenRouterAudioTranscriptionProperties transcriptionProperties,
			ObjectProvider<RetryTemplate> retryTemplateProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<TranscriptionModelObservationConvention> observationConventionProvider) {
		OpenRouterAudioTranscriptionModel transcriptionModel = OpenRouterAudioTranscriptionModel.builder()
			.openRouterApi(openRouterApi)
			.options(transcriptionProperties.toOptions())
			.maxInlineAudioBytes(transcriptionProperties.maxInlineAudioBytes())
			.retryTemplate(retryTemplateProvider.getIfUnique())
			.observationRegistry(observationRegistryProvider.getIfUnique())
			.build();
		observationConventionProvider.ifAvailable(transcriptionModel::setObservationConvention);
		return transcriptionModel;
	}

}
