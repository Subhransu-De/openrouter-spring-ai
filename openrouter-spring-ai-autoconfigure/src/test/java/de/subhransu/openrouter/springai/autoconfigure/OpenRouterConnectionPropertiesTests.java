package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import reactor.test.StepVerifier;

/**
 * The {@code spring.ai.openrouter.connection.*} properties must reach the API that
 * auto-configuration builds. Each test binds a property, then shows its effect on a
 * request that goes through the real API over a recording transport.
 *
 * <p>
 * {@code max-response-body-size} bounds blocking success bodies only: streamed responses
 * have no body limit, and their error bodies, like the blocking ones, fall under
 * {@code max-error-body-size}. The tests below cover each limit on the call that it
 * bounds.
 */
class OpenRouterConnectionPropertiesTests {

	private static final String PREFIX = "spring.ai.openrouter.connection.";

	private static final int LIMIT_BYTES = 1024;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@Test
	void aBlockingSuccessBodyThroughTheBoundLimitIsAcceptedAndOneByteMoreIsRejected() {
		String atLimit = jsonPaddedTo(LIMIT_BYTES);
		String overLimit = jsonPaddedTo(LIMIT_BYTES + 1);
		RecordingTransport accepting = new RecordingTransport().blockingResponse(HttpStatus.OK, atLimit);
		accepting.installOn(this.contextRunner)
			.withPropertyValues(PREFIX + "max-response-body-size=1KB")
			.run(context -> assertThatCode(() -> context.getBean(OpenRouterApi.class).chatCompletion(request()))
				.doesNotThrowAnyException());
		RecordingTransport rejecting = new RecordingTransport().blockingResponse(HttpStatus.OK, overLimit);
		rejecting.installOn(this.contextRunner)
			.withPropertyValues(PREFIX + "max-response-body-size=1KB")
			.run(context -> assertThatThrownBy(() -> context.getBean(OpenRouterApi.class).chatCompletion(request()))
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit())
						.isEqualTo(OpenRouterLimitExceededException.Limit.BLOCKING_RESPONSE_BODY_BYTES);
					assertThat(failure.getConfiguredLimit()).isEqualTo(LIMIT_BYTES);
					assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.OK);
				}));
	}

	@Test
	void aBlockingErrorBodyOverTheBoundLimitIsRejectedAsALimitFailureNotAnApiError() {
		new RecordingTransport().blockingResponse(HttpStatus.BAD_GATEWAY, "x".repeat(LIMIT_BYTES + 1))
			.installOn(this.contextRunner)
			.withPropertyValues(PREFIX + "max-error-body-size=1KB")
			.run(context -> assertThatThrownBy(() -> context.getBean(OpenRouterApi.class).chatCompletion(request()))
				.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
					assertThat(failure.getLimit())
						.isEqualTo(OpenRouterLimitExceededException.Limit.BLOCKING_ERROR_BODY_BYTES);
					assertThat(failure.getConfiguredLimit()).isEqualTo(LIMIT_BYTES);
					assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
				}));
	}

	@Test
	void aStreamingErrorBodyOverTheBoundLimitIsRejectedAsALimitFailure() {
		new RecordingTransport()
			.streamingResponse(HttpStatus.BAD_GATEWAY, MediaType.APPLICATION_JSON, "x".repeat(LIMIT_BYTES + 1))
			.installOn(this.contextRunner)
			.withPropertyValues(PREFIX + "max-error-body-size=1KB")
			.run(context -> StepVerifier.create(context.getBean(OpenRouterApi.class).chatCompletionStream(request()))
				.expectErrorSatisfies(error -> assertThat(error)
					.isInstanceOfSatisfying(OpenRouterLimitExceededException.class, failure -> {
						assertThat(failure.getLimit())
							.isEqualTo(OpenRouterLimitExceededException.Limit.STREAMING_ERROR_BODY_BYTES);
						assertThat(failure.getConfiguredLimit()).isEqualTo(LIMIT_BYTES);
					}))
				.verify());
	}

	@Test
	void theBoundTimeoutEndsAStreamThatStopsEmitting() {
		// Virtual time: the timeout is the property under test, not a real wait.
		new RecordingTransport().stallingStream()
			.installOn(this.contextRunner)
			.withPropertyValues(PREFIX + "timeout=5s")
			.run(context -> StepVerifier
				.withVirtualTime(() -> context.getBean(OpenRouterApi.class).chatCompletionStream(request()))
				.thenAwait(Duration.ofSeconds(4))
				.expectNoEvent(Duration.ofMillis(500))
				.thenAwait(Duration.ofSeconds(1))
				.expectErrorSatisfies(
						error -> assertThat(error).isInstanceOf(TimeoutException.class).hasMessageContaining("5000ms"))
				.verify());
	}

	private static String jsonPaddedTo(int bytes) {
		return RecordingTransport.MINIMAL_CHAT_COMPLETION
				+ " ".repeat(bytes - RecordingTransport.MINIMAL_CHAT_COMPLETION.length());
	}

	private static ChatCompletionRequest request() {
		return ChatCompletionRequest.builder()
			.model("m")
			.messages(List.of(new ChatMessage("user", "hi", null, null, null)))
			.build();
	}

}
