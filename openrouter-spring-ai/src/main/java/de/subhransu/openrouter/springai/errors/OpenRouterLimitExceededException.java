package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatusCode;

/**
 * A local resource limit stopped an OpenRouter response before it could consume unbounded
 * memory or time.
 *
 * @author Subhransu De
 */
public final class OpenRouterLimitExceededException extends NonTransientAiException implements OpenRouterHttpException {

	public enum Limit {

		BLOCKING_RESPONSE_BODY_BYTES("blocking response body", "bytes",
				"spring.ai.openrouter.connection.max-response-body-size"),

		BLOCKING_ERROR_BODY_BYTES("blocking error body", "bytes",
				"spring.ai.openrouter.connection.max-error-body-size"),

		STREAMING_ERROR_BODY_BYTES("streaming error body", "bytes",
				"spring.ai.openrouter.connection.max-error-body-size"),

		STREAMING_TOOL_CALL_BYTES("streamed tool-call assembly", "bytes",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-size"),

		STREAMING_TOOL_CALL_CHUNKS("streamed tool-call assembly", "chunks",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks"),

		STREAMING_TOOL_CALL_DURATION("streamed tool-call assembly", "milliseconds",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-duration"),

		RESPONSES_STATE_BYTES("Responses cumulative state", "bytes",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-size"),

		RESPONSES_STATE_CHUNKS("Responses cumulative state", "chunks",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks"),

		RESPONSES_STATE_ITEMS("Responses output snapshot", "items",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks"),

		RESPONSES_STATE_DURATION("Responses cumulative state", "milliseconds",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-duration");

		private final String description;

		private final String unit;

		private final String property;

		Limit(String description, String unit, String property) {
			this.description = description;
			this.unit = unit;
			this.property = property;
		}

		public String getProperty() {
			return this.property;
		}

	}

	private final Limit limit;

	private final long configuredLimit;

	private final long observedValue;

	private final @Nullable HttpStatusCode statusCode;

	private final @Nullable String responseBody;

	private final @Nullable OpenRouterErrorDetails errorDetails;

	private final @Nullable String endpoint;

	public OpenRouterLimitExceededException(Limit limit, long configuredLimit, long observedValue,
			@Nullable String endpoint, @Nullable HttpStatusCode statusCode, @Nullable String responseBody,
			@Nullable OpenRouterErrorDetails errorDetails) {
		super(message(limit, configuredLimit, observedValue, endpoint));
		this.limit = limit;
		this.configuredLimit = configuredLimit;
		this.observedValue = observedValue;
		this.statusCode = statusCode;
		this.responseBody = responseBody;
		this.errorDetails = errorDetails;
		this.endpoint = endpoint;
	}

	private static String message(Limit limit, long configuredLimit, long observedValue, @Nullable String endpoint) {
		String target = endpoint != null ? "OpenRouter " + endpoint : "OpenRouter";
		return target + " exceeded the configured " + limit.description + " limit of " + configuredLimit + " "
				+ limit.unit + " (observed at least " + observedValue + "). Increase `" + limit.property
				+ "` only when the larger payload is expected";
	}

	public Limit getLimit() {
		return this.limit;
	}

	public long getConfiguredLimit() {
		return this.configuredLimit;
	}

	public long getObservedValue() {
		return this.observedValue;
	}

	@Override
	public @Nullable HttpStatusCode getStatusCode() {
		return this.statusCode;
	}

	@Override
	public @Nullable String getResponseBody() {
		return this.responseBody;
	}

	@Override
	public @Nullable OpenRouterErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

	@Override
	public @Nullable OpenRouterRetryAfter getRetryAfter() {
		return null;
	}

	@Override
	public @Nullable String getEndpoint() {
		return this.endpoint;
	}

}
