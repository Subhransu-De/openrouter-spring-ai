package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// Request body of POST /audio/speech. The success response is raw audio bytes, not JSON.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record SpeechRequest(String model, String input, @Nullable String voice,
		@JsonProperty("response_format") @Nullable String responseFormat, @Nullable Double speed,
		@Nullable String instructions, @Nullable ProviderPreferences provider, @Nullable String user,
		@JsonProperty("session_id") @Nullable String sessionId, @Nullable Map<String, @Nullable Object> trace) {

}
