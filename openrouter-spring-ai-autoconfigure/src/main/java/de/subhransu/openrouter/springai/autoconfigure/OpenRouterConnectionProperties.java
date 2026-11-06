package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.util.Assert;

@ConfigurationProperties(OpenRouterConnectionProperties.CONFIG_PREFIX)
public class OpenRouterConnectionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.connection";

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

	/**
	 * Read timeout for blocking calls and maximum idle gap between streamed events.
	 * Defaults to 2m. When set, it is also the blocking connect timeout unless
	 * connect-timeout is set.
	 */
	// No field default: leaving it unset lets spring.http.clients.connect-timeout reach
	// blocking connects, while an explicit value keeps precedence. The 2m default is
	// applied by getTimeout() and documented in the additional metadata.
	private @Nullable Duration timeout;

	/**
	 * Connect timeout for blocking calls. When unset, an explicit timeout applies, then
	 * spring.http.clients.connect-timeout, then the 2m default. Streams always connect
	 * with spring.http.clients.connect-timeout.
	 */
	private @Nullable Duration connectTimeout;

	/**
	 * Maximum size of one blocking response body or one streamed event. Does not limit
	 * the total size of a stream.
	 */
	private DataSize maxResponseBodySize = DataSize.ofMegabytes(64);

	/**
	 * Maximum size of an HTTP error body that is read and kept for diagnostics.
	 */
	private DataSize maxErrorBodySize = DataSize.ofKilobytes(64);

	public Duration getTimeout() {
		return (this.timeout != null) ? this.timeout : DEFAULT_TIMEOUT;
	}

	@Nullable Duration configuredTimeout() {
		return this.timeout;
	}

	public void setTimeout(@Nullable Duration timeout) {
		this.timeout = timeout;
	}

	public @Nullable Duration getConnectTimeout() {
		return this.connectTimeout;
	}

	public void setConnectTimeout(@Nullable Duration connectTimeout) {
		this.connectTimeout = connectTimeout;
	}

	public DataSize getMaxResponseBodySize() {
		return this.maxResponseBodySize;
	}

	public void setMaxResponseBodySize(DataSize maxResponseBodySize) {
		validateBodySize(maxResponseBodySize, "max-response-body-size");
		this.maxResponseBodySize = maxResponseBodySize;
	}

	public DataSize getMaxErrorBodySize() {
		return this.maxErrorBodySize;
	}

	public void setMaxErrorBodySize(DataSize maxErrorBodySize) {
		validateBodySize(maxErrorBodySize, "max-error-body-size");
		this.maxErrorBodySize = maxErrorBodySize;
	}

	private static void validateBodySize(DataSize size, String property) {
		Assert.isTrue(size != null && size.toBytes() > 0 && size.toBytes() < Integer.MAX_VALUE,
				CONFIG_PREFIX + "." + property + " must be between 1 and 2147483646 bytes");
	}

}
