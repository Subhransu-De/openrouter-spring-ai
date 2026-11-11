package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenRouterReasoningOptionsTests {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void rejectsMutuallyExclusiveControls() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new OpenRouterReasoningOptions("medium", 256, false, true))
			.withMessage("OpenRouter reasoning accepts 'effort' or 'max-tokens', not both");
	}

	@Test
	void rejectsBlankEffort() {
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterReasoningOptions(" \t", null, false, true))
			.withMessage("reasoning 'effort' must not be blank");
	}

	@ParameterizedTest(name = "max-tokens={0}")
	@ValueSource(ints = { 0, -1 })
	void rejectsNonPositiveTokenBudget(int budget) {
		assertThatIllegalArgumentException().isThrownBy(() -> new OpenRouterReasoningOptions(null, budget, false, true))
			.withMessage("reasoning 'max-tokens' must be positive, but was " + budget);
	}

	static Stream<Arguments> wireShapes() {
		return Stream.of(true, false)
			.flatMap(responses -> Stream.of(
					Arguments.of(Named.of(wireName(responses, "provider effort kept verbatim"), responses),
							new OpenRouterReasoningOptions(" future-effort ", null, null, null),
							"{\"effort\":\" future-effort \"}"),
					Arguments.of(Named.of(wireName(responses, "exclude only"), responses),
							new OpenRouterReasoningOptions(null, null, true, null), "{\"exclude\":true}"),
					Arguments.of(Named.of(wireName(responses, "enabled only"), responses),
							new OpenRouterReasoningOptions(null, null, null, false), "{\"enabled\":false}"),
					Arguments.of(Named.of(wireName(responses, "token budget with switches"), responses),
							new OpenRouterReasoningOptions(null, 256, false, true),
							"{\"max_tokens\":256,\"exclude\":false,\"enabled\":true}")));
	}

	private static String wireName(boolean responses, String shape) {
		return (responses ? "responses: " : "chat completions: ") + shape;
	}

	@ParameterizedTest
	@MethodSource("wireShapes")
	void controlShapesReachTheWireWithoutAddedFields(boolean responses, OpenRouterReasoningOptions reasoning,
			String expectedWire) {
		var options = OpenRouterChatOptions.builder().reasoning(reasoning).build();
		Object request = responses
				? new OpenRouterResponsesRequestMapper(this.json).map(List.of(), options, false, List.of())
				: new OpenRouterChatRequestMapper(this.json).map(List.of(), options, false, List.of());

		JsonNode wire = this.json.valueToTree(request).path("reasoning");

		assertThat(wire).isEqualTo(this.json.readTree(expectedWire));
	}

}
