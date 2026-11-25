package de.subhransu.openrouter.springai.api.dto;

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record EmbeddingsRequest(@Nullable String model, Object input,
		@JsonProperty("encoding_format") @Nullable String encodingFormat, @Nullable Integer dimensions,
		@Nullable String user, @Nullable ProviderPreferences provider,
		@JsonProperty("session_id") @Nullable String sessionId, @Nullable Map<String, @Nullable Object> trace,
		@JsonProperty("input_type") @Nullable String inputType) {

	/**
	 * Creates a request without {@code session_id}, {@code trace} or {@code input_type}.
	 */
	public EmbeddingsRequest(@Nullable String model, Object input, @Nullable String encodingFormat,
			@Nullable Integer dimensions, @Nullable String user, @Nullable ProviderPreferences provider) {
		this(model, input, encodingFormat, dimensions, user, provider, null, null, null);
	}

	/**
	 * Creates a request without {@code input_type}.
	 */
	public EmbeddingsRequest(@Nullable String model, Object input, @Nullable String encodingFormat,
			@Nullable Integer dimensions, @Nullable String user, @Nullable ProviderPreferences provider,
			@Nullable String sessionId, @Nullable Map<String, @Nullable Object> trace) {
		this(model, input, encodingFormat, dimensions, user, provider, sessionId, trace, null);
	}

}
