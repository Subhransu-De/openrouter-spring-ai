package de.subhransu.openrouter.springai.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Predicted output content, sent as {@code prediction}. Chat Completions only.
 *
 * @param type the prediction type; OpenRouter documents {@code content}
 * @param content the text the response is expected to contain
 * @author Subhransu De
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(Include.NON_NULL)
public record Prediction(String type, String content) {

	/**
	 * Returns a {@code content} prediction.
	 * @param content the text the response is expected to contain
	 * @return the prediction
	 */
	public static Prediction content(String content) {
		return new Prediction("content", content);
	}

}
