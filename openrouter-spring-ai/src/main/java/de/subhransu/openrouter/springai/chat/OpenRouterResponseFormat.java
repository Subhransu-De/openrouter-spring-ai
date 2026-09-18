package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;

/**
 * Typed {@code response_format} option, mirroring the shape Spring AI 2.0's OpenAI module
 * exposes (text, JSON mode, or a JSON schema for structured output). The request mapper
 * serializes it to OpenRouter's OpenAI-compatible wire form.
 *
 * @param type the response format type
 * @param name the schema name sent for {@link Type#JSON_SCHEMA} (defaults to
 * {@code response})
 * @param strict whether the provider must follow the schema strictly (only meaningful for
 * {@link Type#JSON_SCHEMA})
 * @param schema the JSON schema document for {@link Type#JSON_SCHEMA}
 * @author Subhransu De
 */
public record OpenRouterResponseFormat(Type type, @Nullable String name, @Nullable Boolean strict,
		@Nullable String schema) {

	public OpenRouterResponseFormat {
		Assert.notNull(type, "type must not be null");
		Assert.isTrue(type != Type.JSON_SCHEMA || schema != null, "JSON_SCHEMA response format requires a schema");
	}

	public static OpenRouterResponseFormat text() {
		return new OpenRouterResponseFormat(Type.TEXT, null, null, null);
	}

	public static OpenRouterResponseFormat jsonObject() {
		return new OpenRouterResponseFormat(Type.JSON_OBJECT, null, null, null);
	}

	public static OpenRouterResponseFormat jsonSchema(String schema) {
		return new OpenRouterResponseFormat(Type.JSON_SCHEMA, null, null, schema);
	}

	public static OpenRouterResponseFormat jsonSchema(@Nullable String name, @Nullable Boolean strict, String schema) {
		return new OpenRouterResponseFormat(Type.JSON_SCHEMA, name, strict, schema);
	}

	public enum Type {

		TEXT, JSON_OBJECT, JSON_SCHEMA

	}

}
