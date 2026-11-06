package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Boot reads {@code META-INF/spring-autoconfigure-metadata.properties} to discard
 * auto-configurations whose class conditions fail before it loads them.
 */
class OpenRouterAutoConfigurationMetadataTests {

	private static final String ON_CLASS = "ConditionalOnClass";

	@Test
	void autoConfigureMetadataRecordsTheClassConditions() throws IOException {
		Properties metadata = loadMetadata();
		assertThat(entry(metadata, OpenRouterApiAutoConfiguration.class, ON_CLASS))
			.isEqualTo(OpenRouterApi.class.getName());
		assertThat(entry(metadata, OpenRouterChatAutoConfiguration.class, ON_CLASS))
			.contains(OpenRouterChatModel.class.getName());
		assertThat(entry(metadata, OpenRouterEmbeddingAutoConfiguration.class, ON_CLASS))
			.contains(OpenRouterEmbeddingModel.class.getName());
		assertThat(entry(metadata, OpenRouterImageAutoConfiguration.class, ON_CLASS))
			.contains(OpenRouterImageModel.class.getName());
		assertThat(entry(metadata, OpenRouterChatAutoConfiguration.class, "AutoConfigureAfter"))
			.contains(OpenRouterApiAutoConfiguration.class.getName());
		assertThat(entry(metadata, OpenRouterToolCallingAutoConfiguration.class, "AutoConfigureBefore"))
			.contains("org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration");
	}

	private static String entry(Properties metadata, Class<?> autoConfiguration, String attribute) {
		return metadata.getProperty(autoConfiguration.getName() + "." + attribute);
	}

	private Properties loadMetadata() throws IOException {
		Properties metadata = new Properties();
		Enumeration<URL> resources = getClass().getClassLoader()
			.getResources("META-INF/spring-autoconfigure-metadata.properties");
		while (resources.hasMoreElements()) {
			try (InputStream in = resources.nextElement().openStream()) {
				metadata.load(in);
			}
		}
		return metadata;
	}

}
