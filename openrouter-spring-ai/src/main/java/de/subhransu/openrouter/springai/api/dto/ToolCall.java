package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ToolCall(@Nullable String id, @Nullable String type, @Nullable FunctionCall function,
		@Nullable Integer index) {

	public ToolCall(@Nullable String id, @Nullable String type, @Nullable FunctionCall function) {
		this(id, type, function, null);
	}

}
