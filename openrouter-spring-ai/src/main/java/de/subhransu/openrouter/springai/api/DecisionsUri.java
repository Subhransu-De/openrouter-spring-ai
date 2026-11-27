package de.subhransu.openrouter.springai.api;

import java.net.URI;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Resolves the alpha Decisions endpoint, which sits beside the versioned API, against the
 * client's effective base URL. It runs per request on the {@code RestClient}'s own URI
 * builder, so a custom {@code UriBuilderFactory} or default URI variables apply as they
 * do to the other endpoints.
 *
 * @author Subhransu De
 */
final class DecisionsUri {

	private static final String VERSION = "/v1";

	private DecisionsUri() {
	}

	/**
	 * Map a base path ending in {@code /v1} to its sibling {@code /alpha/decisions}, and
	 * append {@code /alpha/decisions} to any other base path.
	 */
	static URI resolve(UriBuilder base) {
		URI baseUri = base.build();
		String path = StringUtils.trimTrailingCharacter(baseUri.getRawPath(), '/');
		String root = path.endsWith(VERSION) ? path.substring(0, path.length() - VERSION.length()) : path;
		return UriComponentsBuilder.fromUri(baseUri).replacePath(root + OpenRouterApi.DECISIONS).build(true).toUri();
	}

}
