package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.PromptCacheOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterPromptCacheOptions;
import org.jspecify.annotations.Nullable;

/**
 * Maps the typed prompt-cache controls to their wire form in both request modes.
 *
 * @author Subhransu De
 */
final class PromptCacheMapper {

	private PromptCacheMapper() {
	}

	static @Nullable PromptCacheOptions map(@Nullable OpenRouterPromptCacheOptions options) {
		return options == null ? null : new PromptCacheOptions(options.mode(), options.ttl());
	}

}
