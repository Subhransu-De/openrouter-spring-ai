package de.subhransu.openrouter.springai.api.errors;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.stream.Stream;

class OpenRouterApiExceptionTests {

	@ParameterizedTest
	@MethodSource("statusesWithCategories")
	void compatibilityConstructorDerivesTheCategoryFromTheStatus(HttpStatusCode status,
			OpenRouterErrorCategory expected) {
		OpenRouterApiException exception = new OpenRouterApiException("failure", status, "{}");

		assertThat(exception.getStatusCode()).isEqualTo(status);
		assertThat(exception.getCategory()).isEqualTo(expected);
		assertThat(exception.getMessage()).isEqualTo("failure");
		assertThat(exception.getResponseBody()).isEqualTo("{}");
		assertThat(exception.getErrorDetails().message()).isEqualTo("failure");
	}

	@ParameterizedTest
	@MethodSource("hostMessagesWithDiagnostics")
	void hostMessageIsKeptWhateverTheProviderDiagnosticSays(String hostMessage, String diagnostic,
			String sanitizedBody) {
		OpenRouterApiException exception = new OpenRouterApiException(hostMessage, HttpStatus.BAD_GATEWAY, diagnostic);

		assertThat(exception.getMessage()).isEqualTo(hostMessage);
		assertThat(exception.getResponseBody()).isEqualTo(sanitizedBody);
	}

	static Stream<Arguments> hostMessagesWithDiagnostics() {
		return Stream.of(
				Arguments.of(Named.of("a differing diagnostic is never appended", "OpenRouter request failed"),
						"provider\r\ninjected", "provider injected"),
				Arguments.of(Named.of("a diagnostic equal to the host message", "Unauthorized"), "Unauthorized",
						"Unauthorized"));
	}

	static Stream<Arguments> statusesWithCategories() {
		return Stream.of(Arguments.of(Named.of("no status", null), OpenRouterErrorCategory.UNKNOWN),
				Arguments.of(Named.of("HTTP 429", HttpStatus.TOO_MANY_REQUESTS), OpenRouterErrorCategory.RATE_LIMIT),
				Arguments.of(Named.of("HTTP 401", HttpStatus.UNAUTHORIZED), OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("HTTP 503", HttpStatus.SERVICE_UNAVAILABLE),
						OpenRouterErrorCategory.PROVIDER_UNAVAILABLE));
	}

}
