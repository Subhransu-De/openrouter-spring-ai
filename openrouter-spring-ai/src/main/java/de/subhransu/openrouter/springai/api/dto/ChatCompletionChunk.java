package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChatCompletionChunk(@Nullable String id, @Nullable String object, @Nullable Long created,
		@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
		@Nullable Usage usage, @Nullable StreamError error) {
}
