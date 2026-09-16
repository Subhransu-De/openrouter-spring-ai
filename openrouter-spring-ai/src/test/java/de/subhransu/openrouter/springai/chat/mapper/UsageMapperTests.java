package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import org.junit.jupiter.api.Test;

/**
 * Pins usage mapping: flat versus nested cached/reasoning token sources, their
 * precedence, null handling, cost, and the null-usage case.
 */
class UsageMapperTests {

	@Test
	void mapsFlatUsageFields() {
		OpenRouterUsage usage = UsageMapper.map(new Usage(10, 5, 15, 2, 1, 0.001, null, null, null));

		assertThat(usage.getPromptTokens()).isEqualTo(10);
		assertThat(usage.getCompletionTokens()).isEqualTo(5);
		assertThat(usage.getTotalTokens()).isEqualTo(15);
		assertThat(usage.getCachedTokens()).isEqualTo(2);
		assertThat(usage.getReasoningTokens()).isEqualTo(1);
		assertThat(usage.getCost()).isEqualTo(0.001);
	}

	@Test
	void mapsNestedDetailFields() {
		Usage source = new Usage(10, 5, 15, null, null, null, new Usage.PromptTokensDetails(3),
				new Usage.CompletionTokensDetails(4), null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getCachedTokens()).isEqualTo(3);
		assertThat(usage.getReasoningTokens()).isEqualTo(4);
	}

	@Test
	void flatFieldsTakePrecedenceOverNestedDetails() {
		// Top-level cached/reasoning tokens win over the nested detail objects when both
		// are present.
		Usage source = new Usage(10, 5, 15, 2, 1, null, new Usage.PromptTokensDetails(99),
				new Usage.CompletionTokensDetails(88), null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getCachedTokens()).isEqualTo(2);
		assertThat(usage.getReasoningTokens()).isEqualTo(1);
	}

	@Test
	void nestedDetailObjectWithNullInnerValuesYieldsNullCounts() {
		Usage source = new Usage(10, 5, 15, null, null, null, new Usage.PromptTokensDetails(null),
				new Usage.CompletionTokensDetails(null), null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getCachedTokens()).isNull();
		assertThat(usage.getReasoningTokens()).isNull();
	}

	@Test
	void nullUsageMapsToNull() {
		assertThat(UsageMapper.map(null)).isNull();
	}

	@Test
	void explicitTopLevelZeroTakesPrecedenceOverNestedCounts() {
		OpenRouterUsage usage = UsageMapper.map(new Usage(10, 5, 15, 0, 0, null, new Usage.PromptTokensDetails(3),
				new Usage.CompletionTokensDetails(2), null));
		assertThat(usage.getCachedTokens()).isZero();
		assertThat(usage.getCacheReadInputTokens()).isZero();
		assertThat(usage.getReasoningTokens()).isZero();
		assertThat(usage.getCacheWriteInputTokens()).isNull();
	}

	@Test
	void preservesNativeUsagePayload() {
		Usage source = new Usage(10, 5, 15, null, null, null, null, null, null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getNativeUsage()).isSameAs(source);
	}

}
