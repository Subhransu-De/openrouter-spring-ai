package de.subhransu.openrouter.springai.api.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.subhransu.openrouter.springai.errors.TolerantJsonStringDeserializer;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ResponsesStreamEvent(String type, String delta, ResponsesOutputItem item, ResponsesResult response,
		StreamError error, @JsonDeserialize(using = TolerantJsonStringDeserializer.class) String code,
		@JsonDeserialize(using = TolerantJsonStringDeserializer.class) String message, JsonNode metadata,
		@JsonDeserialize(using = TolerantJsonStringDeserializer.class) @JsonProperty("error_type") String errorType,
		String refusal, @JsonProperty("output_index") Integer outputIndex,
		@JsonProperty("content_index") Integer contentIndex) {

	public ResponsesStreamEvent(String type, String delta, ResponsesOutputItem item, ResponsesResult response,
			StreamError error, String code, String message, JsonNode metadata, String errorType) {
		this(type, delta, item, response, error, code, message, metadata, errorType, null, null, null);
	}

	public ResponsesStreamEvent(String type, String delta, ResponsesOutputItem item, ResponsesResult response,
			StreamError error, String code, String message) {
		this(type, delta, item, response, error, code, message, null, null);
	}

	public ResponsesStreamEvent(String type, String delta, ResponsesOutputItem item, ResponsesResult response,
			StreamError error) {
		this(type, delta, item, response, error, null, null, null, null);
	}

}
