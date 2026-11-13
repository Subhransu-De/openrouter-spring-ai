package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.errors.OpenRouterFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransportException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Maps transport failures from the HTTP clients to the library's exception types. The
 * original exception is always kept as the cause.
 *
 * @author Subhransu De
 */
final class TransportFailures {

	// Bounds the walk down a cause chain that a broken exception could make cyclic.
	private static final int MAX_CAUSE_DEPTH = 32;

	private TransportFailures() {
	}

	/**
	 * Run a blocking exchange, mapping the {@link ResourceAccessException} that
	 * {@code RestClient} raises for an I/O failure.
	 */
	static <T> T blocking(String endpoint, Supplier<T> exchange) {
		try {
			return exchange.get();
		}
		catch (ResourceAccessException ex) {
			throw new OpenRouterTransportException(kind(ex), endpoint, ex);
		}
	}

	/**
	 * Map a failure of a streaming exchange. Library failures and failures that are not
	 * transport failures are returned unchanged.
	 */
	static Throwable stream(String endpoint, int maxEventBytes, Throwable failure) {
		if (failure instanceof OpenRouterFailure) {
			return failure;
		}
		if (find(failure, DataBufferLimitException.class) != null) {
			OpenRouterLimitExceededException limit = new OpenRouterLimitExceededException(
					OpenRouterLimitExceededException.Limit.STREAMING_EVENT_BYTES, maxEventBytes, maxEventBytes + 1L,
					endpoint, null, null, null);
			limit.initCause(failure);
			return limit;
		}
		// retrieve() wraps a failure while reading the body, such as a connection closed
		// mid-stream, in WebClientResponseException; exchangeToFlux() does not wrap it.
		boolean transport = failure instanceof TimeoutException || failure instanceof WebClientRequestException
				|| failure instanceof IOException
				|| failure instanceof WebClientResponseException && find(failure, IOException.class) != null;
		return transport ? new OpenRouterTransportException(kind(failure), endpoint, failure) : failure;
	}

	static OpenRouterTransportException.Kind kind(Throwable failure) {
		if (find(failure, SocketTimeoutException.class) != null || find(failure, HttpTimeoutException.class) != null
				|| find(failure, TimeoutException.class) != null || namedTimeout(failure)) {
			return OpenRouterTransportException.Kind.TIMEOUT;
		}
		if (find(failure, ConnectException.class) != null || find(failure, UnknownHostException.class) != null
				|| find(failure, NoRouteToHostException.class) != null
				|| find(failure, UnresolvedAddressException.class) != null) {
			return OpenRouterTransportException.Kind.CONNECT;
		}
		return OpenRouterTransportException.Kind.IO;
	}

	// Connector timeouts, such as Netty's ReadTimeoutException, share no JDK supertype.
	private static boolean namedTimeout(Throwable failure) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (current.getClass().getSimpleName().endsWith("TimeoutException")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static <T extends Throwable> @Nullable T find(Throwable failure, Class<T> type) {
		Throwable current = failure;
		for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
			if (type.isInstance(current)) {
				return type.cast(current);
			}
			current = current.getCause();
		}
		return null;
	}

}
