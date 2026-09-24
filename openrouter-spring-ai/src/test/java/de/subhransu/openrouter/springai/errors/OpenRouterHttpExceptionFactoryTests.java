package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class OpenRouterHttpExceptionFactoryTests {

	private static final Instant NOW = Instant.parse("2026-08-02T07:00:00Z");

	private static final String OVERLOADED_ERROR = "{\"error\":{\"message\":\"overloaded\"}}";

	private final OpenRouterHttpExceptionFactory factory = new OpenRouterHttpExceptionFactory(new ObjectMapper(),
			"configured-credential", Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@ValueSource(strings = { "-1", "invalid", ",", "abc", "abcd", "abcde", "Sunday," })
	void invalidRetryAfterRetainsOriginalValueWithoutInventingADelay(String value) {
		assertThat(retryAfter(value)).isEqualTo(new OpenRouterRetryAfter(value, null, null));
	}

	@Test
	void zeroRetryAfterIsAnImmediateNumericDelay() {
		assertThat(retryAfter(" 0 ")).isEqualTo(new OpenRouterRetryAfter(" 0 ", Duration.ZERO, null));
	}

	@Test
	void rfc850ExactlyFiftyYearsAheadRemainsInTheCurrentCentury() {
		Instant future = Instant.parse("2076-08-02T07:00:30Z");
		assertThat(retryAfter("Sunday, 02-Aug-76 07:00:30 GMT").retryAt()).isEqualTo(future);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "null", "{}", "{\"error\":null}",
			"{\"error\":{\"code\":null,\"message\":null,\"metadata\":null}}",
			"{\"unrelated\":{\"error_type\":\"authentication\"},\"padding\":\"truncated",
			"{\"unrelated\":[{\"error_type\":\"authentication\"}],\"padding\":\"truncated" })
	void missingErrorDetailsPreserveStatusFallbackForWholeAndTruncatedBodies(String body) {
		OpenRouterHttpException whole = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.EMPTY, body);
		OpenRouterHttpException truncated = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.TOO_MANY_REQUESTS, body, 10, 11);
		for (OpenRouterHttpException exception : List.of(whole, truncated)) {
			assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
			assertThat(exception.getErrorDetails())
				.extracting(OpenRouterErrorDetails::code, OpenRouterErrorDetails::message,
						OpenRouterErrorDetails::errorType, OpenRouterErrorDetails::providerCode)
				.containsOnlyNulls();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "}", ",\"padding\":\"truncated" })
	void metadataDetailsSurviveBothWholeAndTruncatedEnvelopes(String ending) {
		String body = """
				{"error":{"code":null,"message":null,"metadata":{
				"error_type":"authentication","provider_code":"synthetic-code"}}
				""" + ending;
		OpenRouterErrorDetails details = this.factory
			.createErrorBodyLimit("/responses", HttpStatus.SERVICE_UNAVAILABLE, body, 10, 11)
			.getErrorDetails();
		assertThat(details.code()).isNull();
		assertThat(details.message()).isNull();
		assertThat(details.errorType()).isEqualTo("authentication");
		assertThat(details.providerCode()).isEqualTo("synthetic-code");
		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	private OpenRouterRetryAfter retryAfter(String value) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, value);
		return ((OpenRouterHttpException) this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE, headers,
				OVERLOADED_ERROR))
			.getRetryAfter();
	}

	@Test
	void parsesProviderDetailsAndDeltaSecondsRetryAfter() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "60");
		String body = """
				{"error":{"code":429,"message":"slow down","metadata":{
				  "error_type":"rate_limit_exceeded","provider_code":"rate_limited","provider_name":"Acme"
				}}}
				""";

		RuntimeException exception = this.factory.create("/chat/completions", HttpStatus.TOO_MANY_REQUESTS, headers,
				body);

		assertThat(exception).isInstanceOf(OpenRouterTransientApiException.class);
		OpenRouterHttpException httpException = (OpenRouterHttpException) exception;
		assertThat(httpException.getErrorDetails().code()).isEqualTo("429");
		assertThat(httpException.getErrorDetails().message()).isEqualTo("slow down");
		assertThat(httpException.getErrorDetails().errorType()).isEqualTo("rate_limit_exceeded");
		assertThat(httpException.getErrorDetails().providerCode()).isEqualTo("rate_limited");
		assertThat(httpException.getErrorDetails().metadata().get("provider_name").stringValue()).isEqualTo("Acme");
		assertThat(httpException.getRetryAfter().value()).isEqualTo("60");
		assertThat(httpException.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(60));
		assertThat(httpException.getRetryAfter().retryAt()).isNull();
	}

	@Test
	void rootErrorTypeWithoutErrorObjectOverridesStatusFallback() {
		RuntimeException exception = this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE,
				HttpHeaders.EMPTY, "{\"error_type\":\"authentication\"}");

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
		OpenRouterHttpException httpException = (OpenRouterHttpException) exception;
		assertThat(httpException.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
		assertThat(httpException.getErrorDetails().errorType()).isEqualTo("authentication");
	}

	@ParameterizedTest(name = "[{index}] {1} -> {4}")
	@CsvSource(value = { "401|authentication|invalid_api_key|invalid key|AUTHENTICATION",
			"403|permission_denied|forbidden|not allowed|AUTHORIZATION",
			"403|NULL|unmapped|blocked|CONTENT_FILTER_REFUSAL",
			"402|payment_required|insufficient_quota|add credits|BILLING_CREDITS",
			"429|rate_limit_exceeded|rate_limited|slow down|RATE_LIMIT",
			"400|invalid_request|bad_request|malformed request|INVALID_REQUEST",
			"400|NULL|unsupported_parameter|parameter is not supported|UNSUPPORTED_PARAMETER",
			"503|provider_unavailable|upstream_reset|provider down|PROVIDER_UNAVAILABLE",
			"403|content_policy_violation|moderation_block|filtered|CONTENT_FILTER_REFUSAL",
			"504|timeout|deadline_exceeded|timed out|TIMEOUT", "520|future_error|future_code|new failure|UNKNOWN" },
			delimiter = '|', nullValues = "NULL")
	void classifiesRepresentativeErrorTaxonomy(int status, String errorType, String code, String message,
			OpenRouterErrorCategory expected) {
		String errorTypeJson = errorType != null ? "\"error_type\":\"" + errorType + "\"," : "";
		String body = "{\"future_root\":true,\"error\":{" + errorTypeJson + "\"code\":\"" + code + "\",\"message\":\""
				+ message + "\",\"future_field\":42}}";

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/chat/completions",
				HttpStatusCode.valueOf(status), HttpHeaders.EMPTY, body);

		assertThat(exception.getCategory()).isEqualTo(expected);
		assertThat(exception.getErrorDetails().code()).isEqualTo(code);
		assertThat(exception.getErrorDetails().message()).isEqualTo(message);
		assertThat(exception.getResponseBody()).isEqualTo(body);
	}

	@Test
	void parsesHttpDateRetryAfterRelativeToResponseTime() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "Sun, 02 Aug 2026 07:00:30 GMT");

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, headers, OVERLOADED_ERROR);

		assertThat(exception.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(30));
		assertThat(exception.getRetryAfter().retryAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void parsesRfc850RetryAfterRelativeToResponseTime() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "Sunday, 02-Aug-26 07:00:30 GMT");

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, headers, OVERLOADED_ERROR);

		assertThat(exception.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(30));
		assertThat(exception.getRetryAfter().retryAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void parsesAsctimeRetryAfterRelativeToResponseTime() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "Sun Aug  2 07:00:30 2026");

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, headers, OVERLOADED_ERROR);

		assertThat(exception.getRetryAfter().delay()).isEqualTo(Duration.ofSeconds(30));
		assertThat(exception.getRetryAfter().retryAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void rfc850YearMoreThanFiftyYearsAheadUsesPriorCentury() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "Tuesday, 02-Aug-77 07:00:30 GMT");

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, headers, OVERLOADED_ERROR);

		assertThat(exception.getRetryAfter().delay()).isZero();
		assertThat(exception.getRetryAfter().retryAt()).isEqualTo(Instant.parse("1977-08-02T07:00:30Z"));
	}

	@Test
	void pastHttpDateRetryAfterUsesZeroDelayAndRetainsTimestamp() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "Sat, 01 Aug 2026 07:00:00 GMT");

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, headers, OVERLOADED_ERROR);

		assertThat(exception.getRetryAfter().delay()).isZero();
		assertThat(exception.getRetryAfter().retryAt()).isEqualTo(Instant.parse("2026-08-01T07:00:00Z"));
	}

	@Test
	void canonicalDeterministicDetailOverridesOtherwiseTransientStatus() {
		String body = """
				{"error":{"code":503,"message":"invalid key",
				  "metadata":{"error_type":"authentication"}}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
	}

	@Test
	void recognizedDeterministicCodeOverridesOtherwiseTransientStatus() {
		String body = """
				{"error":{"code":"invalid_api_key","message":"invalid key"}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.INTERNAL_SERVER_ERROR,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
		assertThat(((OpenRouterHttpException) exception).getCategory())
			.isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void recognizedNumericCodeOverridesOtherwiseTransientStatus() {
		String body = """
				{"error":{"code":"401","message":"invalid key"}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.INTERNAL_SERVER_ERROR,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
		assertThat(((OpenRouterHttpException) exception).getCategory())
			.isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void connectionRefusalFallsBackToTransientProviderStatus() {
		String body = """
				{"error":{"message":"upstream connection refused"}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterTransientApiException.class);
		assertThat(((OpenRouterHttpException) exception).getCategory())
			.isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
	}

	@Test
	void truncatedBodyPreservesNestedErrorType() {
		String excerpt = """
				{"error":{"error_type":"authentication","code":"invalid_api_key","message":"invalid key"},
				 "padding":"truncated
				""";

		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, excerpt, 100, 101);

		assertThat(exception.getErrorDetails().errorType()).isEqualTo("authentication");
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void truncatedBodyPreservesRootErrorTypeAfterErrorObject() {
		String excerpt = """
				{"error":{"code":"invalid_api_key","message":"invalid key"},
				 "error_type":"authentication","padding":"truncated
				""";

		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, excerpt, 100, 101);

		assertThat(exception.getErrorDetails().errorType()).isEqualTo("authentication");
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void truncatedBodyPreservesRootErrorTypeWithoutErrorObject() {
		String excerpt = """
				{"error_type":"authentication","padding":"truncated
				""";

		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, excerpt, 100, 101);

		assertThat(exception.getErrorDetails().errorType()).isEqualTo("authentication");
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
	}

	@Test
	void truncatedBodyPreservesNonStringRootErrorTypeAfterErrorObject() {
		String excerpt = """
				{"error":{"code":"upstream_unavailable","message":"unavailable"},
				 "error_type":{"kind":"future_error"},"padding":"truncated
				""";

		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, excerpt, 100, 101);

		assertThat(exception.getErrorDetails().errorType()).isEqualTo("{\"kind\":\"future_error\"}");
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	@Test
	void unparseableErrorExcerptPreservesStatusCategory() {
		String excerpt = """
				{"error":{"message":"an error value that crosses the configured excerpt limit
				""";

		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.TOO_MANY_REQUESTS, excerpt, 64, 65);

		assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
	}

	@Test
	void nonStringMessageDoesNotDiscardTypedEnvelope() {
		String body = """
				{"error_type":"authentication","error":{"code":"invalid_api_key",
				  "message":{"detail":"invalid key"},"metadata":{"provider_code":"bad_key"}}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
		OpenRouterHttpException httpException = (OpenRouterHttpException) exception;
		assertThat(httpException.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
		assertThat(httpException.getErrorDetails().code()).isEqualTo("invalid_api_key");
		assertThat(httpException.getErrorDetails().message()).isEqualTo("{\"detail\":\"invalid key\"}");
		assertThat(httpException.getErrorDetails().providerCode()).isEqualTo("bad_key");
	}

	@Test
	void nonStringRootErrorTypeDoesNotDiscardEnvelope() {
		String body = """
				{"error_type":{"kind":"authentication"},"error":{"code":"invalid_api_key",
				  "message":"invalid key","metadata":{"provider_code":"bad_key"}}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.SERVICE_UNAVAILABLE,
				HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
		OpenRouterHttpException httpException = (OpenRouterHttpException) exception;
		assertThat(httpException.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
		assertThat(httpException.getErrorDetails().code()).isEqualTo("invalid_api_key");
		assertThat(httpException.getErrorDetails().errorType()).isEqualTo("{\"kind\":\"authentication\"}");
	}

	@Test
	void inBandNumericCodesUseTheSharedClassifier() {
		assertThat(OpenRouterErrorClassifier.isTransient(502, "provider_unavailable")).isTrue();
		assertThat(OpenRouterErrorClassifier.isTransient(503, "authentication")).isFalse();
	}

	@ParameterizedTest(name = "[{index}] status {0}, type {1} -> transient {2}")
	@CsvSource(
			value = { "524|NULL|true", "529|NULL|true", "500|timeout_error|true", "500|api_error|true",
					"500|insufficient_system_resources|true", "503|authentication|false", "503|future_error|false" },
			delimiter = '|', nullValues = "NULL")
	void alignsRetryabilityWithTransientCategories(int status, String errorType, boolean expected) {
		assertThat(OpenRouterErrorClassifier.isTransient(status, errorType)).isEqualTo(expected);
	}

	@Test
	void canonicalRateLimitErrorIsTransient() {
		String body = """
				{"error":{"code":429,"message":"slow down",
				  "metadata":{"error_type":"rate_limit_error"}}}
				""";

		RuntimeException exception = this.factory.create("/responses", HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.EMPTY,
				body);

		assertThat(exception).isInstanceOf(OpenRouterTransientApiException.class);
	}

	@Test
	void unknownCanonicalErrorTypeDoesNotFallBackToTransientStatus() {
		assertThat(OpenRouterErrorClassifier.isTransient(503, "future_error")).isFalse();
	}

	@Test
	void blankCanonicalErrorTypeFallsBackToTransientStatus() {
		assertThat(OpenRouterErrorClassifier.isTransient(503, " \t")).isTrue();
	}

	@Test
	void nullCanonicalErrorTypeFallsBackToTransientStatus() {
		assertThat(OpenRouterErrorClassifier.isTransient(503, null)).isTrue();
	}

	@Test
	void unknownStatusAndErrorTypeDefaultToNonTransient() {
		String body = """
				{"error":{"code":520,"message":"new failure",
				  "metadata":{"error_type":"future_error"}}}
				""";

		RuntimeException exception = this.factory.create("/images", HttpStatusCode.valueOf(520), HttpHeaders.EMPTY,
				body);

		assertThat(exception).isInstanceOf(OpenRouterNonTransientApiException.class);
	}

	@Test
	void sanitizesConfiguredAndRecognizableCredentialsFromBodyAndMessage() {
		String body = """
				{"authorization":"Bearer configured-credential",
				 "error":{"message":"first line\\nsecond line key sk-anotherSecret123 was rejected",
				          "metadata":{"api_key":"plain-secret","nested":{"token":"Bearer nested-secret"}}}}
				""";

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/embeddings",
				HttpStatus.UNAUTHORIZED, HttpHeaders.EMPTY, body);

		assertThat(exception.getResponseBody()).contains("[REDACTED]")
			.doesNotContain("configured-credential", "sk-anotherSecret123", "plain-secret", "nested-secret", "\n");
		assertThat(exception.getMessage())
			.isEqualTo("OpenRouter /embeddings request failed with status 401 UNAUTHORIZED");
		assertThat(exception.getErrorDetails().message())
			.isEqualTo("first line second line key [REDACTED] was rejected");
		assertThat(exception.getErrorDetails().metadata().toString()).doesNotContain("plain-secret", "nested-secret")
			.contains("[REDACTED]");
	}

}
