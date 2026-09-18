package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record Usage(@JsonAlias("input_tokens") @JsonProperty("prompt_tokens") @Nullable Integer promptTokens,
		@JsonAlias("output_tokens") @JsonProperty("completion_tokens") @Nullable Integer completionTokens,
		@JsonProperty("total_tokens") @Nullable Integer totalTokens,
		@JsonAlias("cached_tokens") @JsonProperty("cache_read_input_tokens") @Nullable Integer cachedTokens,
		@JsonProperty("reasoning_tokens") @Nullable Integer reasoningTokens, @Nullable Double cost,
		@JsonAlias("input_tokens_details") @JsonProperty("prompt_tokens_details") @Nullable PromptTokensDetails promptTokensDetails,
		@JsonAlias("output_tokens_details") @JsonProperty("completion_tokens_details") @Nullable CompletionTokensDetails completionTokensDetails,
		@Nullable Map<String, @Nullable Object> details) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record PromptTokensDetails(@JsonProperty("cached_tokens") @Nullable Integer cachedTokens) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record CompletionTokensDetails(@JsonProperty("reasoning_tokens") @Nullable Integer reasoningTokens) {
	}
}
