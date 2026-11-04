package de.subhransu.openrouter.springai.chat.mapper;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.util.StringUtils;

final class ToolChoiceMapper {

	private ToolChoiceMapper() {
	}

	static @Nullable Object map(@Nullable Object choice, boolean responses, ObjectMapper mapper) {
		if (choice == null) {
			return null;
		}
		JsonNode node = mapper.valueToTree(choice);
		if (node.isObject() && node.size() == 1 && node.has("type")) {
			node = node.path("type");
		}
		if (node.isString() && Set.of("auto", "none", "required").contains(node.stringValue())) {
			return node.stringValue();
		}
		String name = functionName(node);
		if (name != null) {
			Map<String, Object> namedChoice = new LinkedHashMap<>();
			namedChoice.put("type", "function");
			namedChoice.put(responses ? "name" : "function", responses ? name : Map.of("name", name));
			return Collections.unmodifiableMap(namedChoice);
		}
		throw new IllegalArgumentException("toolChoice must be auto, none, required, or a named function "
				+ "({type:function,name:...} or {type:function,function:{name:...}})");
	}

	private static @Nullable String functionName(JsonNode node) {
		if (node.isObject() && node.path("type").isString() && "function".equals(node.path("type").stringValue())
				&& node.size() == 2) {
			JsonNode function = node.path("function");
			JsonNode name = node.has("name") ? node.path("name") : function.path("name");
			if (name.isString() && StringUtils.hasText(name.stringValue())
					&& (node.has("name") || (function.isObject() && function.size() == 1))) {
				return name.stringValue();
			}
		}
		return null;
	}

}
