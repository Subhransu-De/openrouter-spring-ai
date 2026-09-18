package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ContentPart(@Nullable String type, @Nullable String text,
		@JsonProperty("image_url") @Nullable ImageUrl imageUrl) {

	public static ContentPart text(String text) {
		return new ContentPart("text", text, null);
	}

	public static ContentPart image(String url) {
		return new ContentPart("image_url", null, new ImageUrl(url));
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record ImageUrl(@Nullable String url) {
	}
}
