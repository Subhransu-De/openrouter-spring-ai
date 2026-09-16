package de.subhransu.openrouter.springai.chat;

import de.subhransu.openrouter.springai.support.OptionSnapshots;
import java.util.List;

public record OpenRouterProviderPreferences(Boolean allowFallbacks, Boolean requireParameters, String dataCollection,
		List<String> order, List<String> ignore, List<String> quantizations, String sort) {
	public OpenRouterProviderPreferences {
		order = OptionSnapshots.list(order);
		ignore = OptionSnapshots.list(ignore);
		quantizations = OptionSnapshots.list(quantizations);
	}
}
