package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ResponsesContent(@Nullable String type, @Nullable String text,
		@JsonProperty("image_url") @Nullable String imageUrl, @Nullable String refusal, @Nullable String filename,
		@JsonProperty("file_data") @Nullable String fileData, @JsonProperty("file_url") @Nullable String fileUrl,
		@JsonProperty("input_audio") ContentPart.@Nullable AudioInput inputAudio,
		@JsonProperty("video_url") @Nullable String videoUrl) {

	public ResponsesContent(@Nullable String type, @Nullable String text, @Nullable String imageUrl,
			@Nullable String refusal) {
		this(type, text, imageUrl, refusal, null, null, null, null, null);
	}

	public ResponsesContent(@Nullable String type, @Nullable String text, @Nullable String imageUrl) {
		this(type, text, imageUrl, null);
	}

	public ResponsesContent(@Nullable String type, @Nullable String text) {
		this(type, text, null);
	}
}
