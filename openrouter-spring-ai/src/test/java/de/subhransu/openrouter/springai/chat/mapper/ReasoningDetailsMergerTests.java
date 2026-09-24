package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReasoningDetailsMergerTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest
	@ValueSource(strings = { "null", "42", "{}", "[]", "true" })
	void nonTextPayloadsArePreservedWithoutDiscardingProviderData(String payload) {
		JsonNode text = this.mapper.readTree("{\"type\":\"reasoning.text\",\"text\":\"first\"}");
		JsonNode other = this.mapper.readTree("{\"type\":\"reasoning.text\",\"text\":" + payload + "}");
		if (other.path("text").isNull()) {
			assertThat(ReasoningDetailsMerger.merge(List.of(text), List.of(other))).containsExactly(text);
			assertThat(ReasoningDetailsMerger.merge(List.of(other), List.of(text))).containsExactly(text);
		}
		else {
			assertThat(ReasoningDetailsMerger.merge(List.of(text), List.of(other))).containsExactly(text, other);
			assertThat(ReasoningDetailsMerger.merge(List.of(other), List.of(text))).containsExactly(other, text);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"id\":\"different\"", "\"index\":1", "\"signature\":\"different\"",
			"\"future\":{\"flag\":false}" })
	void conflictingIdentityOrOpaqueMetadataStartsANewBlock(String conflict) {
		JsonNode first = this.mapper.readTree("""
				{"type":"reasoning.text","text":"first","id":"original","index":0,
				 "signature":"original","future":{"flag":true}}
				""");
		JsonNode next = this.mapper.readTree("{\"type\":\"reasoning.text\",\"text\":\"next\"," + conflict + "}");
		assertThat(ReasoningDetailsMerger.merge(List.of(first), List.of(next))).containsExactly(first, next);
	}

	@Test
	void mergedMetadataIsCopiedAndNullFragmentsDoNotEraseKnownValues() {
		JsonNode first = this.mapper.readTree("""
				{"type":"reasoning.summary","summary":"first","signature":"known"}
				""");
		JsonNode next = this.mapper.readTree("""
				{"type":"reasoning.summary","summary":"next","signature":null,"future":{"flag":true}}
				""");
		List<JsonNode> merged = ReasoningDetailsMerger.merge(List.of(first), List.of(next));
		assertThat(merged).containsExactly(this.mapper.readTree("""
				{"type":"reasoning.summary","summary":"firstnext","signature":"known","future":{"flag":true}}
				"""));
		((ObjectNode) next.path("future")).put("flag", false);
		assertThat(merged.get(0).path("future").path("flag").asBoolean()).isTrue();
		assertThat(first.path("summary").asString()).isEqualTo("first");
		assertThat(first.has("future")).isFalse();
	}

}
