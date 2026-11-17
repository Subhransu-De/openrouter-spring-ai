package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.OptionChecks;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

/**
 * Request-level prompt-cache controls for OpenAI models that support explicit prompt
 * caching, sent as {@code prompt_cache_options}. In {@code explicit} mode the provider
 * caches only the blocks marked with a breakpoint; a request without breakpoints is not
 * cached.
 *
 * <p>
 * An undocumented mode or lifetime is sent as written and logged once as a warning, so
 * values that OpenRouter adds later keep working.
 *
 * @param mode the cache mode, required; {@code explicit} is the only mode that OpenRouter
 * documents
 * @param ttl the minimum lifetime of every cache entry the request writes, {@code 30m} or
 * {@code 12h}; {@code null} keeps the provider default of 30 minutes
 * @author Subhransu De
 */
public record OpenRouterPromptCacheOptions(String mode, @Nullable String ttl) {

	/**
	 * The mode that caches only the blocks marked with a breakpoint.
	 */
	public static final String EXPLICIT = "explicit";

	/**
	 * A 30-minute minimum lifetime, the provider default.
	 */
	public static final String TTL_30_MINUTES = "30m";

	/**
	 * A 12-hour minimum lifetime, billed at a higher cache-write rate.
	 */
	public static final String TTL_12_HOURS = "12h";

	private static final Log logger = LogFactory.getLog(OpenRouterPromptCacheOptions.class);

	// Explicit mode turns off the provider's automatic caching, so the mode is never
	// inferred from a lifetime alone.
	public OpenRouterPromptCacheOptions {
		if (mode == null || mode.isEmpty()) {
			throw new IllegalArgumentException("promptCacheOptions mode must be set: explicit caches only the blocks "
					+ "marked with a cache breakpoint and turns off automatic caching");
		}
		if (ttl != null && ttl.isEmpty()) {
			throw new IllegalArgumentException("promptCacheOptions ttl must not be empty; leave it unset instead");
		}
		OptionChecks.warnIfUnknown(logger, "promptCacheOptions mode", mode, List.of(EXPLICIT));
		OptionChecks.warnIfUnknown(logger, "promptCacheOptions ttl", ttl, List.of(TTL_30_MINUTES, TTL_12_HOURS));
	}

	/**
	 * Returns explicit mode with the provider's default lifetime.
	 * @return the cache options
	 */
	public static OpenRouterPromptCacheOptions explicit() {
		return new OpenRouterPromptCacheOptions(EXPLICIT, null);
	}

	/**
	 * Returns explicit mode with a minimum lifetime.
	 * @param ttl the minimum lifetime, such as {@link #TTL_12_HOURS}
	 * @return the cache options
	 */
	public static OpenRouterPromptCacheOptions explicit(String ttl) {
		return new OpenRouterPromptCacheOptions(EXPLICIT, ttl);
	}

}
