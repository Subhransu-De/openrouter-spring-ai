package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterSerializationRuntimeHints;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorResponse;
import de.subhransu.openrouter.springai.errors.TolerantJsonStringDeserializer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

class OpenRouterSerializationRuntimeHintsTests {

	@Test
	void registersWireRecordsAndCustomDeserializersWithoutToolManagerInternals() throws Exception {
		RuntimeHints hints = new RuntimeHints();
		new OpenRouterSerializationRuntimeHints().registerHints(hints, getClass().getClassLoader());
		var resources = new PathMatchingResourcePatternResolver()
			.getResources("classpath*:de/subhransu/openrouter/springai/api/dto/*.class");
		assertThat(resources).isNotEmpty();
		var reader = new SimpleMetadataReaderFactory();
		for (var resource : resources) {
			// Package annotations are not wire types and need no serialization hints.
			if ("package-info.class".equals(resource.getFilename())) {
				continue;
			}
			Class<?> type = Class.forName(reader.getMetadataReader(resource).getClassMetadata().getClassName());
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
		assertThat(hints.reflection().getTypeHint(OpenRouterErrorResponse.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(OpenRouterErrorResponse.Error.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(TolerantJsonStringDeserializer.class)).isNotNull();
		assertThat(hints.reflection().getTypeHint(DefaultToolCallingManager.class)).isNull();
	}

	@Test
	void apiImportsSerializationHintsWithChatDisabled() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
			.withPropertyValues("spring.ai.openrouter.api-key=synthetic", "spring.ai.model.chat=none",
					"spring.ai.model.embedding=openrouter", "spring.ai.model.image=none")
			.run(context -> {
				assertThat(context).hasSingleBean(OpenRouterApi.class);
				assertThat(Arrays
					.asList(OpenRouterApiAutoConfiguration.class.getAnnotation(ImportRuntimeHints.class).value()))
					.containsExactly(OpenRouterSerializationRuntimeHints.class);
			});
	}

}
