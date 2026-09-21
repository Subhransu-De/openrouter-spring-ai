package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChatCompletionChunk(@Nullable String id, @Nullable String object, @Nullable Long created,
		@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
		@Nullable Usage usage, @Nullable StreamError error,
		@JsonAnyGetter @JsonAnySetter @Nullable Map<String, @Nullable Object> extensions) {
	public Map<String, @Nullable Object> extensions() {
		return Objects.requireNonNull(this.extensions);
	}

	public ChatCompletionChunk {
		extensions = extensions == null ? Map.of() : OptionSnapshots.map(extensions);
	}

	public ChatCompletionChunk(@Nullable String id, @Nullable String object, @Nullable Long created,
			@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
			@Nullable Usage usage, @Nullable StreamError error) {
		this(id, object, created, model, provider, choices, usage, error, null);
	}

}
