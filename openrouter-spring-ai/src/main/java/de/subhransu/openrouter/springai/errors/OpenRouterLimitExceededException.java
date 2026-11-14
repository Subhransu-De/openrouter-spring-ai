package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatusCode;

/**
 * A client-side resource limit stopped an OpenRouter response before it could consume
 * unbounded memory or time.
 *
 * @author Subhransu De
 */
public final class OpenRouterLimitExceededException extends NonTransientAiException implements OpenRouterHttpFailure {

	public enum Limit {

		BLOCKING_RESPONSE_BODY_BYTES("blocking response body", "bytes",
				"spring.ai.openrouter.connection.max-response-body-size", false),

		BLOCKING_ERROR_BODY_BYTES("blocking error body", "bytes", "spring.ai.openrouter.connection.max-error-body-size",
				false),

		STREAMING_ERROR_BODY_BYTES("streaming error body", "bytes",
				"spring.ai.openrouter.connection.max-error-body-size", false),

		/**
		 * One SSE event, or the complete JSON body a streamed image request can receive
		 * instead of events.
		 */
		STREAMING_EVENT_BYTES("streamed event or JSON response body", "bytes",
				"spring.ai.openrouter.connection.max-response-body-size", false),

		STREAMING_TOOL_CALL_BYTES("streamed tool-call assembly", "bytes",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-size", true),

		STREAMING_TOOL_CALL_CHUNKS("streamed tool-call assembly", "chunks",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks", true),

		STREAMING_TOOL_CALL_DURATION("streamed tool-call assembly", "milliseconds",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-duration", true),

		CHAT_STATE_BYTES("Chat Completions retained state", "bytes",
				"spring.ai.openrouter.chat.streaming-state.max-size", true),

		CHAT_STATE_CHOICES("Chat Completions active state", "choices",
				"spring.ai.openrouter.chat.streaming-state.max-choices", true),

		/** Retained audio and transcript of a Chat Completions audio stream. Fixed. */
		CHAT_AUDIO_BYTES("Chat Completions retained audio and transcript", "bytes", null, true),

		/** Choices that carry audio in one Chat Completions stream. Fixed. */
		CHAT_AUDIO_CHOICES("Chat Completions audio", "choices", null, true),

		RESPONSES_STATE_BYTES("Responses cumulative state", "bytes",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-size", true),

		RESPONSES_STATE_CHUNKS("Responses cumulative state", "chunks",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks", true),

		RESPONSES_STATE_ITEMS("Responses output snapshot", "items",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-chunks", true),

		RESPONSES_STATE_DURATION("Responses cumulative state", "milliseconds",
				"spring.ai.openrouter.chat.tool-call-aggregation.max-duration", true);

		private final String description;

		private final String unit;

		private final @Nullable String property;

		private final boolean streamState;

		Limit(String description, String unit, @Nullable String property, boolean streamState) {
			this.description = description;
			this.unit = unit;
			this.property = property;
			this.streamState = streamState;
		}

		/**
		 * Return the property that configures this limit.
		 * @return the property name, or {@code null} for a fixed limit
		 */
		public @Nullable String getProperty() {
			return this.property;
		}

		/**
		 * Return whether this limit guards state the library assembles across stream
		 * events, rather than the size of one HTTP body or event.
		 * @return {@code true} for a stream-state limit
		 */
		public boolean isStreamStateLimit() {
			return this.streamState;
		}

	}

	private final Limit limit;

	private final long configuredLimit;

	private final long observedValue;

	private final OpenRouterHttpFailureDetails failure;

	public OpenRouterLimitExceededException(Limit limit, long configuredLimit, long observedValue,
			@Nullable String endpoint, @Nullable HttpStatusCode statusCode, @Nullable String responseBody,
			@Nullable OpenRouterErrorDetails errorDetails) {
		super(message(limit, configuredLimit, observedValue, endpoint));
		this.limit = limit;
		this.configuredLimit = configuredLimit;
		this.observedValue = observedValue;
		this.failure = new OpenRouterHttpFailureDetails(statusCode, responseBody, errorDetails, null, endpoint);
	}

	private static String message(Limit limit, long configuredLimit, long observedValue, @Nullable String endpoint) {
		String target = endpoint != null ? "OpenRouter " + endpoint : "OpenRouter";
		if (limit.property == null) {
			return target + " exceeded the fixed " + limit.description + " limit of " + configuredLimit + " "
					+ limit.unit + " (observed at least " + observedValue + ")";
		}
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

	/**
	 * Return whether the library raised this limit on state it assembles across stream
	 * events. A stream-state limit has no HTTP status, no response body and empty error
	 * details.
	 * @return {@code true} for a stream-state limit
	 * @see Limit#isStreamStateLimit()
	 */
	public boolean isStreamStateLimit() {
		return this.limit.isStreamStateLimit();
	}

	/**
	 * A limit is deterministic for a given configuration, so it is never transient, even
	 * when the oversized body was an HTTP error with a transient category.
	 * @return {@code false}
	 */
	@Override
	public boolean isTransient() {
		return false;
	}

	@Override
	public OpenRouterHttpFailureDetails getFailureDetails() {
		return this.failure;
	}

}
