package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// JSON request body of POST /api/alpha/decisions. The state is a string, a JSON object or
// an array of strings and content parts.
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record DecisionsRequest(String model, Object state, Map<String, Question> questions,
		@Nullable ProviderPreferences provider, @Nullable String user,
		@JsonProperty("session_id") @Nullable String sessionId, @Nullable Map<String, @Nullable Object> trace) {

	/**
	 * One typed question: {@code noul}, {@code choice} or {@code score}.
	 *
	 * @param type the question type
	 * @param instructions what the model decides
	 * @param criteria the {@code true}/{@code false} descriptions of a {@code noul}, the
	 * options of a {@code choice} or the ordered levels of a {@code score}
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	@JsonInclude(Include.NON_NULL)
	public record Question(String type, String instructions, @Nullable Object criteria) {
	}

}
