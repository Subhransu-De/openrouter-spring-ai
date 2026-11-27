package de.subhransu.openrouter.springai.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.decision.DecisionOptions;

@Tag("decision")
@Tag("decision.options")
class OpenRouterDecisionOptionsTests {

	private static final String MODEL = "typesafe/jev-1.14";

	private static final String USER = "user-1";

	private static final OpenRouterProviderPreferences PROVIDER = OpenRouterProviderPreferences.builder()
		.dataCollection("deny")
		.build();

	private static final OpenRouterTrace TRACE = OpenRouterTrace.builder().traceId("run-1").build();

	@Test
	void mutateCopiesEveryValue() {
		OpenRouterDecisionOptions options = full();

		OpenRouterDecisionOptions copy = options.mutate().build();

		assertThat(copy).isNotSameAs(options);
		assertThat(copy.getModel()).isEqualTo(MODEL);
		assertThat(copy.getProvider()).isEqualTo(PROVIDER);
		assertThat(copy.getUser()).isEqualTo(USER);
		assertThat(copy.getSessionId()).isEqualTo("session-1");
		assertThat(copy.getTrace()).isEqualTo(TRACE);
	}

	@Test
	void combineWithReplacesOnlyTheValuesTheOtherBuilderSets() {
		OpenRouterProviderPreferences other = OpenRouterProviderPreferences.builder().sort("price").build();

		OpenRouterDecisionOptions merged = full().mutate()
			.combineWith(OpenRouterDecisionOptions.builder().provider(other).sessionId("session-2"))
			.build();

		assertThat(merged.getModel()).isEqualTo(MODEL);
		assertThat(merged.getProvider()).isEqualTo(other);
		assertThat(merged.getSessionId()).isEqualTo("session-2");
		assertThat(merged.getUser()).isEqualTo(USER);
		assertThat(merged.getTrace()).isEqualTo(TRACE);
	}

	@Test
	void portableOptionsCarryOverOnlyTheModel() {
		OpenRouterDecisionOptions merged = full().mutate()
			.combineWith(DecisionOptions.builder().model("liquid/d1.2"))
			.build();

		assertThat(merged.getModel()).isEqualTo("liquid/d1.2");
		assertThat(merged.getUser()).isEqualTo(USER);
		assertThat(full().mutate().combineWith(DecisionOptions.builder()).build().getModel()).isEqualTo(MODEL);
	}

	@Test
	void sessionIdIsAtMost256Characters() {
		assertThatThrownBy(() -> OpenRouterDecisionOptions.builder().sessionId("s".repeat(257)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static OpenRouterDecisionOptions full() {
		return OpenRouterDecisionOptions.builder()
			.model(MODEL)
			.provider(PROVIDER)
			.user(USER)
			.sessionId("session-1")
			.trace(TRACE)
			.build();
	}

}
