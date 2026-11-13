package de.subhransu.openrouter.springai.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Data collection policies that OpenRouter documents for
 * {@code provider.data_collection}. The String overloads accept values that OpenRouter
 * adds later; an undocumented value is sent unchanged and logged once as a warning.
 *
 * @author Subhransu De
 */
public enum OpenRouterDataCollection {

	ALLOW("allow"), DENY("deny");

	static final List<String> VALUES = Arrays.stream(values()).map(OpenRouterDataCollection::value).toList();

	private final String value;

	OpenRouterDataCollection(String value) {
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
