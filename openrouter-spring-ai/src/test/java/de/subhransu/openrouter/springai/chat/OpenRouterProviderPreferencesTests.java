package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("api")
@Tag("api.options")
class OpenRouterProviderPreferencesTests {

	@Test
	void builderSetsNamedPreferencesAndLeavesTheRestUnset() {
		assertThat(OpenRouterProviderPreferences.builder().order(List.of("provider-a")).allowFallbacks(false).build())
			.isEqualTo(new OpenRouterProviderPreferences(false, null, null, List.of("provider-a"), null, null, null));
		assertThat(OpenRouterProviderPreferences.builder()
			.requireParameters(true)
			.dataCollection("deny")
			.ignore(List.of("provider-b"))
			.quantizations(List.of("fp8"))
			.sort("price")
			.build())
			.isEqualTo(new OpenRouterProviderPreferences(null, true, "deny", null, List.of("provider-b"),
					List.of("fp8"), "price"));
	}

	@Test
	void builderSnapshotsLists() {
		var order = new ArrayList<>(List.of("provider-a"));
		var preferences = OpenRouterProviderPreferences.builder().order(order).build();
		order.clear();

		assertThat(preferences.order()).containsExactly("provider-a");
		assertThatThrownBy(preferences.order()::clear).isInstanceOf(UnsupportedOperationException.class);
	}

}
