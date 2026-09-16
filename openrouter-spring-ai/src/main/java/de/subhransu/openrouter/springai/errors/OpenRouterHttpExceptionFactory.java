package de.subhransu.openrouter.springai.errors;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.StringUtils;

/**
 * Central classification and safe-detail extraction for all OpenRouter HTTP surfaces.
 *
 * @author Subhransu De
 */
public final class OpenRouterHttpExceptionFactory {

	private static final DateTimeFormatter ASCTIME_FORMATTER = DateTimeFormatter.ofPattern("MMM d HH:mm:ss uuuu",
			Locale.US);

	private final ObjectMapper objectMapper;

	private final String apiKey;

	private final Clock clock;

	public OpenRouterHttpExceptionFactory(ObjectMapper objectMapper, String apiKey) {
		this(objectMapper, apiKey, Clock.systemUTC());
	}

	OpenRouterHttpExceptionFactory(ObjectMapper objectMapper, String apiKey, Clock clock) {
		this.objectMapper = objectMapper;
		this.apiKey = apiKey;
		this.clock = clock;
	}

	public RuntimeException create(String endpoint, HttpStatusCode statusCode, HttpHeaders headers,
			String responseBody) {
		String safeBody = OpenRouterExceptionMessage.sanitize(responseBody, this.apiKey);
		OpenRouterErrorDetails details = parseDetails(responseBody, statusCode.value());
		if (details == null) {
			details = statusDetails(statusCode.value());
		}
		OpenRouterRetryAfter retryAfter = parseRetryAfter(headers.getFirst(HttpHeaders.RETRY_AFTER));
		String message = "OpenRouter " + endpoint + " request failed with status " + statusCode;
		if (OpenRouterErrorClassifier.isTransient(details.category())) {
			return new OpenRouterTransientApiException(message, statusCode, safeBody, details, retryAfter, endpoint);
		}
		return new OpenRouterNonTransientApiException(message, statusCode, safeBody, details, retryAfter, endpoint);
	}

	public OpenRouterLimitExceededException createErrorBodyLimit(String endpoint, HttpStatusCode statusCode,
			String responseBodyExcerpt, long configuredLimit, long observedValue) {
		return createErrorBodyLimit(endpoint, statusCode, responseBodyExcerpt, configuredLimit, observedValue,
				OpenRouterLimitExceededException.Limit.BLOCKING_ERROR_BODY_BYTES);
	}

	public OpenRouterLimitExceededException createErrorBodyLimit(String endpoint, HttpStatusCode statusCode,
			String responseBodyExcerpt, long configuredLimit, long observedValue,
			OpenRouterLimitExceededException.Limit limit) {
		String safeBody = OpenRouterExceptionMessage.sanitize(responseBodyExcerpt, this.apiKey);
		OpenRouterErrorDetails details = parseDetails(responseBodyExcerpt, statusCode.value());
		if (details == null) {
			details = parseDetailsFromPrefix(responseBodyExcerpt, statusCode.value());
		}
		if (details == null) {
			details = statusDetails(statusCode.value());
		}
		return new OpenRouterLimitExceededException(limit, configuredLimit, observedValue, endpoint, statusCode,
				safeBody, details);
	}

	private OpenRouterErrorDetails statusDetails(int statusCode) {
		return new OpenRouterErrorDetails(null, null, null, null, null,
				OpenRouterErrorClassifier.category(statusCode, null, null, null));
	}

	private OpenRouterErrorDetails parseDetails(String responseBody, int statusCode) {
		if (!StringUtils.hasText(responseBody)) {
			return null;
		}
		try {
			OpenRouterErrorResponse response = this.objectMapper.readValue(responseBody, OpenRouterErrorResponse.class);
			return response != null ? details(response.error(), response.errorType(), statusCode) : null;
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private OpenRouterErrorDetails parseDetailsFromPrefix(String responseBody, int statusCode) {
		if (!StringUtils.hasText(responseBody)) {
			return null;
		}
		String errorType = null;
		JsonNode error = null;
		try (JsonParser parser = this.objectMapper.createParser(responseBody)) {
			JsonToken token;
			while ((token = parser.nextToken()) != null) {
				if (token != JsonToken.PROPERTY_NAME) {
					continue;
				}
				String name = parser.currentName();
				JsonToken valueToken = parser.nextToken();
				if ("error_type".equals(name)) {
					errorType = text(parser.readValueAsTree());
				}
				else if ("error".equals(name) && valueToken == JsonToken.START_OBJECT) {
					error = parser.readValueAsTree();
				}
				else if (valueToken == JsonToken.START_OBJECT || valueToken == JsonToken.START_ARRAY) {
					parser.skipChildren();
				}
			}
		}
		catch (JacksonException ex) {
			if (error == null && !StringUtils.hasText(errorType)) {
				return null;
			}
		}
		return details(error, errorType, statusCode);
	}

	private OpenRouterErrorDetails details(OpenRouterErrorResponse.Error error, String rootErrorType, int statusCode) {
		if (error == null) {
			return rootTypeDetails(rootErrorType, statusCode);
		}
		JsonNode metadata = error.metadata();
		String errorType = rootErrorType;
		if (!StringUtils.hasText(errorType)) {
			errorType = error.errorType();
		}
		if (!StringUtils.hasText(errorType) && metadata != null) {
			errorType = text(metadata.get("error_type"));
		}
		String code = text(error.code());
		String message = text(error.message());
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode, errorType, code, message);
		return new OpenRouterErrorDetails(sanitize(code), sanitize(message), sanitize(errorType),
				metadata != null ? sanitize(text(metadata.get("provider_code"))) : null,
				OpenRouterExceptionMessage.sanitizeMetadata(metadata, this.apiKey), category);
	}

	private OpenRouterErrorDetails details(JsonNode error, String rootErrorType, int statusCode) {
		if (error == null || !error.isObject()) {
			return rootTypeDetails(rootErrorType, statusCode);
		}
		JsonNode metadata = error.get("metadata");
		String errorType = rootErrorType;
		if (!StringUtils.hasText(errorType)) {
			errorType = text(error.get("error_type"));
		}
		if (!StringUtils.hasText(errorType) && metadata != null) {
			errorType = text(metadata.get("error_type"));
		}
		String code = text(error.get("code"));
		String message = text(error.get("message"));
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode, errorType, code, message);
		return new OpenRouterErrorDetails(sanitize(code), sanitize(message), sanitize(errorType),
				metadata != null ? sanitize(text(metadata.get("provider_code"))) : null,
				OpenRouterExceptionMessage.sanitizeMetadata(metadata, this.apiKey), category);
	}

	private OpenRouterErrorDetails rootTypeDetails(String errorType, int statusCode) {
		if (!StringUtils.hasText(errorType)) {
			return null;
		}
		OpenRouterErrorCategory category = OpenRouterErrorClassifier.category(statusCode, errorType, null, null);
		return new OpenRouterErrorDetails(null, null, sanitize(errorType), null, null, category);
	}

	private String sanitize(String value) {
		return OpenRouterExceptionMessage.sanitize(value, this.apiKey);
	}

	private String text(JsonNode node) {
		if (node == null || node.isNull()) {
			return null;
		}
		return node.isString() ? node.stringValue() : node.toString();
	}

	private OpenRouterRetryAfter parseRetryAfter(String value) {
		if (!StringUtils.hasText(value)) {
			return null;
		}
		String candidate = value.trim();
		try {
			long seconds = Long.parseLong(candidate);
			if (seconds >= 0) {
				return new OpenRouterRetryAfter(value, Duration.ofSeconds(seconds), null);
			}
		}
		catch (NumberFormatException ex) {
			return parseHttpDate(value, candidate);
		}
		return parseHttpDate(value, candidate);
	}

	private OpenRouterRetryAfter parseHttpDate(String value, String candidate) {
		Instant retryAt = parseHttpDate(candidate);
		if (retryAt == null) {
			return new OpenRouterRetryAfter(value, null, null);
		}
		Duration delay = Duration.between(this.clock.instant(), retryAt);
		return new OpenRouterRetryAfter(value, delay.isNegative() ? Duration.ZERO : delay, retryAt);
	}

	private Instant parseHttpDate(String candidate) {
		Instant imfFixdate = parseImfFixdate(candidate);
		if (imfFixdate != null) {
			return imfFixdate;
		}
		Instant rfc850 = parseRfc850Date(candidate);
		return rfc850 != null ? rfc850 : parseAsctimeDate(candidate);
	}

	private Instant parseImfFixdate(String candidate) {
		try {
			return ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private Instant parseRfc850Date(String candidate) {
		int comma = candidate.indexOf(',');
		if (comma < 0 || comma == candidate.length() - 1) {
			return null;
		}
		int currentYear = ZonedDateTime.now(this.clock).getYear();
		int currentCentury = Math.floorDiv(currentYear, 100) * 100;
		DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive()
			.appendPattern("dd-MMM-")
			.appendValueReduced(ChronoField.YEAR, 2, 2, currentCentury)
			.appendPattern(" HH:mm:ss zzz")
			.toFormatter(Locale.US);
		try {
			ZonedDateTime parsed = ZonedDateTime.parse(candidate.substring(comma + 1).trim(), formatter);
			if (parsed.getYear() > currentYear + 50) {
				parsed = parsed.minusYears(100);
			}
			return parsed.toInstant();
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

	private Instant parseAsctimeDate(String candidate) {
		if (candidate.length() < 5) {
			return null;
		}
		String dateWithoutWeekday = candidate.substring(4).trim().replaceAll("\\s+", " ");
		try {
			return LocalDateTime.parse(dateWithoutWeekday, ASCTIME_FORMATTER).toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException ex) {
			return null;
		}
	}

}
