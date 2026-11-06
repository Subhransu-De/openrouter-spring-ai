package de.subhransu.openrouter.springai.autoconfigure;

import org.springframework.web.client.RestClient;

/**
 * Customizes the {@link RestClient.Builder} that the auto-configured
 * {@code OpenRouterApi} uses for blocking calls, without changing any other client in the
 * application.
 * <p>
 * Beans of this type run in {@link org.springframework.core.annotation.Order @Order}
 * order on a clone of the application's {@code RestClient.Builder}, after Spring Boot's
 * {@code RestClientCustomizer} beans. OpenRouter then installs its base URL and its
 * {@code Authorization} and attribution default headers, so values a customizer sets on
 * the builder do not replace them. Interceptors still run on every request and can change
 * any header or the URI, so treat customizers as trusted application code and keep
 * credentials and destinations unchanged. OpenRouter also replaces the request factory to
 * install its timeouts; choose the transport with a
 * {@code ClientHttpRequestFactoryBuilder} bean instead of setting one here.
 *
 * @author Subhransu De
 */
@FunctionalInterface
public interface OpenRouterRestClientCustomizer {

	/**
	 * Customize the OpenRouter-scoped builder.
	 * @param restClientBuilder the builder to customize
	 */
	void customize(RestClient.Builder restClientBuilder);

}
