package de.subhransu.openrouter.springai.audio;

/**
 * Timestamp detail levels of a {@code verbose_json} transcription, sent in
 * {@code timestamp_granularities}.
 *
 * @author Subhransu De
 */
public enum OpenRouterTimestampGranularity {

	/** Word-level timestamps in the {@code words} array. */
	WORD("word"),

	/** Segment-level timestamps in the {@code segments} array. */
	SEGMENT("segment");

	private final String value;

	OpenRouterTimestampGranularity(String value) {
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
