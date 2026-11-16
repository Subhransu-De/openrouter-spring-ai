package de.subhransu.openrouter.springai.audio.mapper;

import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps provider preferences for the audio endpoints, which accept fewer routing fields
 * than chat. A preference that the endpoint does not apply is rejected instead of being
 * silently ignored.
 *
 * @author Subhransu De
 */
final class AudioProviderMapper {

	static final String ALLOW_FALLBACKS = "allowFallbacks";

	static final String DATA_COLLECTION = "dataCollection";

	static final String ORDER = "order";

	static final String IGNORE = "ignore";

	private AudioProviderMapper() {
	}

	static @Nullable ProviderPreferences map(@Nullable OpenRouterProviderPreferences provider, String endpoint,
			List<String> supported) {
		if (provider == null) {
			return null;
		}
		List<String> unsupported = new ArrayList<>();
		check(unsupported, supported, ALLOW_FALLBACKS, provider.allowFallbacks());
		check(unsupported, supported, "requireParameters", provider.requireParameters());
		check(unsupported, supported, DATA_COLLECTION, provider.dataCollection());
		check(unsupported, supported, ORDER, provider.order());
		check(unsupported, supported, IGNORE, provider.ignore());
		check(unsupported, supported, "quantizations", provider.quantizations());
		check(unsupported, supported, "sort", provider.sort());
		if (!unsupported.isEmpty()) {
			throw new IllegalArgumentException("OpenRouter " + endpoint + " does not apply provider "
					+ String.join(", ", unsupported) + "; it accepts only " + String.join(", ", supported));
		}
		return new ProviderPreferences(provider.allowFallbacks(), null, provider.dataCollection(), provider.order(),
				provider.ignore(), null, null);
	}

	private static void check(List<String> unsupported, List<String> supported, String name, @Nullable Object value) {
		if (value != null && !supported.contains(name)) {
			unsupported.add(name);
		}
	}

}
