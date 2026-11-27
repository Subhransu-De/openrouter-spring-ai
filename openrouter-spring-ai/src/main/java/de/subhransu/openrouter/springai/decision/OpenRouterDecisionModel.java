package de.subhransu.openrouter.springai.decision;

import de.subhransu.openrouter.springai.OpenRouterIdentifiers;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.decision.mapper.OpenRouterDecisionRequestMapper;
import de.subhransu.openrouter.springai.decision.mapper.OpenRouterDecisionResponseMapper;
import de.subhransu.openrouter.springai.internal.Retries;
import io.micrometer.observation.ObservationRegistry;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionOptions;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.observation.DecisionModelObservationContext;
import org.springframework.ai.decision.observation.DecisionModelObservationConvention;
import org.springframework.ai.decision.observation.DecisionModelObservationDocumentation;
import org.springframework.ai.decision.observation.DefaultDecisionModelObservationConvention;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.Assert;

/**
 * OpenRouter {@link DecisionModel} backed by the Decisions API
 * ({@code POST /api/alpha/decisions}).
 *
 * <p>
 * A {@code YesNoQuestion} is asked as a {@code noul}, a {@code ChoiceQuestion} as a
 * {@code choice} and a {@code ScoreQuestion} as a {@code score}; the answers come back
 * with the probabilities and confidence OpenRouter reports, and a question the provider
 * declines is answered with a {@code RefusalAnswer}. OpenRouter's {@code box} question
 * has no Spring AI counterpart, so it cannot be asked. Usage and cost are in
 * {@link de.subhransu.openrouter.springai.chat.OpenRouterUsage#from(DecisionResponse)}.
 *
 * <p>
 * A text input is sent as a string, fields as a JSON object and a conversation as its
 * messages. Images are sent as {@code image_url} parts, and only in a content input,
 * because OpenRouter reads images only at the top level of the request state. An answer
 * that does not fit its question fails with an
 * {@link de.subhransu.openrouter.springai.errors.OpenRouterProtocolException}.
 *
 * <p>
 * <strong>Experimental:</strong> OpenRouter's Decisions API is in alpha and its shape may
 * change, so this model may change in a minor release.
 *
 * @author Subhransu De
 */
public class OpenRouterDecisionModel implements DecisionModel {

	private static final DecisionModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultDecisionModelObservationConvention();

	private final OpenRouterApi openRouterApi;

	private final OpenRouterDecisionOptions defaultOptions;

	private final RetryTemplate retryTemplate;

	private final ObservationRegistry observationRegistry;

	private DecisionModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

	private final OpenRouterDecisionRequestMapper requestMapper = new OpenRouterDecisionRequestMapper();

	private final OpenRouterDecisionResponseMapper responseMapper = new OpenRouterDecisionResponseMapper();

	private OpenRouterDecisionModel(Builder builder) {
		Assert.notNull(builder.openRouterApi, "OpenRouterApi must not be null");
		this.openRouterApi = builder.openRouterApi;
		this.defaultOptions = builder.defaultOptions != null ? builder.defaultOptions.mutate().build()
				: OpenRouterDecisionOptions.builder().build();
		this.retryTemplate = builder.retryTemplate != null ? builder.retryTemplate : RetryUtils.DEFAULT_RETRY_TEMPLATE;
		this.observationRegistry = builder.observationRegistry != null ? builder.observationRegistry
				: ObservationRegistry.NOOP;
	}

	@Override
	public DecisionResponse call(DecisionPrompt prompt) {
		Assert.notNull(prompt, "prompt must not be null");
		OpenRouterDecisionOptions options = buildRequestOptions(prompt.getOptions());
		// The observation sees the merged options, so the model name is always recorded.
		DecisionModelObservationContext observationContext = DecisionModelObservationContext.builder()
			.decisionPrompt(prompt.mutate().options(options).build())
			.provider(OpenRouterIdentifiers.PROVIDER_ID)
			.build();
		return DecisionModelObservationDocumentation.DECISION_MODEL_OPERATION
			.observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
					this.observationRegistry)
			.observe(() -> {
				var request = this.requestMapper.map(prompt, options);
				DecisionResponse response = Retries.invoke(this.retryTemplate,
						() -> this.responseMapper.map(prompt, this.openRouterApi.decisions(request)));
				observationContext.setResponse(response);
				return response;
			});
	}

	/**
	 * Returns a copy of this model's default options, the starting point for per-request
	 * options built with {@code mutate()}.
	 * @return the default options
	 */
	public OpenRouterDecisionOptions getOptions() {
		return this.defaultOptions.mutate().build();
	}

	public void setObservationConvention(DecisionModelObservationConvention observationConvention) {
		Assert.notNull(observationConvention, "observationConvention must not be null");
		this.observationConvention = observationConvention;
	}

	private OpenRouterDecisionOptions buildRequestOptions(@Nullable DecisionOptions runtimeOptions) {
		OpenRouterDecisionOptions.Builder builder = this.defaultOptions.mutate();
		if (runtimeOptions != null) {
			builder.combineWith(runtimeOptions.mutate());
		}
		return builder.build();
	}

	public static Builder builder() {
		return new Builder();
	}

	public static final class Builder {

		private @Nullable OpenRouterApi openRouterApi;

		private @Nullable OpenRouterDecisionOptions defaultOptions;

		private @Nullable RetryTemplate retryTemplate;

		private @Nullable ObservationRegistry observationRegistry;

		private Builder() {
		}

		public Builder openRouterApi(OpenRouterApi openRouterApi) {
			this.openRouterApi = openRouterApi;
			return this;
		}

		/**
		 * Set the default options of the model. Options in a prompt override them.
		 * @param options the default options, or {@code null} for the built-in defaults
		 * @return this builder
		 */
		public Builder options(@Nullable OpenRouterDecisionOptions options) {
			this.defaultOptions = options;
			return this;
		}

		public Builder retryTemplate(@Nullable RetryTemplate retryTemplate) {
			this.retryTemplate = retryTemplate;
			return this;
		}

		public Builder observationRegistry(@Nullable ObservationRegistry observationRegistry) {
			this.observationRegistry = observationRegistry;
			return this;
		}

		public OpenRouterDecisionModel build() {
			return new OpenRouterDecisionModel(this);
		}

	}

}
