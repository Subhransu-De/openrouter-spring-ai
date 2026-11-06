package de.subhransu.openrouter.springai.autoconfigure;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Customizes the {@link WebClient.Builder} that the auto-configured {@code OpenRouterApi}
 * uses for streaming calls, without changing any other client in the application.
 * <p>
 * Beans of this type run in {@link org.springframework.core.annotation.Order @Order}
 * order on a clone of the application's {@code WebClient.Builder}, after Spring Boot's
 * {@code WebClientCustomizer} beans. OpenRouter then installs its base URL, its
 * {@code Authorization} and attribution default headers and the
 * {@code spring.ai.openrouter.connection.max-response-body-size} codec limit, so values a
 * customizer sets on the builder do not replace them. Filters still run on every request
 * and can change any header or the URL, so treat customizers as trusted application code
 * and keep credentials and destinations unchanged.
 *
 * @author Subhransu De
 */
@FunctionalInterface
public interface OpenRouterWebClientCustomizer {

	/**
	 * Customize the OpenRouter-scoped builder.
	 * @param webClientBuilder the builder to customize
	 */
	void customize(WebClient.Builder webClientBuilder);

}
