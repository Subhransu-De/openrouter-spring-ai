package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record Usage(@JsonAlias("input_tokens") @JsonProperty("prompt_tokens") Integer promptTokens,
		@JsonAlias("output_tokens") @JsonProperty("completion_tokens") Integer completionTokens,
		@JsonProperty("total_tokens") Integer totalTokens,
		@JsonAlias("cached_tokens") @JsonProperty("cache_read_input_tokens") Integer cachedTokens,
		@JsonProperty("reasoning_tokens") Integer reasoningTokens, Double cost,
		@JsonAlias("input_tokens_details") @JsonProperty("prompt_tokens_details") PromptTokensDetails promptTokensDetails,
		@JsonAlias("output_tokens_details") @JsonProperty("completion_tokens_details") CompletionTokensDetails completionTokensDetails,
		Map<String, Object> details) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record PromptTokensDetails(@JsonProperty("cached_tokens") Integer cachedTokens) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record CompletionTokensDetails(@JsonProperty("reasoning_tokens") Integer reasoningTokens) {
	}
}
