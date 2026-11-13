package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.support.OptionChecks;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.StringUtils;

final class ToolChoiceMapper {

	private ToolChoiceMapper() {
	}

	/**
	 * Maps a tool choice to the endpoint's wire shape. Without tools, auto and none mean
	 * nothing and are left out; required and a named function cannot be met and are
	 * rejected.
	 */
	static @Nullable Object map(@Nullable Object choice, boolean responses, ObjectMapper mapper,
			List<ToolDefinition> tools) {
		if (choice == null) {
			return null;
		}
		JsonNode original = mapper.valueToTree(choice);
		JsonNode node = original;
		if (node.isObject() && node.size() == 1 && node.has("type")) {
			node = node.path("type");
		}
		if (node.isString() && Set.of("auto", "none", "required").contains(node.stringValue())) {
			return mode(node.stringValue(), tools);
		}
		String name = functionName(node);
		if (name != null) {
			requireTool(name, tools);
			Map<String, Object> namedChoice = new LinkedHashMap<>();
			namedChoice.put("type", "function");
			namedChoice.put(responses ? "name" : "function", responses ? name : Map.of("name", name));
			return Collections.unmodifiableMap(namedChoice);
		}
		throw new IllegalArgumentException(
				"toolChoice " + OptionChecks.describe(original.isString() ? original.stringValue() : original)
						+ " is not supported; use auto, none, required or a named function "
						+ "({type:function,name:...} or {type:function,function:{name:...}})");
	}

	private static @Nullable String mode(String mode, List<ToolDefinition> tools) {
		if (!tools.isEmpty()) {
			return mode;
		}
		if ("required".equals(mode)) {
			throw new IllegalArgumentException("toolChoice \"required\" needs at least one tool, but the request "
					+ "has none; add tool callbacks or unset toolChoice");
		}
		return null;
	}

	private static void requireTool(String name, List<ToolDefinition> tools) {
		List<String> names = tools.stream().map(ToolDefinition::name).toList();
		if (!names.contains(name)) {
			throw new IllegalArgumentException("toolChoice names function " + OptionChecks.describe(name)
					+ ", but the request's tools are " + names + "; name one of them or unset toolChoice");
		}
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
