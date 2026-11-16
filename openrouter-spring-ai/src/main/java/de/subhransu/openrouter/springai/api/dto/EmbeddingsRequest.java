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
		@JsonProperty("session_id") @Nullable String sessionId, @Nullable Map<String, @Nullable Object> trace) {

	/**
	 * Creates a request without {@code session_id} or {@code trace}.
	 */
	public EmbeddingsRequest(@Nullable String model, Object input, @Nullable String encodingFormat,
			@Nullable Integer dimensions, @Nullable String user, @Nullable ProviderPreferences provider) {
		this(model, input, encodingFormat, dimensions, user, provider, null, null);
	}

}
