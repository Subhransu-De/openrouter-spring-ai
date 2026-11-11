package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.BindableRuntimeHintsRegistrar;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

class OpenRouterNestedPropertyHintsTests {

	/**
	 * Every record type that a configuration properties class, or a class nested in it,
	 * holds in a field. A new nested record is picked up without a change here.
	 */
	static List<Arguments> nestedRecords() throws Exception {
		List<Arguments> cases = new ArrayList<>();
		var resolver = new PathMatchingResourcePatternResolver();
		var reader = new SimpleMetadataReaderFactory();
		for (var resource : resolver
			.getResources("classpath*:de/subhransu/openrouter/springai/autoconfigure/*Properties.class")) {
			Class<?> properties = Class.forName(reader.getMetadataReader(resource).getClassMetadata().getClassName());
			if (properties.isAnnotationPresent(ConfigurationProperties.class)) {
				for (Class<?> recordType : recordFieldTypes(properties)) {
					cases.add(Arguments.of(
							Named.of(properties.getSimpleName() + " holds " + recordType.getSimpleName(), properties),
							recordType));
				}
			}
		}
		return cases;
	}

	@Test
	void everyKnownNestedRecordIsFound() throws Exception {
		assertThat(nestedRecords()).extracting(arguments -> ((Class<?>) arguments.get()[1]).getSimpleName())
			.contains("OpenRouterProviderPreferences", "OpenRouterReasoningOptions", "OpenRouterAudioOptions",
					"OpenRouterResponseFormat");
	}

	private static Set<Class<?>> recordFieldTypes(Class<?> properties) {
		Set<Class<?>> records = new LinkedHashSet<>();
		Stream.concat(Stream.of(properties), Arrays.stream(properties.getDeclaredClasses()))
			.flatMap(type -> Arrays.stream(type.getDeclaredFields()))
			.map(field -> field.getType())
			.filter(Class::isRecord)
			.forEach(records::add);
		return records;
	}

	@ParameterizedTest
	@MethodSource("nestedRecords")
	void registersConstructorBindingHintsForNestedRecords(Class<?> properties, Class<?> nested) {
		RuntimeHints hints = new RuntimeHints();
		BindableRuntimeHintsRegistrar.forTypes(properties).registerHints(hints);
		List<TypeReference> components = Arrays.stream(nested.getRecordComponents())
			.map(component -> TypeReference.of(component.getType()))
			.toList();
		assertThat(hints.reflection().getTypeHint(nested))
			.as("%s binding hint for %s", properties.getSimpleName(), nested.getSimpleName())
			.isNotNull()
			.satisfies(hint -> assertThat(hint.constructors()).anySatisfy(constructor -> {
				assertThat(constructor.getParameterTypes()).isEqualTo(components);
				assertThat(constructor.getMode()).isEqualTo(ExecutableMode.INVOKE);
			}));
	}

}
