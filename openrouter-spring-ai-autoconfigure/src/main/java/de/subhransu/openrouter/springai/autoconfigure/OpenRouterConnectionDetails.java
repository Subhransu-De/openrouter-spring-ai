package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.service.connection.ConnectionDetails;

/**
 * Details for a connection to the OpenRouter API, read by the auto-configured
 * {@code OpenRouterApi}.
 * <p>
 * By default they come from {@code spring.ai.openrouter.base-url} and
 * {@code spring.ai.openrouter.api-key}. Define a bean of this type, for example in a test
 * that points OpenRouter at a mock server, to supply the connection instead; the
 * properties are then not read.
 *
 * @author Subhransu De
 */
public interface OpenRouterConnectionDetails extends ConnectionDetails {

	/**
	 * The base URL of the OpenRouter API.
	 * @return the base URL, or {@code null} for {@code https://openrouter.ai/api/v1}
	 */
	@Nullable String getBaseUrl();

	/**
	 * The OpenRouter API key, sent as a bearer token.
	 * @return the API key; the auto-configured {@code OpenRouterApi} rejects a missing or
	 * empty key
	 */
	@Nullable String getApiKey();

}
