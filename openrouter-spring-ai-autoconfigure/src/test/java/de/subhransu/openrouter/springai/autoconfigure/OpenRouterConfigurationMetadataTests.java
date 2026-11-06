package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot's starter guidance asks for field Javadoc on every configuration key so
 * IDEs can describe it. The annotation processor turns that Javadoc into the description
 * in the generated metadata. Keys of nested types from the core module get theirs from
 * {@code additional-spring-configuration-metadata.json}, which the processor merges in.
 */
class OpenRouterConfigurationMetadataTests {

	private static final String PREFIX = "spring.ai.openrouter.";

	private static final String NESTED_SOURCE_PREFIX = "de.subhransu.openrouter.springai.chat.";

	@Test
	void everyOpenRouterKeyHasADescription() throws IOException {
		List<JsonNode> properties = openRouterProperties();
		assertThat(properties).hasSizeGreaterThanOrEqualTo(84);
		assertThat(properties)
			.filteredOn((property) -> property.path("sourceType").asString("").startsWith(NESTED_SOURCE_PREFIX))
			.as("nested core-type keys")
			.hasSize(24);
		assertThat(properties).allSatisfy(
				(property) -> assertThat(property.path("description").asString("")).as(property.path("name").asString())
					.isNotBlank());
	}

	@Test
	void unsetTimeoutStillShowsItsDefault() throws IOException {
		assertThat(openRouterProperties())
			.filteredOn((property) -> property.path("name").asString("").equals(PREFIX + "connection.timeout"))
			.singleElement()
			.satisfies((property) -> assertThat(property.path("defaultValue").asString("")).isEqualTo("2m"));
	}

	private List<JsonNode> openRouterProperties() throws IOException {
		JsonMapper mapper = new JsonMapper();
		List<JsonNode> own = new ArrayList<>();
		Enumeration<URL> resources = getClass().getClassLoader()
			.getResources("META-INF/spring-configuration-metadata.json");
		while (resources.hasMoreElements()) {
			try (InputStream in = resources.nextElement().openStream()) {
				for (JsonNode property : mapper.readTree(in).path("properties")) {
					if (property.path("name").asString("").startsWith(PREFIX)) {
						own.add(property);
					}
				}
			}
		}
		return own;
	}

}
