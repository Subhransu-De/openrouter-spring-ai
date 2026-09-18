package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record StreamError(@Nullable String code, @Nullable String message, @Nullable JsonNode metadata,
		@JsonProperty("error_type") @Nullable String errorType) {

	@JsonCreator
	public StreamError(@JsonProperty("code") @Nullable String code, @JsonProperty("message") @Nullable JsonNode message,
			@JsonProperty("metadata") @Nullable JsonNode metadata,
			@JsonProperty("error_type") @Nullable JsonNode errorType) {
		this(code, text(message), metadata, text(errorType));
	}

	public StreamError(@Nullable String code, @Nullable String message) {
		this(code, message, null, null);
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		if (value == null || value.isNull()) {
			return null;
		}
		return value.isString() ? value.stringValue() : value.toString();
	}
}
