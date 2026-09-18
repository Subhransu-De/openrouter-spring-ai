package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;

public record OpenRouterReasoningOptions(@Nullable String effort, @Nullable Integer maxTokens,
		@Nullable Boolean exclude, @Nullable Boolean enabled) {

	public OpenRouterReasoningOptions {
		if (effort != null && maxTokens != null) {
			throw new IllegalArgumentException("OpenRouter reasoning accepts 'effort' or 'max-tokens', not both");
		}
		if (effort != null && effort.isBlank()) {
			throw new IllegalArgumentException("reasoning 'effort' must not be blank");
		}
		if (maxTokens != null && maxTokens <= 0) {
			throw new IllegalArgumentException("reasoning 'max-tokens' must be positive, but was " + maxTokens);
		}
	}

}
