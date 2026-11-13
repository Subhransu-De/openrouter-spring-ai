package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Validates nullable wire values before they cross into Spring AI contracts. Each
 * instance reports the endpoint whose response it validates.
 *
 * @author OpenRouter Spring AI contributors
 */
final class ResponseValues {

	static final ResponseValues CHAT_COMPLETIONS = new ResponseValues("/chat/completions");

	static final ResponseValues RESPONSES = new ResponseValues("/responses");

	private final String endpoint;

	private ResponseValues(String endpoint) {
		this.endpoint = endpoint;
	}

	@org.springframework.lang.Contract("null, _ -> fail")
	<T> T required(@Nullable T value, String field) {
		if (value == null) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.MISSING_FIELD, this.endpoint,
					"OpenRouter response requires " + field);
		}
		return value;
	}

	// A blank identifier cannot select a tool callback or correlate a tool result.
	@org.springframework.lang.Contract("null, _ -> fail")
	String requiredText(@Nullable String value, String field) {
		if (value == null || value.isBlank()) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.MISSING_FIELD, this.endpoint,
					"OpenRouter response requires a nonblank " + field);
		}
		return value;
	}

	<T> List<T> items(@Nullable List<? extends @Nullable T> values, String field) {
		return values == null ? List.of() : values.stream().map(value -> required(value, field)).toList();
	}

	static <T> void ifPresent(@Nullable T value, java.util.function.Consumer<T> consumer) {
		if (value != null) {
			consumer.accept(value);
		}
	}

}
