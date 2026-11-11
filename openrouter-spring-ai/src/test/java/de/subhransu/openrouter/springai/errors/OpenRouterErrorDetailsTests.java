package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenRouterErrorDetailsTests {

	@ParameterizedTest(name = "code {0}, error type {1} -> {3}")
	@CsvSource(value = { "429|NULL|slow down|RATE_LIMIT", "401|NULL|invalid key|AUTHENTICATION",
			"402|NULL|add credits|BILLING_CREDITS", "NULL|rate_limit_exceeded|slow down|RATE_LIMIT",
			"NULL|NULL|something unexpected|UNKNOWN" }, delimiter = '|', nullValues = "NULL")
	void compatibilityConstructorDerivesTheCategory(String code, String errorType, String message,
			OpenRouterErrorCategory expected) {
		OpenRouterErrorDetails details = new OpenRouterErrorDetails(code, message, errorType, null, null);

		assertThat(details.category()).isEqualTo(expected);
		assertThat(details.code()).isEqualTo(code);
		assertThat(details.message()).isEqualTo(message);
		assertThat(details.errorType()).isEqualTo(errorType);
	}

	@Test
	void canonicalConstructorDefaultsAMissingCategoryToUnknown() {
		OpenRouterErrorDetails details = new OpenRouterErrorDetails("429", "slow down", null, null, null, null);

		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	@Test
	void canonicalConstructorSanitizesProviderText() {
		OpenRouterErrorDetails details = new OpenRouterErrorDetails("429", "first line\nBearer secret-token", null,
				null, null, OpenRouterErrorCategory.RATE_LIMIT);

		assertThat(details.message()).isEqualTo("first line Bearer [REDACTED]");
		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
	}

}
