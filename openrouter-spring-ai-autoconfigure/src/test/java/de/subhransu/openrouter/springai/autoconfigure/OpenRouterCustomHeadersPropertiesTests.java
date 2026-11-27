package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

@Tag("configuration")
@Tag("configuration.properties")
class OpenRouterCustomHeadersPropertiesTests {

	@Test
	void aHigherPrioritySourceWinsWhenItSpellsTheHeaderNameDifferently() {
		MutablePropertySources sources = new MutablePropertySources();
		sources.addLast(new MapPropertySource("override",
				Map.of("spring.ai.openrouter.chat.custom-headers[x-tenant]", "override")));
		sources.addLast(new MapPropertySource("base", Map.of("spring.ai.openrouter.chat.custom-headers[X-Tenant]",
				"default", "spring.ai.openrouter.chat.custom-headers[X-Region]", "eu")));
		OpenRouterChatProperties properties = new Binder(ConfigurationPropertySources.from(sources))
			.bindOrCreate(OpenRouterChatProperties.CONFIG_PREFIX, OpenRouterChatProperties.class);

		OpenRouterChatOptions defaults = properties.toOptions();

		assertThat(defaults.getCustomHeaders()).hasSize(2)
			.containsEntry("X-Tenant", "override")
			.containsEntry("X-Region", "eu");
		OpenRouterChatOptions request = OpenRouterChatOptions.builder()
			.customHeaders(Map.of("X-TENANT", "request"))
			.build();
		assertThat(defaults.merge(request).getCustomHeaders()).containsEntry("x-tenant", "request")
			.containsEntry("X-Region", "eu");
	}

}
