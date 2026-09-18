package de.subhransu.openrouter.springai.chat;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.List;

public record OpenRouterProviderPreferences(@Nullable Boolean allowFallbacks, @Nullable Boolean requireParameters,
		@Nullable String dataCollection, @Nullable List<String> order, @Nullable List<String> ignore,
		@Nullable List<String> quantizations, @Nullable String sort) {
	public OpenRouterProviderPreferences {
		order = OptionSnapshots.list(order);
		ignore = OptionSnapshots.list(ignore);
		quantizations = OptionSnapshots.list(quantizations);
	}
}
