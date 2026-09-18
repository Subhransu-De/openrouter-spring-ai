package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.DefaultUsage;

public class OpenRouterUsage extends DefaultUsage {

	private final @Nullable Integer cachedTokens;

	private final @Nullable Integer reasoningTokens;

	private final @Nullable Double cost;

	public OpenRouterUsage(@Nullable Integer promptTokens, @Nullable Integer generationTokens,
			@Nullable Integer totalTokens, @Nullable Integer cachedTokens, @Nullable Integer reasoningTokens,
			@Nullable Double cost, @Nullable Object nativeUsage) {
		super(promptTokens, generationTokens, totalTokens, nativeUsage,
				cachedTokens != null ? cachedTokens.longValue() : null, null);
		this.cachedTokens = cachedTokens;
		this.reasoningTokens = reasoningTokens;
		this.cost = cost;
	}

	public @Nullable Integer getCachedTokens() {
		return this.cachedTokens;
	}

	public @Nullable Integer getReasoningTokens() {
		return this.reasoningTokens;
	}

	public @Nullable Double getCost() {
		return this.cost;
	}

}
