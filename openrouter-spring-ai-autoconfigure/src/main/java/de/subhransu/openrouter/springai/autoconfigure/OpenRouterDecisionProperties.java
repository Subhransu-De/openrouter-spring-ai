package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionOptions;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(OpenRouterDecisionProperties.CONFIG_PREFIX)
public class OpenRouterDecisionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.decision";

	/**
	 * Decision model id in vendor/model form, such as typesafe/jev-1.14.
	 */
	private @Nullable String model;

	/**
	 * End-user identifier for OpenRouter's analytics and rate limits.
	 */
	private @Nullable String user;

	/**
	 * Default session id for observability grouping, sent as session_id. At most 256
	 * characters.
	 */
	private @Nullable String sessionId;

	/**
	 * Observability metadata sent as trace. A request trace replaces it as a whole.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterTrace trace;

	/**
	 * Provider routing preferences.
	 */
	@NestedConfigurationProperty
	private @Nullable OpenRouterProviderPreferences provider;

	public OpenRouterDecisionOptions toOptions() {
		return OpenRouterDecisionOptions.builder()
			.model(this.model)
			.user(this.user)
			.sessionId(this.sessionId)
			.trace(this.trace)
			.provider(this.provider)
			.build();
	}

	public @Nullable String getModel() {
		return this.model;
	}

	public void setModel(@Nullable String model) {
		this.model = model;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public void setUser(@Nullable String user) {
		this.user = user;
	}

	public @Nullable String getSessionId() {
		return this.sessionId;
	}

	public void setSessionId(@Nullable String sessionId) {
		this.sessionId = sessionId;
	}

	public @Nullable OpenRouterTrace getTrace() {
		return this.trace;
	}

	public void setTrace(@Nullable OpenRouterTrace trace) {
		this.trace = trace;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public void setProvider(@Nullable OpenRouterProviderPreferences provider) {
		this.provider = provider;
	}

}
