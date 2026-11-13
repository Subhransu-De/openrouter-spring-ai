package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.OptionChecks;
import org.jspecify.annotations.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

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
 * @param schema the JSON schema document for {@link Type#JSON_SCHEMA}; must not be blank
 * for that type
 * @author Subhransu De
 */
public record OpenRouterResponseFormat(Type type, @Nullable String name, @Nullable Boolean strict,
		@Nullable String schema) {

	public OpenRouterResponseFormat {
		Assert.notNull(type, "type must not be null");
		if (type == Type.JSON_SCHEMA && !StringUtils.hasText(schema)) {
			throw new IllegalArgumentException("Invalid JSON schema: responseFormat JSON_SCHEMA requires a non-blank "
					+ "schema, but schema was " + OptionChecks.describe(schema)
					+ "; supply a JSON schema document or use TEXT or JSON_OBJECT");
		}
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
