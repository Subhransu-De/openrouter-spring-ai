package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import org.springframework.util.unit.DataSize;
import org.springframework.beans.BeanUtils;

class OpenRouterPropertyNullabilityTests {

	@Test
	void requiredAggregationDefaultsKeepNonNullContracts() throws ReflectiveOperationException {
		var properties = new OpenRouterChatProperties();
		assertNonNullProperty(properties, "ToolCallAggregation", OpenRouterChatProperties.ToolCallAggregation.class);
		assertNonNullProperty(properties.getToolCallAggregation(), "MaxSize", DataSize.class);
		assertNonNullProperty(properties.getToolCallAggregation(), "MaxDuration", Duration.class);
	}

	private void assertNonNullProperty(Object bean, String name, Class<?> type) throws ReflectiveOperationException {
		var getter = bean.getClass().getMethod("get" + name);
		var setter = bean.getClass().getMethod("set" + name, type);
		assertThat(bean.getClass().getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
		assertThat(getter.getAnnotatedReturnType().isAnnotationPresent(Nullable.class)).isFalse();
		assertThat(setter.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class)).isFalse();
		assertThat(getter.invoke(bean)).isNotNull();
	}

	@Test
	void optionalPropertiesExposeNullableJavaContracts() throws ReflectiveOperationException {
		assertThat(OpenRouterChatProperties.class.getPackage().isAnnotationPresent(NullMarked.class)).isTrue();
		for (Class<?> type : List.of(OpenRouterChatProperties.class, OpenRouterEmbeddingProperties.class,
				OpenRouterImageProperties.class)) {
			Object properties = type.getConstructor().newInstance();
			for (var field : type.getDeclaredFields()) {
				if (!field.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
					continue;
				}
				var property = BeanUtils.getPropertyDescriptor(type, field.getName());
				assertThat(property).as("public property %s.%s", type.getSimpleName(), field.getName()).isNotNull();
				var getter = property.getReadMethod();
				var setter = property.getWriteMethod();
				assertThat(getter.getAnnotatedReturnType().isAnnotationPresent(Nullable.class)).isTrue();
				assertThat(setter.getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class)).isTrue();
				setter.invoke(properties, new Object[] { null });
				assertThat(getter.invoke(properties)).as(field.getName()).isNull();
			}
			assertThat(type.getMethod("toOptions").invoke(properties)).isNotNull();
		}
	}

}
