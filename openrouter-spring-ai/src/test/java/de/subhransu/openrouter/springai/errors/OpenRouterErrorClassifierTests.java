package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("http")
@Tag("http.errors")
class OpenRouterErrorClassifierTests {

	@ParameterizedTest(name = "[{index}] status {0}, type [{1}], code {2} -> {3}")
	@CsvSource(
			value = { "524|NULL|NULL|TIMEOUT|true", "529|NULL|NULL|PROVIDER_UNAVAILABLE|true",
					"503|NULL|NULL|PROVIDER_UNAVAILABLE|true", "503|' \t'|NULL|PROVIDER_UNAVAILABLE|true",
					"500|timeout_error|NULL|TIMEOUT|true", "500|api_error|NULL|PROVIDER_UNAVAILABLE|true",
					"500|insufficient_system_resources|NULL|PROVIDER_UNAVAILABLE|true",
					"502|provider_unavailable|NULL|PROVIDER_UNAVAILABLE|true",
					"429|RATE_LIMIT_ERROR|NULL|RATE_LIMIT|true", "503|' RATE_LIMIT_ERROR '|NULL|RATE_LIMIT|true",
					"400|NULL|429|RATE_LIMIT|true", "503|authentication|NULL|AUTHENTICATION|false",
					"503|future_error|NULL|UNKNOWN|false", "520|NULL|NULL|UNKNOWN|false" },
			delimiter = '|', nullValues = "NULL")
	void retryabilityFollowsTheNormalizedCategory(int status, @Nullable String errorType, @Nullable String code,
			OpenRouterErrorCategory category, boolean transientFailure) {
		OpenRouterErrorCategory actual = OpenRouterErrorClassifier.category(status, errorType, code, null);

		assertThat(actual).isEqualTo(category);
		assertThat(OpenRouterErrorClassifier.isTransient(actual)).isEqualTo(transientFailure);
	}

}
