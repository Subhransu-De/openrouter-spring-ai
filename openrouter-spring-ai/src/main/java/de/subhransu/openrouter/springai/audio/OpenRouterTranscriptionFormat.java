package de.subhransu.openrouter.springai.audio;

/**
 * Response formats of OpenRouter's transcription endpoint, sent as
 * {@code response_format}. {@code text}, {@code srt} and {@code vtt} are not offered.
 *
 * @author Subhransu De
 */
public enum OpenRouterTranscriptionFormat {

	/** The transcript text and usage. The endpoint default. */
	JSON("json"),

	/**
	 * Adds the task, language, duration and timestamped segments, and words or speaker
	 * labels when they are requested. Only OpenAI-compatible providers support it.
	 */
	VERBOSE_JSON("verbose_json");

	private final String value;

	OpenRouterTranscriptionFormat(String value) {
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
