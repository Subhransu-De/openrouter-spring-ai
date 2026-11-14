package de.subhransu.openrouter.springai.api;

/**
 * OpenRouter wire protocol used for chat requests.
 *
 * @author Subhransu De
 */
public enum OpenRouterRequestMode {

	/**
	 * OpenRouter Chat Completions API ({@code /chat/completions}). This is the
	 * production-supported default.
	 */
	CHAT_COMPLETIONS,

	/**
	 * OpenRouter Responses API ({@code /responses}). This is an experimental, explicitly
	 * selected compatibility mode.
	 */
	RESPONSES

}
