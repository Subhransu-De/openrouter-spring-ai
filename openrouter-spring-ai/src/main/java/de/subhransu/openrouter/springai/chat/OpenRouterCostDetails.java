package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;

/**
 * OpenRouter's breakdown of a call's cost. The total charged by OpenRouter is
 * {@link OpenRouterUsage#getCost()}; these values come from the upstream provider.
 * {@link OpenRouterUsage#getCostDetails()} never returns {@code null}: when OpenRouter
 * sends no breakdown, every value here is {@code null}, as is any single value it omits.
 *
 * <p>
 * OpenRouter does not price reasoning separately: reasoning tokens are billed as
 * completion tokens, so their cost is part of {@code upstreamInferenceCompletionsCost}.
 *
 * @param upstreamInferenceCost the total that the upstream provider charged
 * @param upstreamInferencePromptCost the upstream cost of the prompt (input) tokens
 * @param upstreamInferenceCompletionsCost the upstream cost of the completion (output)
 * tokens, including reasoning tokens
 * @param serverToolCost the cost of metered OpenRouter server tools that ran
 * @author Subhransu De
 */
public record OpenRouterCostDetails(@Nullable Double upstreamInferenceCost,
		@Nullable Double upstreamInferencePromptCost, @Nullable Double upstreamInferenceCompletionsCost,
		@Nullable Double serverToolCost) {
}
