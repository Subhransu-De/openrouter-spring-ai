package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionModel;
import de.subhransu.openrouter.springai.decision.OpenRouterDecisionOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.decision.DecisionModel;
import org.springframework.ai.decision.DecisionPrompt;
import org.springframework.ai.decision.DecisionResponse;
import org.springframework.ai.decision.observation.DecisionModelObservationConvention;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

@Tag("configuration")
@Tag("configuration.beans")
class OpenRouterDecisionAutoConfigurationTests {

	private static final String API_KEY = "spring.ai.openrouter.api-key=test-key";

	private static final String DECISION = "spring.ai.openrouter.decision.";

	private static final String SELECT_DECISION = "spring.ai.model.decision=openrouter";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
			AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterDecisionAutoConfiguration.class));

	@Test
	void theDecisionModelIsOptIn() {
		this.contextRunner.withPropertyValues(API_KEY)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.doesNotHaveBean(DecisionModel.class));
		this.contextRunner.withPropertyValues(API_KEY, SELECT_DECISION).run(context -> {
			assertThat(context).hasNotFailed().hasSingleBean(OpenRouterDecisionModel.class);
			assertThat(context.getBean(DecisionModel.class)).isInstanceOf(OpenRouterDecisionModel.class);
		});
	}

	@Test
	void anotherProvidersDecisionModelIsNotDuplicatedWhenTheSelectorIsUnset() {
		// The stand-in behaves like Spring AI's own decision providers: it matches an
		// unset selector and backs off only for its own type.
		this.contextRunner.withConfiguration(AutoConfigurations.of(OtherProviderDecisionAutoConfiguration.class))
			.withPropertyValues(API_KEY)
			.run(context -> {
				assertThat(context).hasNotFailed().hasSingleBean(DecisionModel.class);
				assertThat(context.getBean(DecisionModel.class)).isInstanceOf(OtherDecisionModel.class);
			});
	}

	@Test
	void anApplicationDecisionModelWins() {
		DecisionModel decisionModel = mock(DecisionModel.class);
		this.contextRunner.withPropertyValues(API_KEY, SELECT_DECISION)
			.withBean(DecisionModel.class, () -> decisionModel)
			.run(context -> {
				assertThat(context).hasNotFailed().doesNotHaveBean(OpenRouterDecisionModel.class);
				assertThat(context.getBean(DecisionModel.class)).isSameAs(decisionModel);
			});
	}

	@Test
	void anotherSelectedProviderTurnsTheModelOff() {
		this.contextRunner.withPropertyValues(API_KEY, "spring.ai.model.decision=other")
			.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DecisionModel.class));
	}

	@Test
	void aDecisionSelectionAloneCreatesTheClient() {
		ApplicationContextRunner otherModelsOff = this.contextRunner.withPropertyValues("spring.ai.model.chat=none",
				"spring.ai.model.embedding=none", "spring.ai.model.image=none");
		otherModelsOff.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(OpenRouterApi.class));
		otherModelsOff.withPropertyValues(API_KEY, SELECT_DECISION)
			.run(context -> assertThat(context).hasNotFailed()
				.hasSingleBean(OpenRouterApi.class)
				.hasSingleBean(DecisionModel.class));
	}

	@Test
	void anObservationConventionBeanIsApplied() {
		DecisionModelObservationConvention convention = mock(DecisionModelObservationConvention.class);
		this.contextRunner.withPropertyValues(API_KEY, SELECT_DECISION)
			.withBean(DecisionModelObservationConvention.class, () -> convention)
			.run(context -> assertThat(context.getBean(OpenRouterDecisionModel.class))
				.extracting("observationConvention")
				.isSameAs(convention));
	}

	@Tag("configuration.properties")
	@Test
	void bindsDecisionPropertiesIntoModelDefaults() {
		this.contextRunner
			.withPropertyValues(API_KEY, SELECT_DECISION, DECISION + "model=typesafe/jev-1.14",
					DECISION + "user=synthetic-user", DECISION + "session-id=garage-session",
					DECISION + "trace.trace-name=garage", DECISION + "trace.metadata.bay=3",
					DECISION + "provider.order=typesafe", DECISION + "provider.data-collection=deny")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterDecisionOptions options = context.getBean(OpenRouterDecisionModel.class).getOptions();
				assertThat(options.getModel()).isEqualTo("typesafe/jev-1.14");
				assertThat(options.getUser()).isEqualTo("synthetic-user");
				assertThat(options.getSessionId()).isEqualTo("garage-session");
				assertThat(options.getTrace())
					.isEqualTo(OpenRouterTrace.builder().traceName("garage").metadata(Map.of("bay", "3")).build());
				assertThat(options.getProvider()).isEqualTo(OpenRouterProviderPreferences.builder()
					.order(List.of("typesafe"))
					.dataCollection("deny")
					.build());
			});
	}

	@Tag("configuration.properties")
	@Test
	void anOverlongSessionIdFailsAtStartup() {
		this.contextRunner.withPropertyValues(API_KEY, SELECT_DECISION, DECISION + "session-id=" + "s".repeat(257))
			.run(context -> assertThat(context).hasFailed());
	}

	@AutoConfiguration(after = OpenRouterDecisionAutoConfiguration.class)
	static class OtherProviderDecisionAutoConfiguration {

		@Bean
		@ConditionalOnProperty(name = "spring.ai.model.decision", havingValue = "other", matchIfMissing = true)
		@ConditionalOnMissingBean(OtherDecisionModel.class)
		OtherDecisionModel otherDecisionModel() {
			return new OtherDecisionModel();
		}

	}

	static class OtherDecisionModel implements DecisionModel {

		@Override
		public DecisionResponse call(DecisionPrompt prompt) {
			throw new UnsupportedOperationException("stand-in");
		}

	}

}
