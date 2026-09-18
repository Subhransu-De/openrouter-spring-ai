package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ImagesResponse(@Nullable Long created, @Nullable List<@Nullable ImageData> data, @Nullable Usage usage) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record ImageData(@JsonProperty("b64_json") @Nullable String b64Json,
			@JsonProperty("media_type") @Nullable String mediaType, @Nullable String url) {
	}
}
