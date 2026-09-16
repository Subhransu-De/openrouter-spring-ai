package de.subhransu.openrouter.springai.chat;

import org.springframework.ai.chat.metadata.DefaultUsage;

public class OpenRouterUsage extends DefaultUsage {

	private final Integer cachedTokens;

	private final Integer reasoningTokens;

	private final Double cost;

	public OpenRouterUsage(Integer promptTokens, Integer generationTokens, Integer totalTokens, Integer cachedTokens,
			Integer reasoningTokens, Double cost, Object nativeUsage) {
		super(promptTokens, generationTokens, totalTokens, nativeUsage,
				cachedTokens != null ? cachedTokens.longValue() : null, null);
		this.cachedTokens = cachedTokens;
		this.reasoningTokens = reasoningTokens;
		this.cost = cost;
	}

	public Integer getCachedTokens() {
		return this.cachedTokens;
	}

	public Integer getReasoningTokens() {
		return this.reasoningTokens;
	}

	public Double getCost() {
		return this.cost;
	}

}
