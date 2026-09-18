package de.subhransu.openrouter.springai.autoconfigure;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.util.Assert;

@ConfigurationProperties(OpenRouterConnectionProperties.CONFIG_PREFIX)
public class OpenRouterConnectionProperties {

	public static final String CONFIG_PREFIX = "spring.ai.openrouter.connection";

	private @Nullable Duration timeout = Duration.ofMinutes(2);

	private DataSize maxResponseBodySize = DataSize.ofMegabytes(64);

	private DataSize maxErrorBodySize = DataSize.ofKilobytes(64);

	public @Nullable Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(@Nullable Duration timeout) {
		this.timeout = timeout;
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
