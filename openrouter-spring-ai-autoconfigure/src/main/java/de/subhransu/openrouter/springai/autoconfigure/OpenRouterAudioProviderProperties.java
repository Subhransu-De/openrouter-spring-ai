package de.subhransu.openrouter.springai.autoconfigure;

import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import org.jspecify.annotations.Nullable;

/**
 * Provider preferences of the speech and transcription endpoints. OpenRouter applies only
 * its data policy preferences there, so only {@code data-collection} is offered.
 *
 * @author Subhransu De
 */
public class OpenRouterAudioProviderProperties {

	/**
	 * Data collection policy that upstream providers must meet: allow or deny.
	 */
	private @Nullable String dataCollection;

	@Nullable OpenRouterProviderPreferences toPreferences() {
		return this.dataCollection == null ? null
				: OpenRouterProviderPreferences.builder().dataCollection(this.dataCollection).build();
	}

	public @Nullable String getDataCollection() {
		return this.dataCollection;
	}

	public void setDataCollection(@Nullable String dataCollection) {
		this.dataCollection = dataCollection;
	}

}
