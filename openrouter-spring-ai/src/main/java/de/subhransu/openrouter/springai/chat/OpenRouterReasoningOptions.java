package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.OptionChecks;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

public record OpenRouterReasoningOptions(@Nullable String effort, @Nullable Integer maxTokens,
		@Nullable Boolean exclude, @Nullable Boolean enabled) {

	private static final Log logger = LogFactory.getLog(OpenRouterReasoningOptions.class);

	public OpenRouterReasoningOptions {
		if (effort != null && maxTokens != null) {
			throw new IllegalArgumentException(
					"OpenRouter reasoning accepts 'effort' or 'max-tokens', not both, but effort was "
							+ OptionChecks.describe(effort) + " and max-tokens was " + maxTokens);
		}
		if (effort != null && effort.isBlank()) {
			throw new IllegalArgumentException("reasoning 'effort' must not be blank, but was "
					+ OptionChecks.describe(effort) + "; use " + String.join(", ", OpenRouterReasoningEffort.VALUES));
		}
		if (maxTokens != null && maxTokens <= 0) {
			throw new IllegalArgumentException("reasoning 'max-tokens' must be positive, but was " + maxTokens);
		}
		OptionChecks.warnIfUnknown(logger, "reasoning effort", effort, OpenRouterReasoningEffort.VALUES);
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

		/**
		 * Sets the effort as a wire value, which also covers values that OpenRouter adds
		 * after this release. An undocumented value is sent and logged once as a warning.
		 */
		public Builder effort(@Nullable String effort) {
			this.effort = effort;
			return this;
		}

		public Builder effort(@Nullable OpenRouterReasoningEffort effort) {
			this.effort = effort != null ? effort.value() : null;
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
