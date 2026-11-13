package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;

/**
 * An OpenRouter request failed in transport: it timed out, could not connect, or the
 * connection failed while the request or response was in flight. The original transport
 * exception, such as Spring's {@code ResourceAccessException} or
 * {@code WebClientRequestException}, or Reactor's {@code TimeoutException}, is the cause.
 *
 * <p>
 * This is not a Spring AI {@code TransientAiException}. The blocking retry policy still
 * sees the original exception, so it retries exactly the failures it retried before this
 * type existed: Spring AI's default policy retries a blocking transport failure because
 * it includes {@code ResourceAccessException}. Streams do not retry transport failures.
 *
 * @author Subhransu De
 */
public final class OpenRouterTransportException extends RuntimeException implements OpenRouterFailure {

	/**
	 * What failed in transport.
	 */
	public enum Kind {

		/**
		 * A connect, read, response or stream idle timeout expired.
		 */
		TIMEOUT,

		/**
		 * The connection could not be established, for example because the host could not
		 * be resolved or refused the connection.
		 */
		CONNECT,

		/**
		 * Another I/O failure, such as a connection that closed while the request or
		 * response was in flight.
		 */
		IO

	}

	private final Kind kind;

	private final @Nullable String endpoint;

	/**
	 * Create a transport failure.
	 * @param kind what failed
	 * @param endpoint the OpenRouter endpoint, such as {@code /chat/completions}
	 * @param cause the original transport exception
	 */
	public OpenRouterTransportException(Kind kind, @Nullable String endpoint, Throwable cause) {
		super((endpoint != null ? "OpenRouter " + endpoint : "OpenRouter") + " request failed in transport: " + kind,
				cause);
		this.kind = kind;
		this.endpoint = endpoint;
	}

	public Kind getKind() {
		return this.kind;
	}

	/**
	 * Return the endpoint of the failed request.
	 * @return the endpoint, or {@code null} when it is not known
	 */
	public @Nullable String getEndpoint() {
		return this.endpoint;
	}

	/**
	 * Return {@link OpenRouterErrorCategory#TIMEOUT} for a timeout, otherwise
	 * {@link OpenRouterErrorCategory#UNKNOWN}.
	 * @return the category
	 */
	@Override
	public OpenRouterErrorCategory getCategory() {
		return this.kind == Kind.TIMEOUT ? OpenRouterErrorCategory.TIMEOUT : OpenRouterErrorCategory.UNKNOWN;
	}

	/**
	 * A transport failure is temporary.
	 * @return {@code true}
	 */
	@Override
	public boolean isTransient() {
		return true;
	}

}
