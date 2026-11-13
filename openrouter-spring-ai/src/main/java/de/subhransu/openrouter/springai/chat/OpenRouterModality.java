package de.subhransu.openrouter.springai.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Output modalities that OpenRouter documents for {@code modalities}. Audio output
 * requires streaming Chat Completions. The String overloads accept values that OpenRouter
 * adds later. Modalities are lower-cased; an undocumented value is still sent and is
 * logged once as a warning.
 *
 * @author Subhransu De
 */
public enum OpenRouterModality {

	TEXT("text"), IMAGE("image"), AUDIO("audio");

	static final List<String> VALUES = Arrays.stream(values()).map(OpenRouterModality::value).toList();

	private final String value;

	OpenRouterModality(String value) {
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
