package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.core.annotation.AnnotationUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring Boot's starter guidance asks for field Javadoc on every configuration key so
 * IDEs can describe it. The annotation processor turns that Javadoc into the description
 * in the generated metadata. Keys of nested types from the core module get theirs from
 * {@code additional-spring-configuration-metadata.json}, which the processor merges in.
 * The expected keys are derived from the bound classes themselves, so a property that is
 * added to a class and missing from the metadata fails here without a count to update.
 */
class OpenRouterConfigurationMetadataTests {

	private static final String PREFIX = "spring.ai.openrouter.";

	private static final String NESTED_SOURCE_PREFIX = "de.subhransu.openrouter.springai.chat.";

	private static final List<Class<?>> PROPERTY_CLASSES = List.of(OpenRouterCommonProperties.class,
			OpenRouterConnectionProperties.class, OpenRouterChatProperties.class, OpenRouterEmbeddingProperties.class,
			OpenRouterImageProperties.class);

	@Test
	void everyOpenRouterKeyHasADescription() throws IOException {
		assertThat(openRouterProperties()).isNotEmpty()
			.allSatisfy((property) -> assertThat(property.path("description").asString(""))
				.as(property.path("name").asString())
				.isNotBlank());
	}

	@Test
	void everyKeyOfTheBoundClassesIsPublishedInTheMetadata() throws IOException {
		Set<String> published = new TreeSet<>();
		openRouterProperties().forEach((property) -> published.add(property.path("name").asString()));
		Set<String> expected = new TreeSet<>();
		for (Class<?> type : PROPERTY_CLASSES) {
			String prefix = AnnotationUtils.findAnnotation(type, ConfigurationProperties.class).prefix();
			collectKeys(type, prefix, expected, new TreeMap<>());
		}
		assertThat(published).containsAll(expected);
	}

	@Test
	void nestedCoreTypeKeysAreExactlyTheComponentsOfTheirRecordsWithTheirTypes() throws IOException {
		Map<String, String> published = new TreeMap<>();
		openRouterProperties().stream()
			.filter((property) -> property.path("sourceType").asString("").startsWith(NESTED_SOURCE_PREFIX))
			.forEach((property) -> published.put(property.path("name").asString(), property.path("type").asString()));
		Map<String, String> expected = new TreeMap<>();
		for (Class<?> type : PROPERTY_CLASSES) {
			String prefix = AnnotationUtils.findAnnotation(type, ConfigurationProperties.class).prefix();
			collectKeys(type, prefix, new TreeSet<>(), expected);
		}
		assertThat(published).isNotEmpty().containsExactlyInAnyOrderEntriesOf(expected);
	}

	@Test
	void unsetTimeoutStillShowsItsDefault() throws IOException {
		assertThat(openRouterProperties())
			.filteredOn((property) -> property.path("name").asString("").equals(PREFIX + "connection.timeout"))
			.singleElement()
			.satisfies((property) -> assertThat(property.path("defaultValue").asString("")).isEqualTo("2m"));
	}

	// Adds the key of every bindable property to keys. A key from a record of the core
	// module that the property class nests also goes to coreTypeKeys, with its Java type.
	private static void collectKeys(Class<?> type, String prefix, Set<String> keys, Map<String, String> coreTypeKeys) {
		for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(type)) {
			Class<?> propertyType = property.getPropertyType();
			if ("class".equals(property.getName()) || propertyType == null) {
				continue;
			}
			String key = prefix + "." + DataObjectPropertyName.toDashedForm(property.getName());
			if (propertyType.getPackageName().equals(type.getPackageName())) {
				collectKeys(propertyType, key, keys, coreTypeKeys);
			}
			else if (propertyType.isRecord() && propertyType.getName().startsWith(NESTED_SOURCE_PREFIX)) {
				for (var component : propertyType.getRecordComponents()) {
					String componentKey = key + "." + DataObjectPropertyName.toDashedForm(component.getName());
					keys.add(componentKey);
					coreTypeKeys.put(componentKey, component.getGenericType().getTypeName());
				}
			}
			else if (property.getWriteMethod() != null) {
				keys.add(key);
			}
		}
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
