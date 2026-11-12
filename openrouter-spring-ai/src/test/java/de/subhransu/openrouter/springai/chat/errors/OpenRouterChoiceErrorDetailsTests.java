package de.subhransu.openrouter.springai.chat.errors;

import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("api")
@Tag("api.response")
class OpenRouterChoiceErrorDetailsTests {

	@Test
	void compatibilityConstructorDerivesCategory() {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", "401", "invalid credentials", "authentication", null, Map.of(), null, false);

		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void compatibilityConstructorDerivesCategoryFromNumericCode() {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", "503", "unavailable", null, null, Map.of(), null, false);

		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
	}

	@Test
	void choiceFailuresWithoutDetailsReturnUnknownCategory() {
		assertThat(new OpenRouterTransientChoiceException("failed", null).getCategory())
			.isEqualTo(OpenRouterErrorCategory.UNKNOWN);
		assertThat(new OpenRouterNonTransientChoiceException("failed", null).getCategory())
			.isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	@Test
	void fullConstructorNormalizesNullCategory() {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", null, "future_code", "failure", "future_error", null, Map.of(), null, false, null);

		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

}
