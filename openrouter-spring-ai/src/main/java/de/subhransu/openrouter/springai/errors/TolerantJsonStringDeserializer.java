package de.subhransu.openrouter.springai.errors;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Converts an arbitrary JSON value to its string representation without rejecting its
 * containing response.
 *
 * @author Subhransu De
 */
@org.jspecify.annotations.NullUnmarked
public final class TolerantJsonStringDeserializer extends ValueDeserializer<String> {

	@Override
	public String deserialize(JsonParser parser, DeserializationContext context) {
		JsonNode value = parser.readValueAsTree();
		if (value == null || value.isNull()) {
			return null;
		}
		return value.isString() ? value.stringValue() : value.toString();
	}

}
