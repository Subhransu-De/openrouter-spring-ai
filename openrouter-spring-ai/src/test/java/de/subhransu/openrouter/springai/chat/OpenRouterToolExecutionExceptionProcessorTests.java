package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

class OpenRouterToolExecutionExceptionProcessorTests {

	private static final String SECRET = "api-key=sk-sensitive";

	private static final String NESTED_DETAIL = "nested database password";

	private final ToolDefinition toolDefinition = ToolDefinition.builder()
		.name("load_account")
		.description("Load an account")
		.inputSchema("{\"type\":\"object\"}")
		.build();

	@Test
	void defaultPayloadNeverContainsExceptionDetails() {
		ToolExecutionException exception = nestedFailure();

		String payload = new OpenRouterToolExecutionExceptionProcessor().process(exception);

		assertThat(payload).isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD)
			.doesNotContain(SECRET, "local path", NESTED_DETAIL, "\n", "IllegalStateException");
	}

	@Test
	void logMessagesKeepToolNamesOnOneLineAndRetainTheOriginalDebugException() {
		Logger logger = (Logger) LoggerFactory.getLogger(OpenRouterToolExecutionExceptionProcessor.class);
		Level previousLevel = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);
		ToolDefinition definition = ToolDefinition.builder()
			.name("lookup\r\nforged\nentry\rend")
			.description("Synthetic tool")
			.inputSchema("{\"type\":\"object\"}")
			.build();
		ToolExecutionException exception = new ToolExecutionException(definition,
				new IllegalStateException("synthetic failure\r\nforged detail"));
		try {
			assertThat(new OpenRouterToolExecutionExceptionProcessor().process(exception))
				.isEqualTo(OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD);
			assertThat(appender.list).hasSize(2)
				.allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("lookup__forged_entry_end")
					.doesNotContain("\r", "\n"));
			assertThat(((ThrowableProxy) appender.list.get(1).getThrowableProxy()).getThrowable()).isSameAs(exception);
		}
		finally {
			logger.setLevel(previousLevel);
			logger.detachAppender(appender);
			appender.stop();
		}
	}

	@Test
	void recordsTheOriginalNestedExceptionOnTheActiveObservation() {
		TestObservationRegistry registry = TestObservationRegistry.create();
		ToolExecutionException exception = nestedFailure();
		Observation observation = Observation.start("openrouter.tool.callback", registry);
		try (Observation.Scope ignored = observation.openScope()) {
			new OpenRouterToolExecutionExceptionProcessor(registry).process(exception);
		}
		finally {
			observation.stop();
		}

		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
			.hasHandledContextsThatSatisfy(contexts -> assertThat(contexts).singleElement().satisfies(context -> {
				assertThat(context.getError()).isSameAs(exception);
				assertThat(context.getError().getCause().getCause().getMessage()).isEqualTo(NESTED_DETAIL);
			}));
	}

	@Test
	void customRendererIsAnExplicitOptInForDetailedProviderOutput() {
		ToolExecutionException exception = nestedFailure();
		OpenRouterToolExecutionExceptionProcessor processor = new OpenRouterToolExecutionExceptionProcessor(
				ObservationRegistry.NOOP,
				failure -> failure.getCause().getMessage() + ": " + failure.getCause().getCause().getMessage());

		assertThat(processor.process(exception)).contains(SECRET, NESTED_DETAIL);
	}

	private ToolExecutionException nestedFailure() {
		IllegalArgumentException nested = new IllegalArgumentException(NESTED_DETAIL);
		IllegalStateException cause = new IllegalStateException("local path C:\\private\\data\n" + SECRET, nested);
		return new ToolExecutionException(this.toolDefinition, cause);
	}

}
