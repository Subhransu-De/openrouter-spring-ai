package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Checks the common strict tool contract; provider-specific limits remain upstream.
final class ToolSchemaValidator {

	private ToolSchemaValidator() {
	}

	static JsonNode read(ObjectMapper mapper, ToolDefinition tool, @Nullable Boolean strict) {
		JsonNode schema;
		try {
			schema = mapper.readTree(tool.inputSchema());
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("Invalid JSON schema", ex);
		}
		if (Boolean.TRUE.equals(strict)) {
			try {
				require(schema != null && schema.path("type").isString()
						&& "object".equals(schema.path("type").stringValue()), "parameters must have type object");
				require(!schema.has("anyOf"), "parameters must not use a root anyOf");
				validate(schema, schema, Collections.newSetFromMap(new IdentityHashMap<>()));
			}
			catch (IllegalArgumentException ex) {
				throw new IllegalArgumentException(
						"Invalid strict schema for tool '" + tool.name() + "': " + ex.getMessage(), ex);
			}
		}
		return schema;
	}

	private static void validate(JsonNode schema, JsonNode root, Set<JsonNode> visited) {
		require(schema.isObject(), "schemas must be JSON objects");
		require(schema.has("type") || schema.has("$ref") || schema.has("anyOf"),
				"schemas must declare type, $ref, or anyOf");
		if (!visited.add(schema)) {
			return;
		}
		for (String keyword : List.of("allOf", "oneOf", "not", "if", "then", "else", "dependentRequired",
				"dependentSchemas", "patternProperties", "unevaluatedProperties", "prefixItems", "$dynamicRef")) {
			require(!schema.has(keyword), "unsupported strict schema keyword: " + keyword);
		}
		if (schema.has("$ref")) {
			require(schema.path("$ref").isString(), "$ref must be a string");
			String ref = schema.path("$ref").stringValue();
			require(ref.equals("#") || ref.startsWith("#/"), "only local JSON pointer $refs are supported");
			JsonNode target = ref.equals("#") ? root : root.at(ref.substring(1));
			require(!target.isMissingNode(), "unresolved local $ref");
			validate(target, root, visited);
		}
		if (hasType(schema, "object") || schema.has("properties")) {
			validateObject(schema);
		}
		if (hasType(schema, "array")) {
			require(schema.has("items"), "arrays must declare items");
		}
		validateChildren(schema, root, visited);
	}

	private static void validateChildren(JsonNode schema, JsonNode root, Set<JsonNode> visited) {
		// Visit schema positions only: defaults, enums and examples are instance data.
		for (String keyword : List.of("properties", "$defs", "definitions")) {
			if (schema.has(keyword)) {
				require(schema.path(keyword).isObject(), keyword + " must be an object");
				for (JsonNode child : schema.path(keyword)) {
					validate(child, root, visited);
				}
			}
		}
		if (schema.has("items")) {
			validate(schema.path("items"), root, visited);
		}
		if (schema.has("anyOf")) {
			JsonNode alternatives = schema.path("anyOf");
			require(alternatives.isArray() && !alternatives.isEmpty(), "anyOf must be a nonempty array");
			for (JsonNode child : alternatives) {
				validate(child, root, visited);
			}
		}
	}

	private static boolean hasType(JsonNode schema, String type) {
		JsonNode types = schema.path("type");
		if (types.isString() && type.equals(types.stringValue())) {
			return true;
		}
		for (JsonNode candidate : types) {
			if (candidate.isString() && type.equals(candidate.stringValue())) {
				return true;
			}
		}
		return false;
	}

	private static void validateObject(JsonNode schema) {
		JsonNode additional = schema.path("additionalProperties");
		require(additional.isBoolean() && !additional.asBoolean(), "objects must set additionalProperties to false");
		JsonNode properties = schema.path("properties");
		require(properties.isObject(), "objects must declare properties (empty is allowed)");
		JsonNode required = schema.path("required");
		require(required.isArray(), "objects must declare required (empty is allowed)");
		Set<String> names = new HashSet<>();
		for (JsonNode name : required) {
			require(name.isString() && properties.has(name.stringValue()) && names.add(name.stringValue()),
					"required must contain each property exactly once");
		}
		require(names.size() == properties.size(),
				"all properties must be required; represent optional values with a nullable schema");
	}

	private static void require(boolean valid, String message) {
		if (!valid) {
			throw new IllegalArgumentException(message);
		}
	}

}
