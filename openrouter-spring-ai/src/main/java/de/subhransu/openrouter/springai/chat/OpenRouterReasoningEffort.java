package de.subhransu.openrouter.springai.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Reasoning effort levels that OpenRouter documents for {@code reasoning.effort}. The
 * String overloads accept values that OpenRouter adds later; an undocumented value is
 * sent unchanged and logged once as a warning.
 *
 * @author Subhransu De
 */
public enum OpenRouterReasoningEffort {

	NONE("none"), MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high"), XHIGH("xhigh"), MAX("max");

	static final List<String> VALUES = Arrays.stream(values()).map(OpenRouterReasoningEffort::value).toList();

	private final String value;

	OpenRouterReasoningEffort(String value) {
		this.value = value;
	}

	/**
	 * Returns the wire value.
	 * @return the value OpenRouter expects
	 */
	public String value() {
		return this.value;
	}

}
