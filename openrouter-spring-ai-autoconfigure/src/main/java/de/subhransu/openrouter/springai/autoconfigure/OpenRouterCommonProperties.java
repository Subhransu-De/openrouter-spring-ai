package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(OpenRouterCommonProperties.CONFIG_PREFIX)
public class OpenRouterCommonProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter";

	/**
	 * OpenRouter API key, sent as a bearer token. Required while any model type selects
	 * OpenRouter.
	 */
	private @Nullable String apiKey;

	/**
	 * Base URL of the OpenRouter API.
	 */
	private @Nullable String baseUrl = OpenRouterApi.DEFAULT_BASE_URL;

	/**
	 * Application attribution sent with every request.
	 */
	private @Nullable App app = new App();

	public @Nullable String getApiKey() {
		return this.apiKey;
	}

	public void setApiKey(@Nullable String apiKey) {
		this.apiKey = apiKey;
	}

	public @Nullable String getBaseUrl() {
		return this.baseUrl;
	}

	public void setBaseUrl(@Nullable String baseUrl) {
		this.baseUrl = baseUrl;
	}

	public @Nullable App getApp() {
		return this.app;
	}

	public void setApp(@Nullable App app) {
		this.app = app;
	}

	public static class App {

		/**
		 * Application URL sent in the HTTP-Referer attribution header.
		 */
		private @Nullable String httpReferer;

		/**
		 * Application name sent in the X-OpenRouter-Title attribution header.
		 */
		private @Nullable String title;

		/**
		 * Comma-separated list of application categories sent in the
		 * X-OpenRouter-Categories attribution header.
		 */
		private @Nullable List<String> categories;

		public @Nullable String getHttpReferer() {
			return this.httpReferer;
		}

		public void setHttpReferer(@Nullable String httpReferer) {
			this.httpReferer = httpReferer;
		}

		public @Nullable String getTitle() {
			return this.title;
		}

		public void setTitle(@Nullable String title) {
			this.title = title;
		}

		public @Nullable List<String> getCategories() {
			return this.categories;
		}

		public void setCategories(@Nullable List<String> categories) {
			this.categories = categories;
		}

	}

}
