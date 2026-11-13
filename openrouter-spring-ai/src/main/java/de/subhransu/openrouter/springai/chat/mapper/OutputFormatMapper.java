package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.support.OptionChecks;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import org.springframework.util.StringUtils;

final class OutputFormatMapper {

	private static final Log logger = LogFactory.getLog(OutputFormatMapper.class);

	private final ObjectMapper objectMapper;

	OutputFormatMapper(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * The output schema wins when both options are set: {@code ChatClient.entity(...)}
	 * supplies it and parses the reply against it, as with Spring AI's OpenAI options,
	 * where the output schema replaces the response format.
	 */
	@Nullable ObjectNode map(OpenRouterChatOptions options) {
		var format = options.getResponseFormat();
		String outputSchema = options.getOutputSchema();
		if (StringUtils.hasText(outputSchema)) {
			if (format != null) {
				OptionChecks.warnOnce(logger, "outputSchema-over-responseFormat-" + format.type(),
						() -> "Both responseFormat (" + format.type() + ") and outputSchema are set; sending "
								+ "outputSchema, which ChatClient.entity(...) parses the reply against, and ignoring "
								+ "responseFormat. Set only one of them.");
			}
			return jsonSchemaFormat("response", null, outputSchema);
		}
		return format != null ? mapResponseFormat(format) : null;
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
		if (!StringUtils.hasText(schema)) {
			throw new IllegalArgumentException("Invalid JSON schema: schema must not be blank");
		}
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
