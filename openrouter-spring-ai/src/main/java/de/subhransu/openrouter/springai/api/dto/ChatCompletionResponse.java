package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChatCompletionResponse(@Nullable String id, @Nullable String object, @Nullable Long created,
		@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
		@Nullable Usage usage, @Nullable StreamError error,
		@JsonAnyGetter @JsonAnySetter @Nullable Map<String, @Nullable Object> extensions) {
	public ChatCompletionResponse {
		extensions = extensions == null ? Map.of() : OptionSnapshots.map(extensions);
	}

	public ChatCompletionResponse(@Nullable String id, @Nullable String object, @Nullable Long created,
			@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
			@Nullable Usage usage, @Nullable StreamError error) {
		this(id, object, created, model, provider, choices, usage, error, null);
	}

	public ChatCompletionResponse(@Nullable String id, @Nullable String object, @Nullable Long created,
			@Nullable String model, @Nullable String provider, @Nullable List<@Nullable Choice> choices,
			@Nullable Usage usage) {
		this(id, object, created, model, provider, choices, usage, null);
	}
}
