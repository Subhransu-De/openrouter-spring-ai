package de.subhransu.openrouter.springai.audio.mapper;

import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps provider preferences for the audio endpoints. OpenRouter's speech and
 * transcription guides say that only the data policy preferences apply there, and that
 * {@code order}, {@code only} and {@code ignore} are not applied. Of the typed
 * preferences only {@code dataCollection} is sent; any other is rejected instead of being
 * silently ignored.
 *
 * @author Subhransu De
 */
final class AudioProviderMapper {

	private AudioProviderMapper() {
	}

	static @Nullable ProviderPreferences map(@Nullable OpenRouterProviderPreferences provider, String endpoint) {
		if (provider == null) {
			return null;
		}
		List<String> unsupported = new ArrayList<>();
		add(unsupported, "allowFallbacks", provider.allowFallbacks());
		add(unsupported, "requireParameters", provider.requireParameters());
		add(unsupported, "order", provider.order());
		add(unsupported, "ignore", provider.ignore());
		add(unsupported, "quantizations", provider.quantizations());
		add(unsupported, "sort", provider.sort());
		if (!unsupported.isEmpty()) {
			throw new IllegalArgumentException("OpenRouter " + endpoint + " does not apply provider "
					+ String.join(", ", unsupported) + "; it accepts only dataCollection");
		}
		return new ProviderPreferences(null, null, provider.dataCollection(), null, null, null, null);
	}

	private static void add(List<String> unsupported, String name, @Nullable Object value) {
		if (value != null) {
			unsupported.add(name);
		}
	}

}
