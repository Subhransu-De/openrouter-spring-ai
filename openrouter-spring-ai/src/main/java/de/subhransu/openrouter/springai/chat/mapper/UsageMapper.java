package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterCostDetails;
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

	public static @Nullable Long cacheWriteInputTokens(@Nullable Object nativeUsage) {
		if (nativeUsage instanceof Usage usage) {
			var details = usage.promptTokensDetails();
			Integer written = details != null ? details.cacheWriteTokens() : null;
			return written != null ? written.longValue() : null;
		}
		return null;
	}

	// Never null, so callers read a breakdown value without first checking for the
	// object.
	public static OpenRouterCostDetails costDetails(@Nullable Object nativeUsage) {
		if (nativeUsage instanceof Usage usage && usage.costDetails() != null) {
			var details = usage.costDetails();
			return new OpenRouterCostDetails(details.upstreamInferenceCost(), details.upstreamInferencePromptCost(),
					details.upstreamInferenceCompletionsCost(), details.serverToolCost());
		}
		return new OpenRouterCostDetails(null, null, null, null);
	}

	public static @Nullable Boolean byok(@Nullable Object nativeUsage) {
		return nativeUsage instanceof Usage usage ? usage.isByok() : null;
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
