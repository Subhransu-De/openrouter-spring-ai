package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterReasoningOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterResponseFormat;
import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.context.properties.bind.BindableRuntimeHintsRegistrar;
import tools.jackson.databind.ObjectMapper;

class OpenRouterNestedPropertyHintsTests {

	private static final String CHAT = "spring.ai.openrouter.chat.";

	private static final String EMBEDDING = "spring.ai.openrouter.embedding.";

	static List<Arguments> nestedRecords() {
		return List.of(Arguments.of(OpenRouterChatProperties.class, OpenRouterProviderPreferences.class),
				Arguments.of(OpenRouterChatProperties.class, OpenRouterReasoningOptions.class),
				Arguments.of(OpenRouterChatProperties.class, OpenRouterAudioOptions.class),
				Arguments.of(OpenRouterChatProperties.class, OpenRouterResponseFormat.class),
				Arguments.of(OpenRouterEmbeddingProperties.class, OpenRouterProviderPreferences.class));
	}

	@ParameterizedTest
	@MethodSource("nestedRecords")
	void registersConstructorBindingHintsForNestedRecords(Class<?> properties, Class<?> nested) throws Exception {
		RuntimeHints hints = new RuntimeHints();
		BindableRuntimeHintsRegistrar.forTypes(properties).registerHints(hints);
		assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(canonicalConstructor(nested)))
			.as("%s binding hint for %s", properties.getSimpleName(), nested.getSimpleName())
			.accepts(hints);
	}

	@Test
	void publishesNestedRoutingAndReasoningMetadata() throws Exception {
		try (var input = getClass().getClassLoader()
			.getResourceAsStream("META-INF/spring-configuration-metadata.json")) {
			assertThat(input).isNotNull();
			List<String> names = new ArrayList<>();
			new ObjectMapper().readTree(input)
				.path("properties")
				.forEach(property -> names.add(property.path("name").asString()));
			assertThat(names).contains(CHAT + "provider.allow-fallbacks", CHAT + "provider.require-parameters",
					CHAT + "provider.data-collection", CHAT + "provider.order", CHAT + "provider.ignore",
					CHAT + "provider.quantizations", CHAT + "provider.sort", CHAT + "reasoning.effort",
					CHAT + "reasoning.max-tokens", CHAT + "reasoning.exclude", CHAT + "reasoning.enabled",
					EMBEDDING + "provider.allow-fallbacks", EMBEDDING + "provider.require-parameters",
					EMBEDDING + "provider.data-collection", EMBEDDING + "provider.order", EMBEDDING + "provider.ignore",
					EMBEDDING + "provider.quantizations", EMBEDDING + "provider.sort");
		}
	}

	private static Constructor<?> canonicalConstructor(Class<?> type) throws NoSuchMethodException {
		return type.getDeclaredConstructor(
				Arrays.stream(type.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new));
	}

}
