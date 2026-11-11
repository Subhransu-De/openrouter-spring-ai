package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class OpenRouterHttpExceptionFactoryTests {

	private static final String OVERLOADED_ERROR = "{\"error\":{\"message\":\"overloaded\"}}";

	private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter.RFC_1123_DATE_TIME;

	private static final DateTimeFormatter RFC_850 = DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss 'GMT'",
			Locale.US);

	private static final DateTimeFormatter ASCTIME = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US);

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterHttpExceptionFactory factory = new OpenRouterHttpExceptionFactory(this.json,
			"configured-credential");

	@ParameterizedTest
	@MethodSource("retryAfterHeadersInThePast")
	void retryAfterParsesDeltaSecondsAndPastHttpDates(String header, Duration delay, Instant retryAt) {
		assertThat(retryAfter(header)).isEqualTo(new OpenRouterRetryAfter(header, delay, retryAt));
	}

	static Stream<Arguments> retryAfterHeadersInThePast() {
		Instant past = Instant.parse("2000-08-01T07:00:00Z");
		return Stream.of(Arguments.of(Named.of("zero seconds, padded", " 0 "), Duration.ZERO, null),
				Arguments.of(Named.of("IMF-fixdate in the past", "Tue, 01 Aug 2000 07:00:00 GMT"), Duration.ZERO, past),
				Arguments.of(Named.of("RFC 850 in the past", "Tuesday, 01-Aug-00 07:00:00 GMT"), Duration.ZERO, past),
				Arguments.of(Named.of("asctime in the past", "Tue Aug  1 07:00:00 2000"), Duration.ZERO, past));
	}

	@ParameterizedTest
	@MethodSource("httpDateFormats")
	void retryAfterHttpDatesInTheFutureYieldTheRemainingDelay(DateTimeFormatter format) {
		ZonedDateTime target = ZonedDateTime.now(ZoneOffset.UTC).plusHours(1).truncatedTo(ChronoUnit.SECONDS);
		String header = format.format(target);

		Instant before = Instant.now();
		OpenRouterRetryAfter parsed = retryAfter(header);
		Instant after = Instant.now();

		assertThat(parsed.value()).isEqualTo(header);
		assertThat(parsed.retryAt()).isEqualTo(target.toInstant());
		// The production parser reads the system clock and has no clock input, so the
		// delay can only be bracketed by two instants taken around the call.
		assertThat(parsed.delay()).isBetween(Duration.between(after, target.toInstant()),
				Duration.between(before, target.toInstant()));
	}

	static Stream<Arguments> httpDateFormats() {
		return Stream.of(Arguments.of(Named.of("IMF-fixdate", IMF_FIXDATE)), Arguments.of(Named.of("RFC 850", RFC_850)),
				Arguments.of(Named.of("asctime", ASCTIME)));
	}

	@Test
	void rfc850DateExactlyFiftyYearsAheadStaysInThisCentury() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		// The parser reads two-digit years in the current system century, so the case is
		// meaningful only while the target stays in it; it would skip from the year 2050.
		assumeThat(now.plusYears(50).getYear() / 100).isEqualTo(now.getYear() / 100);
		ZonedDateTime target = now.plusYears(50).truncatedTo(ChronoUnit.SECONDS);

		OpenRouterRetryAfter parsed = retryAfter(RFC_850.format(target));

		assertThat(parsed.retryAt()).isEqualTo(target.toInstant());
		assertThat(parsed.delay()).isPositive();
	}

	@Test
	void rfc850DateBeyondFiftyYearsAheadUsesThePriorCentury() {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		assumeThat(now.plusYears(51).getYear() / 100).isEqualTo(now.getYear() / 100);
		ZonedDateTime target = now.plusYears(51).truncatedTo(ChronoUnit.SECONDS);

		OpenRouterRetryAfter parsed = retryAfter(RFC_850.format(target));

		assertThat(parsed.retryAt()).isEqualTo(target.minusYears(100).toInstant());
		assertThat(parsed.delay()).isEqualTo(Duration.ZERO);
	}

	@ParameterizedTest
	@ValueSource(strings = { "-1", "invalid", ",", "abc", "abcd", "abcde", "Sunday," })
	void invalidRetryAfterRetainsOriginalValueWithoutInventingADelay(String value) {
		assertThat(retryAfter(value)).isEqualTo(new OpenRouterRetryAfter(value, null, null));
	}

	static Stream<Arguments> bodiesWithoutErrorDetails() {
		return Stream
			.of(null, "", " ", "null", "{}", "{\"error\":null}",
					"{\"error\":{\"code\":null,\"message\":null,\"metadata\":null}}",
					"{\"error\":{\"code\":null,\"message\":null,\"metadata\":null},\"padding\":\"truncated",
					"{\"unrelated\":{\"error_type\":\"authentication\"},\"padding\":\"truncated",
					"{\"unrelated\":[{\"error_type\":\"authentication\"}],\"padding\":\"truncated",
					"{\"error\":{\"message\":\"an error value that crosses the configured excerpt limit")
			.map(body -> Arguments.of(Named.of("body [" + body + "]", body)));
	}

	@ParameterizedTest
	@MethodSource("bodiesWithoutErrorDetails")
	void missingErrorDetailsPreserveStatusFallbackForWholeBodies(@Nullable String body) {
		OpenRouterHttpException whole = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatus.TOO_MANY_REQUESTS, HttpHeaders.EMPTY, body);

		assertStatusFallback(whole);
	}

	@ParameterizedTest
	@MethodSource("bodiesWithoutErrorDetails")
	void missingErrorDetailsPreserveStatusFallbackForTruncatedBodies(@Nullable String body) {
		OpenRouterHttpException truncated = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.TOO_MANY_REQUESTS, body, 10, 11);

		assertStatusFallback(truncated);
	}

	private static void assertStatusFallback(OpenRouterHttpException exception) {
		assertThat(exception.getErrorDetails().category()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
		assertThat(exception.getErrorDetails())
			.extracting(OpenRouterErrorDetails::code, OpenRouterErrorDetails::message,
					OpenRouterErrorDetails::errorType, OpenRouterErrorDetails::providerCode)
			.containsOnlyNulls();
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

	private enum Transience {

		TRANSIENT(OpenRouterTransientApiException.class), NON_TRANSIENT(OpenRouterNonTransientApiException.class);

		private final Class<? extends OpenRouterHttpException> type;

		Transience(Class<? extends OpenRouterHttpException> type) {
			this.type = type;
		}

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
		ObjectNode root = this.json.createObjectNode();
		root.put("future_root", true);
		ObjectNode error = root.putObject("error");
		if (errorType != null) {
			error.put("error_type", errorType);
		}
		error.put("code", code).put("message", message).put("future_field", 42);
		String body = this.json.writeValueAsString(root);

		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/chat/completions",
				HttpStatusCode.valueOf(status), HttpHeaders.EMPTY, body);

		assertThat(exception.getCategory()).isEqualTo(expected);
		assertThat(exception.getErrorDetails().code()).isEqualTo(code);
		assertThat(exception.getErrorDetails().message()).isEqualTo(message);
		assertThat(exception.getResponseBody()).isEqualTo(body);
	}

	@ParameterizedTest
	@MethodSource("bodiesWithExpectedTransience")
	void bodyShapeAndStatusDecideTransientOrNonTransientClassification(int status, String body, Transience transience,
			OpenRouterErrorCategory category) {
		OpenRouterHttpException exception = (OpenRouterHttpException) this.factory.create("/responses",
				HttpStatusCode.valueOf(status), HttpHeaders.EMPTY, body);

		assertThat(exception).isInstanceOf(transience.type);
		assertThat(exception.getCategory()).isEqualTo(category);
	}

	static Stream<Arguments> bodiesWithExpectedTransience() {
		return Stream.of(Arguments.of(Named.of("canonical deterministic detail overrides a transient status", 503), """
				{"error":{"code":503,"message":"invalid key",
				  "metadata":{"error_type":"authentication"}}}
				""", Transience.NON_TRANSIENT, OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("recognized deterministic code overrides a transient status", 500), """
						{"error":{"code":"invalid_api_key","message":"invalid key"}}
						""", Transience.NON_TRANSIENT, OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("recognized numeric code overrides a transient status", 500), """
						{"error":{"code":"401","message":"invalid key"}}
						""", Transience.NON_TRANSIENT, OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("root error type without an error object overrides the status", 503), """
						{"error_type":"authentication"}
						""", Transience.NON_TRANSIENT, OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("connection refusal falls back to the provider status", 503), """
						{"error":{"message":"upstream connection refused"}}
						""", Transience.TRANSIENT, OpenRouterErrorCategory.PROVIDER_UNAVAILABLE),
				Arguments.of(Named.of("canonical rate limit is transient", 429), """
						{"error":{"code":429,"message":"slow down",
						  "metadata":{"error_type":"rate_limit_error"}}}
						""", Transience.TRANSIENT, OpenRouterErrorCategory.RATE_LIMIT),
				Arguments.of(Named.of("unknown status and error type default to non-transient", 520), """
						{"error":{"code":520,"message":"new failure",
						  "metadata":{"error_type":"future_error"}}}
						""", Transience.NON_TRANSIENT, OpenRouterErrorCategory.UNKNOWN));
	}

	@ParameterizedTest
	@MethodSource("truncatedExcerpts")
	void truncatedBodyPreservesTheErrorTypeWherever(String excerpt, String errorType,
			OpenRouterErrorCategory category) {
		OpenRouterLimitExceededException exception = this.factory.createErrorBodyLimit("/responses",
				HttpStatus.SERVICE_UNAVAILABLE, excerpt, 100, 101);

		assertThat(exception.getErrorDetails().errorType()).isEqualTo(errorType);
		assertThat(exception.getErrorDetails().category()).isEqualTo(category);
	}

	static Stream<Arguments> truncatedExcerpts() {
		return Stream.of(Arguments.of(Named.of("nested in the error object", """
				{"error":{"error_type":"authentication","code":"invalid_api_key","message":"invalid key"},
				 "padding":"truncated
				"""), "authentication", OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("root after the error object", """
						{"error":{"code":"invalid_api_key","message":"invalid key"},
						 "error_type":"authentication","padding":"truncated
						"""), "authentication", OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("root without an error object", """
						{"error_type":"authentication","padding":"truncated
						"""), "authentication", OpenRouterErrorCategory.AUTHENTICATION),
				Arguments.of(Named.of("non-string root after the error object", """
						{"error":{"code":"upstream_unavailable","message":"unavailable"},
						 "error_type":{"kind":"future_error"},"padding":"truncated
						"""), "{\"kind\":\"future_error\"}", OpenRouterErrorCategory.UNKNOWN));
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
