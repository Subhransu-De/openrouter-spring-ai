package de.subhransu.openrouter.springai.api;

import static de.subhransu.openrouter.springai.api.ResponseContractStubs.blocking;
import static de.subhransu.openrouter.springai.api.ResponseContractStubs.chatModel;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract of the blocking {@code /responses} path, the Responses twin of
 * {@link ChatResponseContractTests}: empty, null, no-content and malformed bodies reach
 * the caller as protocol failures instead of a null or half-built result.
 */
class ResponsesResponseContractTests {

	private static final Prompt PROMPT = new Prompt("synthetic prompt");

	private final ResponsesRequest request = new ObjectMapper().readValue("{}", ResponsesRequest.class);

	static Stream<Arguments> emptyAndNullBodies() {
		return Stream.of(HttpStatus.OK, HttpStatus.NO_CONTENT)
			.flatMap(status -> Stream.of(
					Arguments.of(Named.of(status.value() + " with an empty body", status), "",
							"Empty OpenRouter /responses response"),
					Arguments.of(Named.of(status.value() + " with a null body", status), "null",
							"Null OpenRouter /responses response")));
	}

	@ParameterizedTest
	@MethodSource("emptyAndNullBodies")
	void apiRejectsEmptyAndNullBodies(HttpStatus status, String body, String message) {
		assertThatThrownBy(() -> blocking("/responses", body, status).responses(this.request))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(message);
	}

	@ParameterizedTest(name = "[{index}] body {0}")
	@ValueSource(strings = { "{not json", "[]", "\"text\"", "{\"output\":\"not-a-list\"}", "{\"status\":" })
	void apiRejectsMalformedBodiesKeepingTheJacksonCause(String body) {
		assertThatThrownBy(() -> blocking("/responses", body, HttpStatus.OK).responses(this.request))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("Failed to decode OpenRouter /responses response")
			.hasCauseInstanceOf(JacksonException.class);
	}

	@ParameterizedTest(name = "[{index}] body \"{0}\"")
	@ValueSource(strings = { "", "null" })
	void modelCallFailsWithAProtocolErrorAndStopsTheObservation(String body) {
		TestObservationRegistry registry = TestObservationRegistry.create();
		var model = chatModel(blocking("/responses", body, HttpStatus.OK), OpenRouterRequestMode.OPENAI_RESPONSES,
				registry);
		assertThatThrownBy(() -> model.call(PROMPT)).isInstanceOf(OpenRouterProtocolException.class);
		TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterProtocolException.class);
	}

}
