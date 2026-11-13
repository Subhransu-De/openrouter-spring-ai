package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageResponse;
import de.subhransu.openrouter.springai.chat.mapper.UsageMapper;

/**
 * Token counts, cost and cache details that OpenRouter reports for a call. Read it with
 * {@link #from(ChatResponse)}, {@link #from(EmbeddingResponse)} or
 * {@link #from(ImageResponse)}; they never return {@code null}.
 *
 * @author Subhransu De
 */
public class OpenRouterUsage extends DefaultUsage {

	private final @Nullable Integer cachedTokens;

	private final @Nullable Integer reasoningTokens;

	private final @Nullable Double cost;

	private final OpenRouterCostDetails costDetails;

	private final @Nullable Boolean byok;

	public OpenRouterUsage(@Nullable Integer promptTokens, @Nullable Integer generationTokens,
			@Nullable Integer totalTokens, @Nullable Integer cachedTokens, @Nullable Integer reasoningTokens,
			@Nullable Double cost, @Nullable Object nativeUsage) {
		super(promptTokens, generationTokens, totalTokens, nativeUsage,
				cachedTokens != null ? cachedTokens.longValue() : null, UsageMapper.cacheWriteInputTokens(nativeUsage));
		this.cachedTokens = cachedTokens;
		this.reasoningTokens = reasoningTokens;
		this.cost = cost;
		this.costDetails = UsageMapper.costDetails(nativeUsage);
		this.byok = UsageMapper.byok(nativeUsage);
	}

	/**
	 * Read the usage of a chat response.
	 * @param response the chat response, from a call or a stream chunk
	 * @return the usage; when the provider sent none, the token counts are {@code 0} and
	 * the other values are {@code null}
	 */
	public static OpenRouterUsage from(ChatResponse response) {
		return UsageMapper.from(response.getMetadata().getUsage());
	}

	/**
	 * Read the usage of an embedding response.
	 * @param response the embedding response
	 * @return the usage; when the provider sent none, the token counts are {@code 0} and
	 * the other values are {@code null}
	 */
	public static OpenRouterUsage from(EmbeddingResponse response) {
		return UsageMapper.from(response.getMetadata().getUsage());
	}

	/**
	 * Read the usage of an image response, stored under
	 * {@link OpenRouterMetadata#IMAGE_USAGE}.
	 * @param response the image response, from a call or a stream event
	 * @return the usage; when the provider sent none, the token counts are {@code 0} and
	 * the other values are {@code null}
	 */
	public static OpenRouterUsage from(ImageResponse response) {
		return UsageMapper
			.from(response.getMetadata().get(OpenRouterMetadata.IMAGE_USAGE) instanceof Usage usage ? usage : null);
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

	public OpenRouterCostDetails getCostDetails() {
		return this.costDetails;
	}

	public @Nullable Boolean getByok() {
		return this.byok;
	}

}
