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
		applyTo(request.getHeaders());
		return execution.execute(request, body);
	}

	// The WebClient sets the same headers as defaults.
	void applyTo(HttpHeaders headers) {
		setIfPresent(headers, "HTTP-Referer", this.httpReferer);
		setIfPresent(headers, "X-OpenRouter-Title", this.appTitle);
		setIfPresent(headers, "X-OpenRouter-Categories", this.appCategories);
	}

	private static void setIfPresent(HttpHeaders headers, String name, @Nullable String value) {
		if (StringUtils.hasText(value)) {
			headers.set(name, value);
		}
	}

}
