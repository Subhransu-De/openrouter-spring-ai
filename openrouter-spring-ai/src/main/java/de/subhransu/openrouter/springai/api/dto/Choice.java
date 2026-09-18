package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record Choice(@Nullable Integer index, @Nullable ChatMessage message, @Nullable Delta delta,
		@JsonProperty("finish_reason") @Nullable String finishReason,
		@JsonProperty("native_finish_reason") @Nullable Object nativeFinishReason, @Nullable ChoiceError error) {

	public Choice(@Nullable Integer index, @Nullable ChatMessage message, @Nullable Delta delta,
			@Nullable String finishReason, @Nullable Object nativeFinishReason) {
		this(index, message, delta, finishReason, nativeFinishReason, null);
	}

}
