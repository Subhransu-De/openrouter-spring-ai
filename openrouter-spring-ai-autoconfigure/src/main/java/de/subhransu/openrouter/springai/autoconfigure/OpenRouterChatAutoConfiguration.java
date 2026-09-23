package de.subhransu.openrouter.springai.autoconfigure;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
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

@AutoConfiguration(after = { OpenRouterApiAutoConfiguration.class, SpringAiRetryAutoConfiguration.class },
		afterName = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass({ OpenRouterChatModel.class, ChatModel.class })
@ConditionalOnBean(OpenRouterApi.class)
@ConditionalOnProperty(name = SpringAIModelProperties.CHAT_MODEL, havingValue = OpenRouterIdentifiers.PROVIDER_ID,
		matchIfMissing = true)
@EnableConfigurationProperties(OpenRouterChatProperties.class)
public class OpenRouterChatAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean(ChatModel.class)
	OpenRouterChatModel openRouterChatModel(OpenRouterApi openRouterApi, OpenRouterChatProperties chatProperties,
			ObjectProvider<ToolCallingManager> toolCallingManagerProvider,
			ObjectProvider<RetryTemplate> retryTemplateProvider, ObjectProvider<ObjectMapper> objectMapperProvider,
			ObjectProvider<ObservationRegistry> observationRegistryProvider,
			ObjectProvider<ChatModelObservationConvention> observationConventionProvider) {
		OpenRouterChatModel chatModel = OpenRouterChatModel.builder()
			.openRouterApi(openRouterApi)
			.defaultOptions(chatProperties.toOptions())
			.toolCallingManager(toolCallingManagerProvider.getIfAvailable())
			.retryTemplate(retryTemplateProvider.getIfAvailable())
			.objectMapper(objectMapperProvider.getIfAvailable(ObjectMapper::new))
			.observationRegistry(observationRegistryProvider.getIfAvailable())
			.streamingStateMaxBytes(chatProperties.getStreamingState().getMaxSize().toBytes())
			.streamingStateMaxChoices(chatProperties.getStreamingState().getMaxChoices())
			.toolCallAggregationMaxBytes(chatProperties.getToolCallAggregation().getMaxSize().toBytes())
			.toolCallAggregationMaxChunks(chatProperties.getToolCallAggregation().getMaxChunks())
			.toolCallAggregationMaxDuration(chatProperties.getToolCallAggregation().getMaxDuration())
			.build();
		observationConventionProvider.ifAvailable(chatModel::setObservationConvention);
		return chatModel;
	}

}
