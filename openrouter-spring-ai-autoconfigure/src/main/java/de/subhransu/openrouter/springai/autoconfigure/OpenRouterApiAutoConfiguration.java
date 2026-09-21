package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterSerializationRuntimeHints;
import java.time.Duration;
import java.util.List;
import java.util.StringJoiner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration(afterName = { "org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration",
		"org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration" })
@ImportRuntimeHints(OpenRouterSerializationRuntimeHints.class)
@ConditionalOnClass(OpenRouterApi.class)
@Conditional(OpenRouterModelSelectionCondition.class)
@EnableConfigurationProperties({ OpenRouterCommonProperties.class, OpenRouterConnectionProperties.class })
public class OpenRouterApiAutoConfiguration {

	@Bean
	@ConditionalOnMissingBean
	OpenRouterApi openRouterApi(OpenRouterCommonProperties commonProperties,
			OpenRouterConnectionProperties connectionProperties,
			ObjectProvider<ClientHttpRequestFactoryBuilder<?>> requestFactoryBuilderProvider,
			ObjectProvider<HttpClientSettings> httpClientSettingsProvider,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider,
			ObjectProvider<WebClient.Builder> webClientBuilderProvider,
			ObjectProvider<ObjectMapper> objectMapperProvider) {
		String apiKey = commonProperties.getApiKey();
		Assert.hasText(apiKey, "OpenRouter API key must not be empty");
		OpenRouterCommonProperties.App app = commonProperties.getApp();
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder).clone();
		ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder = requestFactoryBuilderProvider
			.getIfAvailable(ClientHttpRequestFactoryBuilder::detect);
		HttpClientSettings httpClientSettings = httpClientSettingsProvider.getIfAvailable(HttpClientSettings::defaults);
		applyTimeout(restClientBuilder, requestFactoryBuilder, httpClientSettings, connectionProperties.getTimeout());
		return OpenRouterApi.builder()
			.baseUrl(commonProperties.getBaseUrl())
			.apiKey(apiKey)
			.httpReferer(app != null ? app.getHttpReferer() : null)
			.applicationTitle(app != null ? app.getTitle() : null)
			.applicationCategories(app != null ? categories(app.getCategories()) : null)
			.restClientBuilder(restClientBuilder)
			.webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder))
			.objectMapper(objectMapperProvider.getIfAvailable(ObjectMapper::new))
			.timeout(connectionProperties.getTimeout())
			.maxResponseBodyBytes(Math.toIntExact(connectionProperties.getMaxResponseBodySize().toBytes()))
			.maxErrorBodyBytes(Math.toIntExact(connectionProperties.getMaxErrorBodySize().toBytes()))
			.build();
	}

	private void applyTimeout(RestClient.Builder restClientBuilder,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder, HttpClientSettings httpClientSettings,
			@Nullable Duration timeout) {
		if (timeout == null) {
			return;
		}
		// RestClient.Builder has no supported request-factory accessor. Install the
		// provider-scoped factory last so the blocking timeout cannot be bypassed by an
		// already-customized builder. Transport selection belongs in the factory builder,
		// where it can be composed with these settings.
		HttpClientSettings settings = httpClientSettings.withTimeouts(timeout, timeout);
		restClientBuilder.requestFactory(requestFactoryBuilder.build(settings));
	}

	private @Nullable String categories(@Nullable List<String> categories) {
		if (categories == null || categories.isEmpty()) {
			return null;
		}
		StringJoiner joiner = new StringJoiner(",");
		categories.forEach(joiner::add);
		return joiner.toString();
	}

}
