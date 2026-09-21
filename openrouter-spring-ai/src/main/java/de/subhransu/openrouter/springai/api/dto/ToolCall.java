package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ToolCall(@Nullable String id, @Nullable String type, @Nullable FunctionCall function,
		@Nullable Integer index, @JsonAnyGetter @JsonAnySetter @Nullable Map<String, @Nullable Object> extensions) {
	public Map<String, @Nullable Object> extensions() {
		return Objects.requireNonNull(this.extensions);
	}

	public ToolCall {
		extensions = extensions == null ? Map.of() : OptionSnapshots.map(extensions);
	}

	public ToolCall(@Nullable String id, @Nullable String type, @Nullable FunctionCall function,
			@Nullable Integer index) {
		this(id, type, function, index, null);
	}

	public ToolCall(@Nullable String id, @Nullable String type, @Nullable FunctionCall function) {
		this(id, type, function, null);
	}

}
