package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ImagesRequest(String model, String prompt, @Nullable Integer n, @Nullable String size,
		@Nullable String resolution, @JsonProperty("aspect_ratio") @Nullable String aspectRatio,
		@Nullable String quality, @JsonProperty("output_format") @Nullable String outputFormat,
		@Nullable String background, @JsonProperty("output_compression") @Nullable Integer outputCompression,
		@Nullable Integer seed, @Nullable Boolean stream,
		@JsonProperty("input_references") @Nullable List<ContentPart> inputReferences,
		@Nullable Map<String, @Nullable Object> provider) {
}
