package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Pins the normalization of OpenRouter / OpenAI finish-reason strings to Spring AI's
 * portable values. Chat-completions and responses-mode finish reasons both flow through
 * {@link FinishReasonMapper}, so a regression in either contract fails here.
 */
@Tag("api")
@Tag("api.response")
class FinishReasonMapperTests {

	@ParameterizedTest(name = "[{index}] \"{0}\" -> \"{1}\"")
	@CsvSource({ "stop, STOP", "completed, STOP", "length, LENGTH", "max_output_tokens, LENGTH",
			"tool_calls, TOOL_CALLS", "function_call, TOOL_CALLS", "content_filter, CONTENT_FILTER" })
	void mapsKnownFinishReasonsToPortableValues(String input, String expected) {
		assertThat(FinishReasonMapper.map(input)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] unknown \"{0}\" passes through verbatim")
	@CsvSource({ "failed", "incomplete", "error", "guardrail_triggered" })
	void passesUnknownFinishReasonsThroughVerbatim(String input) {
		// Unknown provider-specific reasons are surfaced unchanged so callers can react
		// to
		// new states without a library upgrade.
		assertThat(FinishReasonMapper.map(input)).isEqualTo(input);
	}

	@Test
	void nullInputPassesThrough() {
		assertThat(FinishReasonMapper.map(null)).isNull();
	}

	@Test
	void blankInputPassesThrough() {
		assertThat(FinishReasonMapper.map("   ")).isEqualTo("   ");
	}

}
