package de.subhransu.openrouter.springai.chat;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
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
 * <p>
 * As with Spring AI's {@code DefaultToolExecutionExceptionProcessor}, a failure whose
 * cause is not a {@link RuntimeException}, such as an {@code IOException} or an
 * {@link Error}, propagates instead of becoming a result, and so does a cause of one of
 * the configured rethrown types.
 *
 * @author Subhransu De
 */
public final class OpenRouterToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

	/** Provider-visible payload used for failed tool callbacks by default. */
	public static final String DEFAULT_FAILURE_PAYLOAD = "{\"error\":\"Tool execution failed\"}";

	private static final Log logger = LogFactory.getLog(OpenRouterToolExecutionExceptionProcessor.class);

	private final ObservationRegistry observationRegistry;

	private final Function<ToolExecutionException, String> failurePayloadRenderer;

	private final List<Class<? extends RuntimeException>> rethrownExceptions;

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
		this(observationRegistry, failurePayloadRenderer, List.of());
	}

	/**
	 * Create a processor with an application-defined provider-visible payload and
	 * exception types that propagate instead of becoming a result.
	 * @param observationRegistry registry used to record the original failure
	 * @param failurePayloadRenderer renderer for the provider-visible result; rendering
	 * exception details is an explicit opt-in that may disclose sensitive information
	 * @param rethrownExceptions runtime exception types that are rethrown when they are
	 * the cause of a failure
	 */
	public OpenRouterToolExecutionExceptionProcessor(ObservationRegistry observationRegistry,
			Function<ToolExecutionException, String> failurePayloadRenderer,
			List<Class<? extends RuntimeException>> rethrownExceptions) {
		Assert.notNull(observationRegistry, "observationRegistry must not be null");
		Assert.notNull(failurePayloadRenderer, "failurePayloadRenderer must not be null");
		Assert.noNullElements(rethrownExceptions, "rethrownExceptions must not contain null elements");
		this.observationRegistry = observationRegistry;
		this.failurePayloadRenderer = failurePayloadRenderer;
		this.rethrownExceptions = List.copyOf(rethrownExceptions);
	}

	@Override
	public String process(ToolExecutionException exception) {
		Assert.notNull(exception, "exception must not be null");
		// The same rule as Spring AI's default processor: checked exceptions and errors
		// are not tool results, and rethrown types such as OAuth2 re-authorization must
		// reach the caller.
		if (!(exception.getCause() instanceof RuntimeException cause)) {
			throw exception;
		}
		if (this.rethrownExceptions.stream().anyMatch((type) -> type.isInstance(cause))) {
			throw cause;
		}
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
