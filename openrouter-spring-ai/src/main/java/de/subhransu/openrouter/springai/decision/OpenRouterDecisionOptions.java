package de.subhransu.openrouter.springai.decision;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.support.OptionChecks;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DefaultDecisionOptions;

/**
 * Options for OpenRouter's Decisions API ({@code POST /api/alpha/decisions}). Mirrors the
 * request fields {@code model}, {@code provider}, {@code user}, {@code session_id} and
 * {@code trace}.
 *
 * <p>
 * Options given in a prompt override the model's defaults through
 * {@link Builder#combineWith(DecisionOptions.Builder)}: each value they set replaces the
 * default, and a {@code provider} or {@code trace} replaces it as a whole. Portable
 * {@link DecisionOptions} carry over only the model.
 *
 * <p>
 * <strong>Experimental:</strong> the Decisions API is in alpha and its shape may change,
 * so these options may change in a minor release.
 *
 * @author Subhransu De
 */
public final class OpenRouterDecisionOptions implements DecisionOptions {

	private final @Nullable String model;

	private final @Nullable OpenRouterProviderPreferences provider;

	private final @Nullable String user;

	private final @Nullable String sessionId;

	private final @Nullable OpenRouterTrace trace;

	private OpenRouterDecisionOptions(@Nullable String model, @Nullable OpenRouterProviderPreferences provider,
			@Nullable String user, @Nullable String sessionId, @Nullable OpenRouterTrace trace) {
		this.model = model;
		this.provider = provider;
		this.user = user;
		this.sessionId = sessionId;
		this.trace = trace;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public Builder mutate() {
		return builder().model(this.model)
			.provider(this.provider)
			.user(this.user)
			.sessionId(this.sessionId)
			.trace(this.trace);
	}

	/**
	 * Return the decision model, such as {@code typesafe/jev-1.14}.
	 * @return the model, or {@code null} when unset; a request needs one
	 */
	@Override
	public @Nullable String getModel() {
		return this.model;
	}

	public @Nullable OpenRouterProviderPreferences getProvider() {
		return this.provider;
	}

	public @Nullable String getUser() {
		return this.user;
	}

	public @Nullable String getSessionId() {
		return this.sessionId;
	}

	public @Nullable OpenRouterTrace getTrace() {
		return this.trace;
	}

	public static final class Builder extends DefaultDecisionOptions.Builder<Builder> {

		private @Nullable OpenRouterProviderPreferences provider;

		private @Nullable String user;

		private @Nullable String sessionId;

		private @Nullable OpenRouterTrace trace;

		private Builder() {
		}

		/**
		 * Set the provider routing preferences, sent as {@code provider}.
		 * @param provider the preferences, or {@code null}
		 * @return this builder
		 */
		public Builder provider(@Nullable OpenRouterProviderPreferences provider) {
			this.provider = provider;
			return this;
		}

		/**
		 * End-user identifier for OpenRouter's analytics and rate limits, sent as
		 * {@code user}.
		 */
		public Builder user(@Nullable String user) {
			this.user = user;
			return this;
		}

		/**
		 * Groups related requests for observability, sent as {@code session_id}. At most
		 * 256 characters.
		 */
		public Builder sessionId(@Nullable String sessionId) {
			this.sessionId = OptionChecks.sessionId(sessionId);
			return this;
		}

		/**
		 * Observability metadata, sent as {@code trace}. A per-request trace replaces the
		 * default trace as a whole.
		 */
		public Builder trace(@Nullable OpenRouterTrace trace) {
			this.trace = trace;
			return this;
		}

		@Override
		public Builder combineWith(DecisionOptions.Builder<?> other) {
			super.combineWith(other);
			if (other instanceof Builder that) {
				if (that.provider != null) {
					this.provider = that.provider;
				}
				if (that.user != null) {
					this.user = that.user;
				}
				if (that.sessionId != null) {
					this.sessionId = that.sessionId;
				}
				if (that.trace != null) {
					this.trace = that.trace;
				}
			}
			return this;
		}

		@Override
		public OpenRouterDecisionOptions build() {
			return new OpenRouterDecisionOptions(this.model, this.provider, this.user, this.sessionId, this.trace);
		}

	}

}
