package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins usage mapping rules that are specific to {@link UsageMapper}: flat fields win over
 * nested details (including zero), null nested values, cost and the null-usage case.
 * Nested accounting and native-payload identity across every mapper are pinned in
 * {@link UsageWireMappingTests}.
 */
class UsageMapperTests {

	@ParameterizedTest(name = "top-level cached={0}, reasoning={1}")
	@CsvSource({ "2,1", "0,0" })
	void flatFieldsTakePrecedenceOverNestedDetailsEvenWhenZero(int cached, int reasoning) {
		Usage source = new Usage(10, 5, 15, cached, reasoning, null, new Usage.PromptTokensDetails(99),
				new Usage.CompletionTokensDetails(88), null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getCachedTokens()).isEqualTo(cached);
		assertThat(usage.getCacheReadInputTokens()).isEqualTo(cached);
		assertThat(usage.getReasoningTokens()).isEqualTo(reasoning);
		assertThat(usage.getCacheWriteInputTokens()).isNull();
	}

	@Test
	void nestedDetailObjectWithNullInnerValuesYieldsNullCounts() {
		Usage source = new Usage(10, 5, 15, null, null, null, new Usage.PromptTokensDetails(null),
				new Usage.CompletionTokensDetails(null), null);

		OpenRouterUsage usage = UsageMapper.map(source);

		assertThat(usage.getCachedTokens()).isNull();
		assertThat(usage.getReasoningTokens()).isNull();
	}

	@ParameterizedTest(name = "cost={0}")
	@CsvSource(value = { "NIL", "0.0", "0.003" }, nullValues = "NIL")
	void costIsCopiedAndAnAbsentCostStaysNullInsteadOfZero(Double cost) {
		OpenRouterUsage usage = UsageMapper.map(new Usage(10, 5, 15, null, null, cost, null, null, null));

		assertThat(usage.getCost()).isEqualTo(cost);
	}

	@Test
	void nullUsageMapsToNull() {
		assertThat(UsageMapper.map(null)).isNull();
	}

}
