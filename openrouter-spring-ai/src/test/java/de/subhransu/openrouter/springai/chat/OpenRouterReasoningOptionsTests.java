package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("api.reasoning")
class OpenRouterReasoningOptionsTests {

	@Test
	void rejectsMutuallyExclusiveControls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterReasoningOptions("medium", 256, false, true))
			.withMessageContaining("effort")
			.withMessageContaining("max-tokens");
	}

	@Test
	void rejectsBlankEffort() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterReasoningOptions(" \t", null, false, true))
			.withMessageContaining("must not be blank");
	}

	@Test
	void rejectsNonPositiveTokenBudget() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterReasoningOptions(null, 0, false, true))
			.withMessageContaining("0");
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterReasoningOptions(null, -1, false, true))
			.withMessageContaining("-1");
	}

	@Test
	void acceptsEveryValidControlShape() {
		assertThat(new OpenRouterReasoningOptions("medium", null, false, true).effort()).isEqualTo("medium");
		assertThat(new OpenRouterReasoningOptions(null, 256, false, true).maxTokens()).isEqualTo(256);
		assertThat(new OpenRouterReasoningOptions(null, null, false, true).enabled()).isTrue();
		assertThat(new OpenRouterReasoningOptions(null, null, true, null).exclude()).isTrue();
	}

	@Test
	void preservesProviderEffortValuesVerbatim() {
		OpenRouterReasoningOptions reasoning = new OpenRouterReasoningOptions(" future-effort ", null, false, true);

		assertThat(reasoning.effort()).isEqualTo(" future-effort ");
	}

	@Test
	void builderSetsNamedControlsAndLeavesTheRestUnset() {
		assertThat(OpenRouterReasoningOptions.builder().effort("high").build())
			.isEqualTo(new OpenRouterReasoningOptions("high", null, null, null));
		assertThat(OpenRouterReasoningOptions.builder().maxTokens(2048).exclude(true).enabled(true).build())
			.isEqualTo(new OpenRouterReasoningOptions(null, 2048, true, true));
	}

	@Test
	void builderAppliesTheSameValidation() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterReasoningOptions.builder().effort("high").maxTokens(256).build())
			.withMessageContaining("not both");
	}

}
