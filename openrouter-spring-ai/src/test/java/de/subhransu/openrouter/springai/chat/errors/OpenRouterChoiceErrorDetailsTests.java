package de.subhransu.openrouter.springai.chat.errors;

import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenRouterChoiceErrorDetailsTests {

	@ParameterizedTest
	@MethodSource("compatibilityCategories")
	void compatibilityConstructorDerivesCategory(String code, String errorType, OpenRouterErrorCategory expected) {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", code, "provider message", errorType, null, Map.of(), null, false);

		assertThat(details.category()).isEqualTo(expected);
	}

	static Stream<Arguments> compatibilityCategories() {
		return Stream.of(
				Arguments.of(Named.of("string error type with a numeric code", "401"), "authentication",
						OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("numeric code without an error type", "503"), null,
						OpenRouterErrorCategory.PROVIDER_UNAVAILABLE));
	}

	@Test
	void metadataIsACopyThatLaterChangesToTheSourceCannotReach() {
		Map<String, Object> source = new LinkedHashMap<>();
		source.put("provider_name", "synthetic");
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", "503", "provider message", null, null, source, null, false);

		source.put("added_later", "value");

		assertThat(details.metadata()).containsOnlyKeys("provider_name");
		assertThatThrownBy(() -> details.metadata().put("other", "value"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void partialOutputIsKeptWithItsTruncationFlag() {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", "503", "provider message", null, null, Map.of(), "partial text", true);

		assertThat(details.partialOutput()).isEqualTo("partial text");
		assertThat(details.partialOutputTruncated()).isTrue();
	}

	@ParameterizedTest
	@MethodSource("failuresWithoutDetails")
	void choiceFailuresWithoutDetailsReturnUnknownCategory(OpenRouterChoiceFailure failure) {
		assertThat(failure.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	static Stream<Arguments> failuresWithoutDetails() {
		return Stream.of(Arguments.of(Named.of("transient", new OpenRouterTransientChoiceException("failed", null))),
				Arguments.of(Named.of("non-transient", new OpenRouterNonTransientChoiceException("failed", null))));
	}

	@Test
	void fullConstructorNormalizesNullCategory() {
		OpenRouterChoiceErrorDetails details = new OpenRouterChoiceErrorDetails("response-id", "model", "provider", 0,
				"error", null, "future_code", "failure", "future_error", null, Map.of(), null, false, null);

		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

}
