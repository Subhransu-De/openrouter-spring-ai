package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

class ToolSchemaValidatorTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void validatesRecursiveReferencesWithoutRewritingSchemasOrInstanceData() {
		String schema = """
				{"type":"object","properties":{"next":{"anyOf":[{"$ref":"#"},{"type":"null"}]}},
				 "required":["next"],"additionalProperties":false,
				 "default":{"type":"object","properties":{"arbitrary":true}}}
				""";
		ToolDefinition tool = tool(schema);
		var parsed = ToolSchemaValidator.read(this.mapper, tool, true);
		assertThat(parsed).isEqualTo(this.mapper.readTree(schema));
		assertThat(tool.inputSchema()).isEqualTo(schema);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"type\":\"object\",\"properties\":{},\"required\":[]}",
			"{\"type\":\"object\",\"properties\":{\"optional\":{\"type\":\"string\"}},\"required\":[],\"additionalProperties\":false}",
			"{\"type\":\"object\",\"properties\":{},\"required\":[\"missing\"],\"additionalProperties\":false}",
			"{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":true}",
			"{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":{\"type\":\"string\"}}" })
	void rejectsOpenObjectsAndOptionalOrUnknownRequiredProperties(String schema) {
		assertThatThrownBy(() -> ToolSchemaValidator.read(this.mapper, tool(schema), true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid strict schema for tool 'synthetic'");
		for (Boolean strict : new Boolean[] { null, false }) {
			assertThat(ToolSchemaValidator.read(this.mapper, tool(schema), strict))
				.isEqualTo(this.mapper.readTree(schema));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"$ref\":null}", "{\"type\":\"object\"}", "{\"type\":[\"object\",\"null\"]}",
			"{\"type\":\"array\",\"items\":{\"type\":\"object\"}}", "{\"type\":\"array\"}",
			"{\"$ref\":\"#/$defs/missing\"}", "{\"$ref\":\"https://schemas.test/external\"}",
			"{\"$defs\":{\"nested\":{\"type\":\"object\"}},\"$ref\":\"#/$defs/nested\"}",
			"{\"anyOf\":[{\"type\":\"object\"},{\"type\":\"null\"}]}", "{\"allOf\":[{\"type\":\"string\"}]}", "true" })
	void checksNestedArraysDefinitionsReferencesAndUnsupportedComposition(String child) {
		String schema = """
				{"type":"object","properties":{"value":%s},"required":["value"],"additionalProperties":false}
				""".formatted(child);
		assertThatThrownBy(() -> ToolSchemaValidator.read(this.mapper, tool(schema), true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid strict schema");
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "[]", "{\"type\":\"array\"}", "{\"anyOf\":[]}" })
	void rejectsNonObjectRoot(String schema) {
		assertThatThrownBy(() -> ToolSchemaValidator.read(this.mapper, tool(schema), true))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("parameters must have type object");
	}

	private static ToolDefinition tool(String schema) {
		return ToolDefinition.builder().name("synthetic").description("Synthetic tool").inputSchema(schema).build();
	}

}
