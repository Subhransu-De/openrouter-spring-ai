package de.subhransu.openrouter.springai.chat;

import java.util.Arrays;
import java.util.List;

/**
 * Provider sort strategies that OpenRouter documents for {@code provider.sort}. The
 * String overloads accept values that OpenRouter adds later; an undocumented value is
 * sent unchanged and logged once as a warning.
 *
 * @author Subhransu De
 */
public enum OpenRouterProviderSort {

	PRICE("price"), THROUGHPUT("throughput"), LATENCY("latency"), EXACTO("exacto");

	static final List<String> VALUES = Arrays.stream(values()).map(OpenRouterProviderSort::value).toList();

	private final String value;

	OpenRouterProviderSort(String value) {
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
