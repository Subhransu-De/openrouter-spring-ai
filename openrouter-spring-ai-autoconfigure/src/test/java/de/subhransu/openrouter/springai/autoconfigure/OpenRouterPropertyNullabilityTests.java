package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.BeanUtils;

/**
 * The properties classes publish their nullness through the JSpecify annotations on their
 * public getters and setters. A Kotlin or Java caller relies on them, so a property that
 * accepts null must say so on both accessors and must really return null after it is set
 * to null, and a required one must say the opposite.
 */
class OpenRouterPropertyNullabilityTests {

	static Stream<Arguments> propertiesTypes() {
		return Stream.of(
				Arguments.of(Named.of("chat",
						new PropertiesType(OpenRouterChatProperties.class, OpenRouterChatProperties::new,
								(bean) -> ((OpenRouterChatProperties) bean).toOptions()))),
				Arguments.of(Named.of("embedding",
						new PropertiesType(OpenRouterEmbeddingProperties.class, OpenRouterEmbeddingProperties::new,
								(bean) -> ((OpenRouterEmbeddingProperties) bean).toOptions()))),
				Arguments.of(Named.of("image", new PropertiesType(OpenRouterImageProperties.class,
						OpenRouterImageProperties::new, (bean) -> ((OpenRouterImageProperties) bean).toOptions()))));
	}

	static Stream<Arguments> requiredProperties() {
		OpenRouterChatProperties chat = new OpenRouterChatProperties();
		return Stream.of(Arguments.of(Named.of("tool-call aggregation", chat), "toolCallAggregation"),
				Arguments.of(Named.of("tool-call aggregation max size", chat.getToolCallAggregation()), "maxSize"),
				Arguments.of(Named.of("tool-call aggregation max duration", chat.getToolCallAggregation()),
						"maxDuration"));
	}

	@ParameterizedTest
	@MethodSource("requiredProperties")
	void requiredAggregationDefaultsKeepNonNullContracts(Object bean, String name) throws Exception {
		PropertyDescriptor property = BeanUtils.getPropertyDescriptor(bean.getClass(), name);
		assertThat(property).isNotNull();
		assertThat(property.getReadMethod().getAnnotatedReturnType().isAnnotationPresent(Nullable.class)).isFalse();
		assertThat(property.getWriteMethod().getAnnotatedParameterTypes()[0].isAnnotationPresent(Nullable.class))
			.isFalse();
		assertThat(property.getReadMethod().invoke(bean)).isNotNull();
	}

	@ParameterizedTest
	@MethodSource("propertiesTypes")
	void nullableAccessorsAgreeAndAcceptNull(PropertiesType propertiesType)
			throws IllegalAccessException, InvocationTargetException {
		Object properties = propertiesType.factory().get();
		int nullable = 0;
		for (PropertyDescriptor property : BeanUtils.getPropertyDescriptors(propertiesType.type())) {
			if (property.getReadMethod() == null || property.getWriteMethod() == null
					|| property.getReadMethod().getDeclaringClass() == Object.class) {
				continue;
			}
			boolean nullableGetter = property.getReadMethod()
				.getAnnotatedReturnType()
				.isAnnotationPresent(Nullable.class);
			boolean nullableSetter = property.getWriteMethod().getAnnotatedParameterTypes()[0]
				.isAnnotationPresent(Nullable.class);
			assertThat(nullableSetter)
				.as("%s.%s: getter and setter agree on nullness", propertiesType.type().getSimpleName(),
						property.getName())
				.isEqualTo(nullableGetter);
			if (nullableGetter) {
				nullable++;
				property.getWriteMethod().invoke(properties, new Object[] { null });
				assertThat(property.getReadMethod().invoke(properties)).as(property.getName()).isNull();
			}
		}
		assertThat(nullable).as("nullable properties of %s", propertiesType.type().getSimpleName()).isPositive();
		assertThat(propertiesType.toOptions().apply(properties)).isNotNull();
	}

	record PropertiesType(Class<?> type, Supplier<Object> factory, Function<Object, Object> toOptions) {

	}

}
