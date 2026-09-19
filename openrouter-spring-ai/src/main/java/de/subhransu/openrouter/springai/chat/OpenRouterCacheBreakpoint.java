package de.subhransu.openrouter.springai.chat;

import java.util.Objects;

/**
 * Explicit cache boundary in system or user message text, for Chat Completions. Store an
 * ordered list under {@link #METADATA_KEY} in the message metadata. Offsets use
 * {@link String#length()} units and must not split a surrogate pair. Retain the metadata
 * with the unchanged text when replaying conversation history.
 *
 * @param endIndex exclusive end of the cached text prefix
 * @param ttl cache lifetime; one hour requires a supporting Claude provider
 * @author OpenRouter Spring AI contributors
 */
public record OpenRouterCacheBreakpoint(int endIndex, Ttl ttl) {

	public static final String METADATA_KEY = "openrouter.cache.breakpoints";

	public OpenRouterCacheBreakpoint {
		if (endIndex <= 0) {
			throw new IllegalArgumentException("Cache breakpoint endIndex must be positive");
		}
		Objects.requireNonNull(ttl, "ttl must not be null");
	}

	public OpenRouterCacheBreakpoint(int endIndex) {
		this(endIndex, Ttl.FIVE_MINUTES);
	}

	public enum Ttl {

		FIVE_MINUTES, ONE_HOUR

	}

}
