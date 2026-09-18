package de.subhransu.openrouter.springai.errors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Tolerant DTO for OpenRouter's HTTP error envelope.
 *
 * <p>
 * Provider-specific metadata is deliberately retained as a JSON tree. Additive fields
 * therefore remain inspectable without making deserialization depend on an undocumented
 * provider schema.
 *
 * @param error error object supplied by OpenRouter
 * @param errorType canonical Responses-style root {@code error_type}, when supplied
 * @author Subhransu De
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
@org.jspecify.annotations.NullUnmarked
public record OpenRouterErrorResponse(Error error,
		@JsonDeserialize(using = TolerantJsonStringDeserializer.class) @JsonProperty("error_type") String errorType) {

	/**
	 * Tolerant OpenRouter/provider error object.
	 *
	 * @param code numeric or symbolic OpenRouter/provider code
	 * @param message diagnostic message in any provider-supplied JSON shape
	 * @param metadata complete provider metadata object
	 * @param errorType nested canonical error type used by some API skins
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record Error(JsonNode code, JsonNode message, JsonNode metadata, @JsonDeserialize(
			using = TolerantJsonStringDeserializer.class) @JsonProperty("error_type") String errorType) {
	}

}
