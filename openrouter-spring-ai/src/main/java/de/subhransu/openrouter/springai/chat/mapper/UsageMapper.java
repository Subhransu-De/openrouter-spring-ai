package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;

public final class UsageMapper {

	private UsageMapper() {
	}

	public static OpenRouterUsage map(Usage usage) {
		if (usage == null) {
			return null;
		}
		return new OpenRouterUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
				cachedTokens(usage), reasoningTokens(usage), usage.cost(), usage);
	}

	// Preserve explicit top-level counts (including zero) before nested details.
	private static Integer cachedTokens(Usage usage) {
		if (usage.cachedTokens() != null) {
			return usage.cachedTokens();
		}
		return usage.promptTokensDetails() != null ? usage.promptTokensDetails().cachedTokens() : null;
	}

	private static Integer reasoningTokens(Usage usage) {
		if (usage.reasoningTokens() != null) {
			return usage.reasoningTokens();
		}
		return usage.completionTokensDetails() != null ? usage.completionTokensDetails().reasoningTokens() : null;
	}

}
