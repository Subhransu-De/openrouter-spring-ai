package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An error embedded in an OpenRouter chat-completion choice, in either a blocking
 * response or a streamed chunk.
 *
 * @param code OpenRouter or upstream provider error code
 * @param message provider diagnostic message
 * @param metadata provider metadata, including {@code error_type} when supplied
 * @author Subhransu De
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record ChoiceError(@Nullable String code, @Nullable String message,
		@Nullable Map<String, @Nullable Object> metadata) {

	public ChoiceError {
		metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
	}

	public Map<String, @Nullable Object> metadata() {
		return Objects.requireNonNull(this.metadata);
	}

}
