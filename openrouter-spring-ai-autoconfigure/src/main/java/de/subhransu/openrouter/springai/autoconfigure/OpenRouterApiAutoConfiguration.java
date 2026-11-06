package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterSerializationRuntimeHints;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import java.util.stream.Stream;
import org.springframework.ai.model.SpringAIModelProperties;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
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
import org.springframework.core.env.Environment;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration(afterName = { "org.springframework.boot.http.client.autoconfigure.HttpClientAutoConfiguration",
		"org.springframework.boot.http.client.autoconfigure.imperative.ImperativeHttpClientAutoConfiguration" })
@ImportRuntimeHints(OpenRouterSerializationRuntimeHints.class)
@ConditionalOnClass(OpenRouterApi.class)
@Conditional(OpenRouterModelSelectionCondition.class)
@EnableConfigurationProperties({ OpenRouterCommonProperties.class, OpenRouterConnectionProperties.class })
public final class OpenRouterApiAutoConfiguration {

	private static final Log logger = LogFactory.getLog(OpenRouterApiAutoConfiguration.class);

	@Bean
	@ConditionalOnMissingBean
	OpenRouterApi openRouterApi(OpenRouterCommonProperties commonProperties,
			OpenRouterConnectionProperties connectionProperties,
			ObjectProvider<ClientHttpRequestFactoryBuilder<?>> requestFactoryBuilderProvider,
			ObjectProvider<HttpClientSettings> httpClientSettingsProvider,
			ObjectProvider<RestClient.Builder> restClientBuilderProvider,
			ObjectProvider<WebClient.Builder> webClientBuilderProvider,
			ObjectProvider<OpenRouterRestClientCustomizer> restClientCustomizers,
			ObjectProvider<OpenRouterWebClientCustomizer> webClientCustomizers,
			ObjectProvider<JsonMapper> jsonMapperProvider, ObjectProvider<ObjectMapper> objectMapperProvider,
			ObjectProvider<SpringAiRetryProperties> retryPropertiesProvider, Environment environment) {
		String apiKey = commonProperties.getApiKey();
		Assert.hasText(apiKey, "OpenRouter API key must not be empty");
		retryPropertiesProvider
			.ifAvailable((retryProperties) -> reportUnusedRetrySettings(retryProperties, environment));
		OpenRouterCommonProperties.App app = commonProperties.getApp();
		// Customize clones only: an application may supply a singleton builder bean.
		RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable(RestClient::builder).clone();
		restClientCustomizers.orderedStream().forEach((customizer) -> customizer.customize(restClientBuilder));
		WebClient.Builder webClientBuilder = webClientBuilderProvider.getIfAvailable(WebClient::builder).clone();
		webClientCustomizers.orderedStream().forEach((customizer) -> customizer.customize(webClientBuilder));
		ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder = requestFactoryBuilderProvider
			.getIfAvailable(ClientHttpRequestFactoryBuilder::detect);
		HttpClientSettings httpClientSettings = httpClientSettingsProvider.getIfAvailable(HttpClientSettings::defaults);
		applyTimeouts(restClientBuilder, requestFactoryBuilder, httpClientSettings, connectionProperties);
		return OpenRouterApi.builder()
			.baseUrl(commonProperties.getBaseUrl())
			.apiKey(apiKey)
			.httpReferer(app != null ? app.getHttpReferer() : null)
			.applicationTitle(app != null ? app.getTitle() : null)
			.applicationCategories(app != null ? categories(app.getCategories()) : null)
			.restClientBuilder(restClientBuilder)
			.webClientBuilder(webClientBuilder)
			.objectMapper(jsonMapper(jsonMapperProvider, objectMapperProvider))
			.timeout(connectionProperties.getTimeout())
			.maxResponseBodyBytes(Math.toIntExact(connectionProperties.getMaxResponseBodySize().toBytes()))
			.maxErrorBodyBytes(Math.toIntExact(connectionProperties.getMaxErrorBodySize().toBytes()))
			.build();
	}

	private void applyTimeouts(RestClient.Builder restClientBuilder,
			ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder, HttpClientSettings shared,
			OpenRouterConnectionProperties connection) {
		Duration timeout = connection.getTimeout();
		// RestClient.Builder has no supported request-factory accessor. Install the
		// provider-scoped factory last so the blocking timeout cannot be bypassed by an
		// already-customized builder. Transport selection belongs in the factory builder,
		// where it can be composed with these settings.
		// Connect: the most specific setting wins. OpenRouter's connect-timeout, then an
		// explicit connection.timeout, then Boot's shared connect timeout (which streams
		// use as well), then the default. Read stays OpenRouter's: a generation can run
		// longer than a global read timeout tuned for REST calls.
		Duration connect = connection.getConnectTimeout();
		if (connect == null) {
			connect = connection.configuredTimeout();
		}
		if (connect == null) {
			connect = shared.connectTimeout();
		}
		if (connect == null) {
			connect = timeout;
		}
		restClientBuilder.requestFactory(requestFactoryBuilder.build(shared.withTimeouts(connect, timeout)));
	}

	// Jackson 3 mappers are format-specific: only a JsonMapper may serialize the JSON
	// wire protocol, and Boot's XmlMapper or CBORMapper must not make the lookup
	// ambiguous. Falling back to a default mapper is not silent when the application
	// has JSON mappers that we skip.
	private static JsonMapper jsonMapper(ObjectProvider<JsonMapper> jsonMappers,
			ObjectProvider<ObjectMapper> objectMappers) {
		JsonMapper jsonMapper = jsonMappers.getIfUnique();
		if (jsonMapper != null) {
			return jsonMapper;
		}
		long skipped = objectMappers.stream()
			.filter((mapper) -> mapper instanceof JsonMapper || mapper.getClass() == ObjectMapper.class)
			.count();
		if (skipped > 0) {
			logger.warn("OpenRouter uses a default JsonMapper because the context has no unique JsonMapper bean; "
					+ skipped
					+ " JSON ObjectMapper bean(s) are not used. Declare one JsonMapper bean or mark one @Primary.");
		}
		return new JsonMapper();
	}

	// These keys configure Spring AI's ResponseErrorHandler bean, which OpenRouterApi
	// does not use. Say so instead of ignoring them silently. They may still apply to
	// another provider in the same application, so this is not a failure, and it is only
	// a warning when every model selector rules other providers out.
	private static void reportUnusedRetrySettings(SpringAiRetryProperties retryProperties, Environment environment) {
		List<String> unused = new ArrayList<>();
		if (!CollectionUtils.isEmpty(retryProperties.getOnHttpCodes())) {
			unused.add("on-http-codes");
		}
		if (!CollectionUtils.isEmpty(retryProperties.getExcludeOnHttpCodes())) {
			unused.add("exclude-on-http-codes");
		}
		if (retryProperties.isOnClientErrors()) {
			unused.add("on-client-errors");
		}
		if (unused.isEmpty()) {
			return;
		}
		String message = "spring.ai.retry." + String.join(", spring.ai.retry.", unused)
				+ " do not apply to OpenRouter models, which classify HTTP errors themselves. "
				+ "spring.ai.retry.max-attempts and spring.ai.retry.backoff.* still apply.";
		if (onlyOpenRouterSelected(environment)) {
			logger.warn(message);
		}
		else {
			logger.info(message);
		}
	}

	private static boolean onlyOpenRouterSelected(Environment environment) {
		// Every Spring AI selector counts: some providers select on the narrower keys,
		// for example Google GenAI text embeddings on spring.ai.model.embedding.text and
		// Mistral moderation, which uses Spring AI's error handler, on .moderation.
		return Stream
			.of(SpringAIModelProperties.CHAT_MODEL, SpringAIModelProperties.EMBEDDING_MODEL,
					SpringAIModelProperties.TEXT_EMBEDDING_MODEL, SpringAIModelProperties.MULTI_MODAL_EMBEDDING_MODEL,
					SpringAIModelProperties.IMAGE_MODEL, SpringAIModelProperties.AUDIO_SPEECH_MODEL,
					SpringAIModelProperties.AUDIO_TRANSCRIPTION_MODEL, SpringAIModelProperties.MODERATION_MODEL)
			.map(environment::getProperty)
			.allMatch((selected) -> OpenRouterIdentifiers.PROVIDER_ID.equals(selected) || "none".equals(selected));
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
