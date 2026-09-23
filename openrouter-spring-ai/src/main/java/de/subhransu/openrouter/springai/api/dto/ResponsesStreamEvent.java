package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.subhransu.openrouter.springai.errors.TolerantJsonStringDeserializer;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ResponsesStreamEvent(@Nullable String type, @Nullable String delta, @Nullable ResponsesOutputItem item,
		@Nullable ResponsesResult response, @Nullable StreamError error,
		@JsonDeserialize(using = TolerantJsonStringDeserializer.class) @Nullable String code,
		@JsonDeserialize(using = TolerantJsonStringDeserializer.class) @Nullable String message,
		@Nullable JsonNode metadata,
		@JsonDeserialize(
				using = TolerantJsonStringDeserializer.class) @JsonProperty("error_type") @Nullable String errorType,
		@Nullable String refusal, @JsonProperty("output_index") @Nullable Integer outputIndex,
		@JsonProperty("content_index") @Nullable Integer contentIndex, @Nullable String text) {

	public ResponsesStreamEvent(@Nullable String type, @Nullable String delta, @Nullable ResponsesOutputItem item,
			@Nullable ResponsesResult response, @Nullable StreamError error, @Nullable String code,
			@Nullable String message, @Nullable JsonNode metadata, @Nullable String errorType, @Nullable String refusal,
			@Nullable Integer outputIndex, @Nullable Integer contentIndex) {
		this(type, delta, item, response, error, code, message, metadata, errorType, refusal, outputIndex, contentIndex,
				null);
	}

	public ResponsesStreamEvent(@Nullable String type, @Nullable String delta, @Nullable ResponsesOutputItem item,
			@Nullable ResponsesResult response, @Nullable StreamError error, @Nullable String code,
			@Nullable String message, @Nullable JsonNode metadata, @Nullable String errorType) {
		this(type, delta, item, response, error, code, message, metadata, errorType, null, null, null);
	}

	public ResponsesStreamEvent(@Nullable String type, @Nullable String delta, @Nullable ResponsesOutputItem item,
			@Nullable ResponsesResult response, @Nullable StreamError error, @Nullable String code,
			@Nullable String message) {
		this(type, delta, item, response, error, code, message, null, null);
	}

	public ResponsesStreamEvent(@Nullable String type, @Nullable String delta, @Nullable ResponsesOutputItem item,
			@Nullable ResponsesResult response, @Nullable StreamError error) {
		this(type, delta, item, response, error, null, null, null, null);
	}

}
