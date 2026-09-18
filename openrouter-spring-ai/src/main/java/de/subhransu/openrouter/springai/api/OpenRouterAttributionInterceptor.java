package de.subhransu.openrouter.springai.api;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StringUtils;

final class OpenRouterAttributionInterceptor implements ClientHttpRequestInterceptor {

	private final @Nullable String httpReferer;

	private final @Nullable String applicationTitle;

	private final @Nullable String applicationCategories;

	OpenRouterAttributionInterceptor(@Nullable String httpReferer, @Nullable String applicationTitle,
			@Nullable String applicationCategories) {
		this.httpReferer = httpReferer;
		this.applicationTitle = applicationTitle;
		this.applicationCategories = applicationCategories;
	}

	@Override
	public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
			throws IOException {
		if (StringUtils.hasText(this.httpReferer)) {
			request.getHeaders().set("HTTP-Referer", this.httpReferer);
		}
		if (StringUtils.hasText(this.applicationTitle)) {
			request.getHeaders().set("X-OpenRouter-Title", this.applicationTitle);
		}
		if (StringUtils.hasText(this.applicationCategories)) {
			request.getHeaders().set("X-OpenRouter-Categories", this.applicationCategories);
		}
		return execution.execute(request, body);
	}

}
