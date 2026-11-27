package de.subhransu.openrouter.springai.api;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

final class OpenRouterAttributionInterceptor implements ClientHttpRequestInterceptor {

	private final @Nullable String httpReferer;

	private final @Nullable String appTitle;

	private final @Nullable String appCategories;

	OpenRouterAttributionInterceptor(@Nullable String httpReferer, @Nullable String appTitle,
			@Nullable String appCategories) {
		this.httpReferer = httpReferer;
		this.appTitle = appTitle;
		this.appCategories = appCategories;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		addTo(request.getHeaders());
		return execution.execute(request, body);
	}

	/**
	 * Set the configured attribution headers. The streaming client uses this for its
	 * default headers.
	 * @param headers the headers to set them on
	 */
	void addTo(HttpHeaders headers) {
		if (StringUtils.hasText(this.httpReferer)) {
			headers.set("HTTP-Referer", this.httpReferer);
		}
		if (StringUtils.hasText(this.appTitle)) {
			headers.set("X-OpenRouter-Title", this.appTitle);
		}
		if (StringUtils.hasText(this.appCategories)) {
			headers.set("X-OpenRouter-Categories", this.appCategories);
		}
	}

}
