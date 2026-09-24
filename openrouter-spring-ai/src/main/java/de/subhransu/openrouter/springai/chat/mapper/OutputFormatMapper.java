package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import org.springframework.util.StringUtils;

final class OutputFormatMapper {

	private final ObjectMapper objectMapper;

	OutputFormatMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Nullable ObjectNode map(OpenRouterChatOptions options) {
		var format = options.getResponseFormat();
		if (format != null) {
			return mapResponseFormat(format);
		}
		if (StringUtils.hasText(options.getOutputSchema())) {
			return jsonSchemaFormat("response", null, options.getOutputSchema());
		}
		return null;
	}

	private ObjectNode mapResponseFormat(OpenRouterResponseFormat format) {
		return switch (format.type()) {
			case TEXT -> this.objectMapper.createObjectNode().put("type", "text");
			case JSON_OBJECT -> this.objectMapper.createObjectNode().put("type", "json_object");
			case JSON_SCHEMA -> jsonSchemaFormat(StringUtils.hasText(format.name()) ? format.name() : "response",
					format.strict(), format.schema());
		};
	}

	private ObjectNode jsonSchemaFormat(String name, @Nullable Boolean strict, @Nullable String schema) {
		ObjectNode jsonSchema = this.objectMapper.createObjectNode().put("name", name);
		if (strict != null) {
			jsonSchema.put("strict", strict);
		}
		try {
			jsonSchema.set("schema", this.objectMapper.readTree(schema));
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("Invalid JSON schema", ex);
		}
		ObjectNode node = this.objectMapper.createObjectNode().put("type", "json_schema");
		node.set("json_schema", jsonSchema);
		return node;
	}

}
