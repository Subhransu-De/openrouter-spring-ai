package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

@Tag("http")
@Tag("http.errors")
class OpenRouterErrorEnvelopeTests {

	private final OpenRouterHttpExceptionFactory httpFactory = new OpenRouterHttpExceptionFactory(new ObjectMapper(),
			null);

	@ParameterizedTest(name = "[{index}] root [{0}], nested [{1}], metadata [{2}] -> {3} {4}")
	@CsvSource(value = { "rate_limit_error|NULL|NULL|rate_limit_error|RATE_LIMIT",
			"NULL|authentication|NULL|authentication|AUTHENTICATION", "NULL|NULL|timeout|timeout|TIMEOUT",
			"authentication|rate_limit_error|timeout|authentication|AUTHENTICATION",
			"' '|rate_limit_error|authentication|rate_limit_error|RATE_LIMIT",
			"NULL|' '|permission_denied|permission_denied|AUTHORIZATION",
			"RATE_LIMIT_ERROR|NULL|NULL|RATE_LIMIT_ERROR|RATE_LIMIT",
			"future_error|rate_limit_error|timeout|future_error|UNKNOWN", "NULL|NULL|NULL|NULL|PROVIDER_UNAVAILABLE" },
			delimiter = '|', nullValues = "NULL")
	void bothFactoriesApplyTheSameErrorTypePrecedence(@Nullable String root, @Nullable String nested,
			@Nullable String metadataType, @Nullable String expectedType, OpenRouterErrorCategory expectedCategory) {
		ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("provider_code", "upstream-503");
		if (metadataType != null) {
			metadata.put("error_type", metadataType);
		}
		ObjectNode error = JsonNodeFactory.instance.objectNode().put("code", "503").put("message", "failure");
		if (nested != null) {
			error.put("error_type", nested);
		}
		error.set("metadata", metadata);
		ObjectNode body = JsonNodeFactory.instance.objectNode();
		if (root != null) {
			body.put("error_type", root);
		}
		body.set("error", error);

		String json = body.toString();

		OpenRouterHttpException http = (OpenRouterHttpException) this.httpFactory.create("/responses",
				HttpStatusCode.valueOf(503), HttpHeaders.EMPTY, json);
		OpenRouterHttpException truncated = this.httpFactory.createErrorBodyLimit("/responses",
				HttpStatusCode.valueOf(503), json.substring(0, json.length() - 1), 10, 11);
		OpenRouterApiException inBand = OpenRouterApiExceptionFactory.create("fallback", json,
				new StreamError("503", "failure", metadata, nested), root, "/responses",
				OpenRouterDiagnosticSanitizer.keyless());

		for (OpenRouterErrorDetails details : List.of(http.getErrorDetails(), truncated.getErrorDetails(),
				inBand.getErrorDetails())) {
			assertThat(details.errorType()).isEqualTo(expectedType);
			assertThat(details.category()).isEqualTo(expectedCategory);
			assertThat(details.code()).isEqualTo("503");
			assertThat(details.message()).isEqualTo("failure");
			assertThat(details.providerCode()).isEqualTo("upstream-503");
			assertThat(details.metadata()).isEqualTo(metadata);
		}
		assertThat(inBand.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(503));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void blankRootTypeWithoutAnErrorObjectLeavesNoErrorType(String root) {
		OpenRouterHttpException http = (OpenRouterHttpException) this.httpFactory.create("/responses",
				HttpStatusCode.valueOf(429), HttpHeaders.EMPTY, "{\"error_type\":\"" + root + "\"}");
		OpenRouterApiException inBand = OpenRouterApiExceptionFactory.create("fallback", null, null, root, "/responses",
				OpenRouterDiagnosticSanitizer.keyless());

		assertThat(http.getErrorDetails().errorType()).isNull();
		assertThat(http.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
		assertThat(inBand.getErrorDetails().errorType()).isNull();
		assertThat(inBand.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

}
