package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class OpenRouterPropertyNullabilityTests {

	@Test
	void optionalPropertiesExposeNullableJavaContracts() throws ReflectiveOperationException {
		assertThat(OpenRouterChatProperties.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
		for (Class<?> type : List.of(OpenRouterChatProperties.class, OpenRouterEmbeddingProperties.class,
				OpenRouterImageProperties.class)) {
			Object properties = type.getConstructor().newInstance();
			var getter = type.getMethod("getModel");
			var setter = type.getMethod("setModel", String.class);
			assertThat(getter.getAnnotatedReturnType().isAnnotationPresent(Nullable.class)).isTrue();
			assertThat(setter.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class)).isTrue();
			setter.invoke(properties, new Object[] { null });
			assertThat(getter.invoke(properties)).isNull();
			assertThat(type.getMethod("toOptions").invoke(properties)).isNotNull();
		}
	}

}
