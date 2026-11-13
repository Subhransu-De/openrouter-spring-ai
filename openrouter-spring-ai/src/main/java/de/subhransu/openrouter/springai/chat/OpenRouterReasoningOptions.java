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

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable String effort;

		private @Nullable Integer maxTokens;

		private @Nullable Boolean exclude;

		private @Nullable Boolean enabled;

		private Builder() {
		}

		public Builder effort(@Nullable String effort) {
			this.effort = effort;
			return this;
		}

		public Builder maxTokens(@Nullable Integer maxTokens) {
			this.maxTokens = maxTokens;
			return this;
		}

		public Builder exclude(@Nullable Boolean exclude) {
			this.exclude = exclude;
			return this;
		}

		public Builder enabled(@Nullable Boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		public OpenRouterReasoningOptions build() {
			return new OpenRouterReasoningOptions(this.effort, this.maxTokens, this.exclude, this.enabled);
		}

	}

}
