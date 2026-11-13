package de.subhransu.openrouter.springai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.apache.commons.logging.Log;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OptionChecksTests {

	@Tag("api")
	@Tag("api.options")
	@Test
	void unknownValueWarningsCannotForgeLogLines() {
		Log log = mock(Log.class);
		when(log.isWarnEnabled()).thenReturn(true);

		OptionChecks.warnIfUnknown(log, "reasoning.effort", "high\r\nINFO forged", List.of("low", "high"));

		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(log).warn(message.capture());
		assertThat(message.getValue()).doesNotContain("\r", "\n").contains("high__INFO forged");
	}

	@Tag("api")
	@Tag("api.options")
	@Test
	void documentedValuesAreNotWarned() {
		Log log = mock(Log.class);
		when(log.isWarnEnabled()).thenReturn(true);

		OptionChecks.warnIfUnknown(log, "reasoning.effort", "high", List.of("low", "high"));

		verify(log, never()).warn(anyString());
	}

}
