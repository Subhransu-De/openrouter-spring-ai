package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.errors.OpenRouterTransportException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

class TransportFailuresTests {

	@Tag("http")
	@Tag("http.errors")
	@Test
	void connectorTimeoutsWithoutAJdkTimeoutTypeAreTransportTimeouts() {
		// Netty's ReadTimeoutException, which a customized response timeout raises, is
		// not a java.util.concurrent.TimeoutException.
		RuntimeException raw = new ReadTimeoutException();
		WebClientResponseException wrapped = new WebClientResponseException(200, "OK", HttpHeaders.EMPTY, new byte[0],
				StandardCharsets.UTF_8);
		wrapped.initCause(new ReadTimeoutException());

		for (RuntimeException failure : new RuntimeException[] { raw, wrapped }) {
			assertThat(TransportFailures.stream("/images", 1024, failure))
				.isInstanceOfSatisfying(OpenRouterTransportException.class, transport -> {
					assertThat(transport.getKind()).isEqualTo(OpenRouterTransportException.Kind.TIMEOUT);
					assertThat(transport.getCause()).isSameAs(failure);
				});
		}
	}

	@Tag("http")
	@Tag("http.errors")
	@Test
	void unrelatedResponseFailuresStayUnchanged() {
		WebClientResponseException failure = WebClientResponseException.create(HttpStatus.BAD_GATEWAY.value(),
				"Bad Gateway", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);

		assertThat(TransportFailures.stream("/chat/completions", 1024, failure)).isSameAs(failure);
	}

	private static final class ReadTimeoutException extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

}
