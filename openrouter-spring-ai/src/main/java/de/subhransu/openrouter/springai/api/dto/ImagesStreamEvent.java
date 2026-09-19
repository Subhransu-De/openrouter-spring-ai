package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ImagesStreamEvent(@Nullable String type,
		@JsonProperty("partial_image_index") @Nullable Integer partialImageIndex,
		@JsonProperty("b64_json") @Nullable String b64Json, @JsonProperty("media_type") @Nullable String mediaType,
		@Nullable Long created, @Nullable Usage usage, @Nullable StreamError error, @Nullable String url) {

	public ImagesStreamEvent(@Nullable String type, @Nullable Integer partialImageIndex, @Nullable String b64Json,
			@Nullable String mediaType, @Nullable Long created, @Nullable Usage usage, @Nullable StreamError error) {
		this(type, partialImageIndex, b64Json, mediaType, created, usage, error, null);
	}

	public static final String PARTIAL_IMAGE = "image_generation.partial_image";

	public static final String COMPLETED = "image_generation.completed";

	public static final String ERROR_EVENT = "error";

}
