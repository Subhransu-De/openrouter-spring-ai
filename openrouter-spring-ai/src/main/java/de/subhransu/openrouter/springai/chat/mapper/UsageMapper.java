package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import org.springframework.lang.Contract;

public final class UsageMapper {

	private UsageMapper() {
	}

	@Contract("!null -> !null")
	public static @Nullable OpenRouterUsage map(@Nullable Usage usage) {
		if (usage == null) {
			return null;
		}
		return new OpenRouterUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(),
				cachedTokens(usage), reasoningTokens(usage), usage.cost(), usage);
	}

	// Preserve explicit top-level counts (including zero) before nested details.
	private static @Nullable Integer cachedTokens(Usage usage) {
		var details = usage.promptTokensDetails();
		if (usage.cachedTokens() != null) {
			return usage.cachedTokens();
		}
		return details != null ? details.cachedTokens() : null;
	}

	private static @Nullable Integer reasoningTokens(Usage usage) {
		var details = usage.completionTokensDetails();
		if (usage.reasoningTokens() != null) {
			return usage.reasoningTokens();
		}
		return details != null ? details.reasoningTokens() : null;
	}

}
