package de.subhransu.openrouter.springai.api;

import static de.subhransu.openrouter.springai.api.ResponseContractStubs.blocking;
import static de.subhransu.openrouter.springai.api.ResponseContractStubs.chatModel;
import static de.subhransu.openrouter.springai.api.ResponseContractStubs.streaming;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract of the Chat Completions response path: a canned blocking or SSE reply goes
 * through the transport and the model, and the test observes the mapped result or the
 * failure the caller sees. The Responses twin is {@code ResponsesResponseContractTests}.
 */
class ChatResponseContractTests {

	private static final Prompt PROMPT = new Prompt("synthetic prompt");

	private final ChatCompletionRequest request = new ObjectMapper().readValue("{}", ChatCompletionRequest.class);

	private OpenRouterChatModel model(String body, TestObservationRegistry registry) {
		return chatModel(blocking("/chat/completions", body, HttpStatus.OK),
				OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, registry);
	}

	private OpenRouterChatModel streamingModel(String body) {
		return chatModel(streaming(body), OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, null);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", ",\"message\":null" })
	void completedChoicesRequireMessageObjects(String message) {
		String body = "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"" + message + "}]}";
		TestObservationRegistry registry = TestObservationRegistry.create();
		OpenRouterChatModel model = model(body, registry);
		assertThatThrownBy(() -> model.call(PROMPT)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining("requires a message");
		TestObservationRegistryAssert.assertThat(registry)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	// Unlike a completed blocking choice, a streamed choice may finish without any
	// message data: the closing chunk carries only the finish reason.
	@ParameterizedTest
	@ValueSource(strings = { "", ",\"delta\":null" })
	void streamedChoiceWithoutADeltaIsAValidFinishOnlyChunk(String delta) {
		String body = "{\"choices\":[{\"index\":0,\"finish_reason\":\"stop\"" + delta + "}]}";

		StepVerifier.create(streamingModel(body).stream(PROMPT)).assertNext(response -> {
			assertThat(response.getResult().getOutput().getText()).isEmpty();
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		}).verifyComplete();
	}

	static Stream<Arguments> messagesWithoutText() {
		return Stream.of(
				Arguments.of(Named.of("empty content",
						new MessageShape("\"content\":\"\"", "stop", "STOP",
								generation -> assertThat(generation.getOutput().getText()).isEmpty()))),
				Arguments.of(Named.of("refusal",
						new MessageShape("\"refusal\":\"synthetic refusal\"", "stop", "STOP",
								generation -> assertThat(generation.getOutput().getMetadata())
									.containsEntry("openrouter.refusal", "synthetic refusal")))),
				Arguments.of(Named.of("tool call", new MessageShape(
						"\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"test\",\"arguments\":\"{}\"}}]",
						"tool_calls", "TOOL_CALLS",
						generation -> assertThat(generation.getOutput().getToolCalls())
							.extracting(call -> call.id(), call -> call.type(), call -> call.name(),
									call -> call.arguments())
							.containsExactly(
									org.assertj.core.groups.Tuple.tuple("call_1", "function", "test", "{}"))))),
				Arguments.of(Named.of("generated image", new MessageShape(
						"\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,aW1hZ2U=\"}}]",
						"stop", "STOP",
						generation -> assertThat(generation.getOutput().getMedia()).singleElement().satisfies(media -> {
							assertThat(media.getMimeType().toString()).isEqualTo("image/png");
							assertThat(media.getData()).isEqualTo("data:image/png;base64,aW1hZ2U=");
						})))));
	}

	@ParameterizedTest
	@MethodSource("messagesWithoutText")
	void emptyTextMessagesRemainValid(MessageShape shape) {
		String body = "{\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"" + shape.finishReason()
				+ "\",\"message\":{\"role\":\"assistant\"," + shape.field() + "}}]}";
		var response = model(body, null).call(PROMPT);
		assertThat(response.getResults()).singleElement().satisfies(generation -> {
			assertThat(generation.getMetadata().getFinishReason()).isEqualTo(shape.mappedFinishReason());
			shape.check().accept(generation);
		});
	}

	static Stream<Arguments> transientErrors() {
		return Stream.of(new TransientCode("503", 503), new TransientCode("\"503\"", 503),
				new TransientCode("\"provider_unavailable\"", 500), new TransientCode("\"rate_limit_exceeded\"", 429))
			.flatMap(code -> Stream.of(true, false)
				.map(withChoices -> Arguments.of(
						Named.of("code " + code.json() + (withChoices ? " with choices" : " without choices"), code),
						withChoices)));
	}

	private static String errorBody(String code, boolean withChoices) {
		String choices = withChoices
				? ",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ignored\"}}]" : "";
		return "{\"error\":{\"code\":" + code + ",\"message\":\"synthetic failure\"}" + choices + "}";
	}

	private void assertStreamFailsWithSyntheticFailure(String body) {
		StepVerifier.create(streamingModel(body).stream(PROMPT))
			.expectErrorSatisfies(failure -> assertThat(failure).isInstanceOfSatisfying(OpenRouterApiException.class,
					error -> assertThat(error.getErrorDetails().message()).isEqualTo("synthetic failure")))
			.verify();
	}

	@ParameterizedTest
	@MethodSource("transientErrors")
	void transientTopLevelErrorsTakePrecedenceOverChoices(TransientCode code, boolean withChoices) {
		String body = errorBody(code.json(), withChoices);
		assertThatThrownBy(() -> model(body, null).call(PROMPT))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, failure -> {
				assertThat(failure.getErrorDetails().message()).isEqualTo("synthetic failure");
				assertThat(failure.getStatusCode().value()).isEqualTo(code.status());
			});
		assertStreamFailsWithSyntheticFailure(body);
	}

	@ParameterizedTest(name = "with choices: {0}")
	@ValueSource(booleans = { true, false })
	void topLevelErrorWithoutCodeIsNotTransient(boolean withChoices) {
		String body = errorBody("null", withChoices);
		assertThatThrownBy(() -> model(body, null).call(PROMPT)).isInstanceOfSatisfying(OpenRouterApiException.class,
				failure -> {
					assertThat(failure).isNotInstanceOf(OpenRouterTransientApiException.class);
					assertThat(failure.getErrorDetails().message()).isEqualTo("synthetic failure");
				});
		assertStreamFailsWithSyntheticFailure(body);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"choices\":null}", "{\"choices\":[]}", "{\"choices\":[null]}" })
	void rejectsMissingOrInvalidChoices(String body) {
		assertThatThrownBy(() -> model(body, null).call(PROMPT)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("OpenRouter chat completion requires non-null choices");
	}

	static Stream<Arguments> emptyAndNullBodies() {
		return Stream.of(HttpStatus.OK, HttpStatus.NO_CONTENT)
			.flatMap(status -> Stream.of(
					Arguments.of(Named.of(status.value() + " with an empty body", status), "",
							"Empty OpenRouter /chat/completions response"),
					Arguments.of(Named.of(status.value() + " with a null body", status), "null",
							"Null OpenRouter /chat/completions response")));
	}

	@ParameterizedTest
	@MethodSource("emptyAndNullBodies")
	void rejectsEmptyAndNullBlockingBodies(HttpStatus status, String body, String message) {
		assertThatThrownBy(() -> blocking("/chat/completions", body, status).chatCompletion(this.request))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage(message);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "\"choices\":[],", "\"choices\":null," })
	void usageOnlyIsValidOnlyInStreams(String choices) {
		String body = "{" + choices + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
		assertThatThrownBy(() -> model(body, null).call(PROMPT)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("OpenRouter chat completion requires non-null choices");
		StepVerifier.create(streamingModel(body).stream(PROMPT)).assertNext(response -> {
			assertThat(response.getResults()).isEmpty();
			assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(3);
		}).verifyComplete();
	}

	record MessageShape(String field, String finishReason, String mappedFinishReason, Consumer<Generation> check) {
	}

	record TransientCode(String json, int status) {
	}

}
