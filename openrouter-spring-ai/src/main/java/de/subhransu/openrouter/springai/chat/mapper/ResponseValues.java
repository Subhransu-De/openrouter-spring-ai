package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Validates nullable wire values before they cross into Spring AI contracts.
 *
 * @author OpenRouter Spring AI contributors
 */
final class ResponseValues {

	private ResponseValues() {
	}

	@org.springframework.lang.Contract("null, _ -> fail")
	static <T> T required(@Nullable T value, String field) {
		if (value == null) {
			throw new OpenRouterProtocolException("OpenRouter response requires " + field);
		}
		return value;
	}

	static <T> List<T> items(@Nullable List<? extends @Nullable T> values, String field) {
		return values == null ? List.of() : values.stream().map(value -> required(value, field)).toList();
	}

	static <T> void ifPresent(@Nullable T value, java.util.function.Consumer<T> consumer) {
		if (value != null) {
			consumer.accept(value);
		}
	}

}
