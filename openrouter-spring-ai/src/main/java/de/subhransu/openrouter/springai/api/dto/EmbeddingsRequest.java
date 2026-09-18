package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record EmbeddingsRequest(@Nullable String model, Object input,
		@JsonProperty("encoding_format") @Nullable String encodingFormat, @Nullable Integer dimensions,
		@Nullable String user, @Nullable ProviderPreferences provider) {
}
