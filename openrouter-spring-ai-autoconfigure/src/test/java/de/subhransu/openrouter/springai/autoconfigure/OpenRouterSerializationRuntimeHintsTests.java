package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterSerializationRuntimeHints;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorResponse;
import de.subhransu.openrouter.springai.errors.TolerantJsonStringDeserializer;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

class OpenRouterSerializationRuntimeHintsTests {

	/**
	 * The only nested builders in the dto package; a new one needs a deliberate decision.
	 */
	private static final Set<String> REQUEST_BUILDERS = Set.of(
			"de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest$Builder",
			"de.subhransu.openrouter.springai.api.dto.ResponsesRequest$Builder");

	@Test
	void registersWireRecordsAndCustomDeserializersWithoutToolManagerInternals() throws Exception {
		RuntimeHints hints = new RuntimeHints();
		new OpenRouterSerializationRuntimeHints().registerHints(hints, getClass().getClassLoader());
		var resources = new PathMatchingResourcePatternResolver()
			.getResources("classpath*:de/subhransu/openrouter/springai/api/dto/*.class");
		assertThat(resources).isNotEmpty();
		var reader = new SimpleMetadataReaderFactory();
		Set<String> skippedBuilders = new TreeSet<>();
		for (var resource : resources) {
			// Package annotations are not wire types and need no serialization hints.
			if ("package-info.class".equals(resource.getFilename())) {
				continue;
			}
			Class<?> type = Class.forName(reader.getMetadataReader(resource).getClassMetadata().getClassName());
			// Request builders run in caller code and are never serialized.
			if (REQUEST_BUILDERS.contains(type.getName())) {
				skippedBuilders.add(type.getName());
				continue;
			}
			assertThat(hints.reflection().getTypeHint(type)).as(type.getName()).isNotNull();
			for (var constructor : type.getDeclaredConstructors()) {
				assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(constructor).test(hints))
					.as(constructor.toString())
					.isTrue();
			}
			if (type.isRecord()) {
				for (var component : type.getRecordComponents()) {
					assertThat(
							RuntimeHintsPredicates.reflection().onMethodInvocation(component.getAccessor()).test(hints))
						.as(component.toString())
						.isTrue();
				}
			}
		}
		assertThat(skippedBuilders).as("request builders found in the dto package").isEqualTo(REQUEST_BUILDERS);
		assertThat(hints.reflection().getTypeHint(OpenRouterErrorResponse.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(OpenRouterErrorResponse.Error.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(TolerantJsonStringDeserializer.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(DefaultToolCallingManager.class)).isNull();
	}

	@Test
	void serializationHintsAreRegisteredForAotWithAndWithoutBootAutoConfiguration() {
		// Spring's AOT processing loads registrars from aot.factories, so the wire hints
		// also apply when the core module is used without Boot auto-configuration.
		var registrars = SpringFactoriesLoader.forResourceLocation("META-INF/spring/aot.factories")
			.load(RuntimeHintsRegistrar.class);
		assertThat(registrars).hasAtLeastOneElementOfType(OpenRouterSerializationRuntimeHints.class);
		// With Boot auto-configuration, the API auto-configuration imports the same
		// registrar.
		assertThat(OpenRouterApiAutoConfiguration.class.getAnnotation(ImportRuntimeHints.class).value())
			.containsExactly(OpenRouterSerializationRuntimeHints.class);
	}

}
