package de.subhransu.openrouter.springai.chat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.function.Function;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.util.Assert;

/**
 * Converts local tool callback failures into a provider-safe result while retaining the
 * original exception in local diagnostics.
 *
 * <p>
 * Tool results cross an application/provider trust boundary. The default payload is
 * deliberately stable and contains no exception message, cause, or stack trace. The
 * original {@link ToolExecutionException} is attached to the active Micrometer
 * observation and written to debug logs.
 *
 * <p>
 * Applications that intentionally expose failure details can supply a custom payload
 * renderer. Doing so makes the renderer responsible for preventing sensitive data from
 * reaching the provider.
 *
 * @author Subhransu De
 */
public final class OpenRouterToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

	/** Provider-visible payload used for failed tool callbacks by default. */
	public static final String DEFAULT_FAILURE_PAYLOAD = "{\"error\":\"Tool execution failed\"}";

	private static final Log logger = LogFactory.getLog(OpenRouterToolExecutionExceptionProcessor.class);

	private final ObservationRegistry observationRegistry;

	private final Function<ToolExecutionException, String> failurePayloadRenderer;

	/**
	 * Create a processor with the safe default payload and no observation registry.
	 */
	public OpenRouterToolExecutionExceptionProcessor() {
		this(ObservationRegistry.NOOP);
	}

	/**
	 * Create a processor with the safe default payload.
	 * @param observationRegistry registry used to record the original failure
	 */
	public OpenRouterToolExecutionExceptionProcessor(ObservationRegistry observationRegistry) {
		this(observationRegistry, exception -> DEFAULT_FAILURE_PAYLOAD);
	}

	/**
	 * Create a processor with an application-defined provider-visible payload.
	 * @param observationRegistry registry used to record the original failure
	 * @param failurePayloadRenderer renderer for the provider-visible result; rendering
	 * exception details is an explicit opt-in that may disclose sensitive information
	 */
	public OpenRouterToolExecutionExceptionProcessor(ObservationRegistry observationRegistry,
			Function<ToolExecutionException, String> failurePayloadRenderer) {
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(failurePayloadRenderer, "failurePayloadRenderer must not be null");
		this.observationRegistry = observationRegistry;
		this.failurePayloadRenderer = failurePayloadRenderer;
	}

	@Override
	public String process(ToolExecutionException exception) {
		Assert.notNull(exception, "exception must not be null");
		Observation observation = this.observationRegistry.getCurrentObservation();
		if (observation != null) {
			observation.error(exception);
		}
		String toolName = exception.getToolDefinition().name().replace('\r', '_').replace('\n', '_');
		if (logger.isWarnEnabled()) {
			logger.warn("Tool callback '" + toolName + "' failed; returning a sanitized result to the provider");
		}
		if (logger.isDebugEnabled()) {
			logger.debug("Local details for failed tool callback '" + toolName + "'", exception);
		}
		String payload = this.failurePayloadRenderer.apply(exception);
		Assert.notNull(payload, "failurePayloadRenderer must not return null");
		return payload;
	}

}
