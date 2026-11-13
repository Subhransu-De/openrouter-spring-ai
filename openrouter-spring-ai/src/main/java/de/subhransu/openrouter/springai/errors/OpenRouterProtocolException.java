package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import org.springframework.ai.retry.NonTransientAiException;

/**
 * An OpenRouter response does not satisfy the minimum protocol shape.
 *
 * @author Subhransu De
 */
public final class OpenRouterProtocolException extends NonTransientAiException implements OpenRouterFailure {

	/**
	 * What part of the protocol the response broke.
	 */
	public enum Kind {

		/** The response had no body, or its body was the JSON value {@code null}. */
		EMPTY_BODY,

		/** The body or an event was not valid JSON of the expected shape. */
		DECODE,

		/** A required field or value was absent, null or blank. */
		MISSING_FIELD,

		/**
		 * The values were present but broke a rule, such as conflicting stream fragments,
		 * a count or index mismatch, or an unsupported combination.
		 */
		CONTRACT

	}

	private final Kind kind;

	private final @Nullable String endpoint;

	/**
	 * Create a {@link Kind#CONTRACT} failure with no endpoint.
	 * @param message the message
	 */
	public OpenRouterProtocolException(String message) {
		this(Kind.CONTRACT, null, message);
	}

	/**
	 * Create a {@link Kind#CONTRACT} failure with no endpoint.
	 * @param message the message
	 * @param cause the cause
	 */
	public OpenRouterProtocolException(String message, Throwable cause) {
		this(Kind.CONTRACT, null, message, cause);
	}

	/**
	 * Create a protocol failure.
	 * @param kind what part of the protocol the response broke
	 * @param endpoint the OpenRouter endpoint, such as {@code /chat/completions}
	 * @param message the message
	 */
	public OpenRouterProtocolException(Kind kind, @Nullable String endpoint, String message) {
		super(message);
		this.kind = kind;
		this.endpoint = endpoint;
	}

	/**
	 * Create a protocol failure.
	 * @param kind what part of the protocol the response broke
	 * @param endpoint the OpenRouter endpoint, such as {@code /chat/completions}
	 * @param message the message
	 * @param cause the cause
	 */
	public OpenRouterProtocolException(Kind kind, @Nullable String endpoint, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
		this.endpoint = endpoint;
	}

	public Kind getKind() {
		return this.kind;
	}

	/**
	 * Return the endpoint whose response broke the protocol.
	 * @return the endpoint, or {@code null} when it is not known
	 */
	public @Nullable String getEndpoint() {
		return this.endpoint;
	}

	/**
	 * A protocol failure carries no provider category.
	 * @return {@link OpenRouterErrorCategory#UNKNOWN}
	 */
	@Override
	public OpenRouterErrorCategory getCategory() {
		return OpenRouterErrorCategory.UNKNOWN;
	}

}
