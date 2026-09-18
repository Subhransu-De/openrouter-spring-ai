package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonDeserialize(using = ResponsesOutputItem.Deserializer.class)
public record ResponsesOutputItem(@Nullable String id, @Nullable String type, @Nullable String status,
		@Nullable String role, @Nullable List<@Nullable ResponsesContent> content, @Nullable String callId,
		@Nullable String name, @Nullable String arguments, @Nullable String result, @Nullable String outputFormat,
		@JsonIgnore @Nullable JsonNode rawItem) {

	public ResponsesOutputItem(@Nullable String id, @Nullable String type, @Nullable String status,
			@Nullable String role, @Nullable List<@Nullable ResponsesContent> content, @Nullable String callId,
			@Nullable String name, @Nullable String arguments, @Nullable String result, @Nullable JsonNode rawItem) {
		this(id, type, status, role, content, callId, name, arguments, result,
				rawItem != null && rawItem.hasNonNull("output_format") ? rawItem.get("output_format").asString() : null,
				rawItem);
	}

	public ResponsesOutputItem(@Nullable String id, @Nullable String type, @Nullable String status,
			@Nullable String role, @Nullable List<@Nullable ResponsesContent> content, @Nullable String callId,
			@Nullable String name, @Nullable String arguments, @Nullable String result) {
		this(id, type, status, role, content, callId, name, arguments, result, null);
	}

	public ResponsesOutputItem(@Nullable String id, @Nullable String type, @Nullable String status,
			@Nullable String role, @Nullable List<@Nullable ResponsesContent> content) {
		this(id, type, status, role, content, null, null, null, null);
	}

	// Received output items retain their complete wire shape for ordered conversation
	// replay. Locally constructed items retain the existing typed wire contract.
	@JsonValue
	public Object wireValue() {
		if (this.rawItem != null) {
			return this.rawItem;
		}
		Map<String, @Nullable Object> value = new LinkedHashMap<>();
		value.put("id", this.id);
		value.put("type", this.type);
		value.put("status", this.status);
		value.put("role", this.role);
		value.put("content", this.content);
		value.put("call_id", this.callId);
		value.put("name", this.name);
		value.put("arguments", this.arguments);
		value.put("result", this.result);
		value.put("output_format", this.outputFormat);
		value.values().removeIf(Objects::isNull);
		return value;
	}

	static final class Deserializer extends ValueDeserializer<ResponsesOutputItem> {

		@Override
		public ResponsesOutputItem deserialize(JsonParser parser, DeserializationContext context) {
			JsonNode node = parser.readValueAsTree();
			String type = text(node, "type");
			List<@Nullable ResponsesContent> content = !"reasoning".equals(type) && node.hasNonNull("content")
					? Arrays.asList(context.readTreeAsValue(node.get("content"), ResponsesContent[].class)) : null;
			return new ResponsesOutputItem(text(node, "id"), type, text(node, "status"), text(node, "role"), content,
					text(node, "call_id"), text(node, "name"), text(node, "arguments"), text(node, "result"),
					text(node, "output_format"), node.deepCopy());
		}

		private static @Nullable String text(JsonNode node, String field) {
			return node.hasNonNull(field) ? node.get(field).asString() : null;
		}

	}
}
