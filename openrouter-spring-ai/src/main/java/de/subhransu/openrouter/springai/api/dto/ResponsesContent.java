package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ResponsesContent(String type, String text, @JsonProperty("image_url") String imageUrl, String refusal) {

	public ResponsesContent(String type, String text, String imageUrl) {
		this(type, text, imageUrl, null);
	}

	public ResponsesContent(String type, String text) {
		this(type, text, null);
	}
}
