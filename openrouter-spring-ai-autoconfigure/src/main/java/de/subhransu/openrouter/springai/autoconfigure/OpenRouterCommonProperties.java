package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(OpenRouterCommonProperties.CONFIG_PREFIX)
public class OpenRouterCommonProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter";

	private @Nullable String apiKey;

	private @Nullable String baseUrl = OpenRouterApi.DEFAULT_BASE_URL;

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

		private @Nullable String httpReferer;

		private @Nullable String title;

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
