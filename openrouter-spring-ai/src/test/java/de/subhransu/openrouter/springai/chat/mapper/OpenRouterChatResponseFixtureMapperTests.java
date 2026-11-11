package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceFailure;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.MimeTypeUtils;

/**
 * Mapper coverage driven by realistic provider-shaped JSON fixtures. Fixtures are parsed
 * through the configured {@link ObjectMapper} before mapping, so this exercises Jackson
 * naming, {@code @JsonAlias} usage detail handling, unknown-field tolerance, and real
 * array/object content shapes that DTO-instantiation tests cannot reach. Responses API
 * fixtures belong in the Responses-mode test class.
 */
class OpenRouterChatResponseFixtureMapperTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenRouterChatResponseMapper responseMapper = new OpenRouterChatResponseMapper(
			OpenRouterDiagnosticSanitizer.keyless());

	@ParameterizedTest(name = "blocking native failure {0} -> {1}")
	@CsvSource({ "error, UNKNOWN", "insufficient_system_resources, PROVIDER_UNAVAILABLE",
			"insufficient_quota, BILLING_CREDITS" })
	void blockingNativeFailureReasonsRaiseTypedFailures(String nativeReason, OpenRouterErrorCategory category) {
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial", null, null, null), null, nativeReason,
				nativeReason);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-native", "chat.completion", 1L, "m", "p",
				List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response)).isInstanceOf(OpenRouterChoiceFailure.class)
			.satisfies(thrown -> {
				OpenRouterChoiceFailure failure = (OpenRouterChoiceFailure) thrown;
				assertThat(failure.getCategory()).isEqualTo(category);
				assertThat(failure.getErrorDetails().nativeFinishReason()).isEqualTo(nativeReason);
			});
	}

	@Test
	void sparseChoiceErrorFallsBackToNativeFailureCategory() {
		ChoiceError error = new ChoiceError(null, null, Map.of());
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial", null, null, null), null, "error",
				"insufficient_system_resources", error);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-native", "chat.completion", 1L, "m", "p",
				List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response)).isInstanceOfSatisfying(
				OpenRouterTransientChoiceException.class, exception -> assertThat(exception.getCategory())
					.isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE));
	}

	@Test
	void unknownCanonicalChoiceTypeRemainsFailClosedWithNativeFailureReason() {
		ChoiceError error = new ChoiceError(null, null, Map.of("error_type", "future_error"));
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial", null, null, null), null, "error",
				"insufficient_system_resources", error);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-native", "chat.completion", 1L, "m", "p",
				List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterNonTransientChoiceException.class, exception -> {
				assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("future_error");
			});
	}

	private <T> T parseFixture(String name, Class<T> type) throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
			assertThat(in).as("fixture %s must exist on the test classpath", name).isNotNull();
			return this.objectMapper.readValue(in, type);
		}
	}

	@Test
	void mapsRealisticSuccessFixtureIncludingNestedUsageAndUnknownFields() throws IOException {
		ChatCompletionResponse response = parseFixture("chat-completion-success.json", ChatCompletionResponse.class);

		ChatResponse mapped = this.responseMapper.map(response);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("The capital of France is Paris.");
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(mapped.getMetadata().getId()).isEqualTo("gen-abc123");
		assertThat(mapped.getMetadata().getModel()).isEqualTo("openai/gpt-5.4-mini");
		OpenRouterUsage usage = (OpenRouterUsage) mapped.getMetadata().getUsage();
		// Billing-relevant fields individually: swapped prompt/completion counts must
		// fail, not just a matching total.
		assertThat(usage.getPromptTokens()).isEqualTo(12);
		assertThat(usage.getCompletionTokens()).isEqualTo(8);
		assertThat(usage.getTotalTokens()).isEqualTo(20);
		// Nested detail objects feed cached/reasoning token counts.
		assertThat(usage.getCachedTokens()).isEqualTo(4);
		assertThat(usage.getReasoningTokens()).isEqualTo(3);
		assertThat(usage.getCost()).isEqualTo(0.00031);
	}

	@Test
	void mapsMultipleChoicesToMultipleGenerations() throws IOException {
		ChatCompletionResponse response = parseFixture("chat-completion-multi-choice.json",
				ChatCompletionResponse.class);

		ChatResponse mapped = this.responseMapper.map(response);

		assertThat(mapped.getResults()).hasSize(2);
		assertThat(mapped.getResults().get(0).getOutput().getText()).isEqualTo("First candidate.");
		assertThat(mapped.getResults().get(0).getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(mapped.getResults().get(1).getOutput().getText()).isEqualTo("第二候补 — second candidate.");
		assertThat(mapped.getResults().get(1).getMetadata().getFinishReason()).isEqualTo("LENGTH");
	}

	@Test
	void choiceErrorFixtureDeserializesAndRaisesTransientFailureWithDiagnostics() throws IOException {
		ChatCompletionResponse response = parseFixture("chat-completion-choice-error.json",
				ChatCompletionResponse.class);

		assertThat(response.choices()).singleElement().satisfies(choice -> {
			assertThat(choice.error()).isNotNull();
			assertThat(choice.error().code()).isEqualTo("502");
			assertThat(choice.error().message()).isEqualTo("Provider disconnected mid-stream");
			assertThat(choice.error().metadata()).containsEntry("error_type", "provider_unavailable")
				.containsEntry("provider_code", "connection_reset")
				.containsEntry("retryable", true);
		});

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
				assertThat(exception.getErrorDetails().responseId()).isEqualTo("gen-choice-error-502");
				assertThat(exception.getErrorDetails().choiceIndex()).isZero();
				assertThat(exception.getErrorDetails().finishReason()).isEqualTo("error");
				assertThat(exception.getErrorDetails().code()).isEqualTo("502");
				assertThat(exception.getErrorDetails().message()).isEqualTo("Provider disconnected mid-stream");
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("provider_unavailable");
				assertThat(exception.getErrorDetails().providerCode()).isEqualTo("connection_reset");
				assertThat(exception.getErrorDetails().provider()).isEqualTo("Anthropic");
				assertThat(exception.getErrorDetails().metadata()).containsEntry("upstream_request_id",
						"req-provider-123");
				assertThat(exception.getErrorDetails().partialOutput())
					.isEqualTo("The provider began an answer but disconnected before completing it.");
				assertThat(exception.getErrorDetails().partialOutputTruncated()).isFalse();
			});
	}

	@Test
	void anyFailedChoiceMakesTheWholeMultiChoiceResponseFail() throws IOException {
		ChatCompletionResponse response = parseFixture("chat-completion-mixed-choice-error.json",
				ChatCompletionResponse.class);

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterNonTransientChoiceException.class, exception -> {
				assertThat(exception.getErrorDetails().choiceIndex()).isEqualTo(1);
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("invalid_request");
				assertThat(exception.getErrorDetails().partialOutput()).isEqualTo("A partial second candidate.");
			});
	}

	static Stream<Arguments> symbolicChoiceCodes() {
		return Stream.of(
				Arguments.of(Named.of("transient symbolic code", "server_error"), Map.<String, Object>of(),
						OpenRouterTransientChoiceException.class, null),
				Arguments.of(Named.of("deterministic symbolic code", "invalid_request_error"), Map.<String, Object>of(),
						OpenRouterNonTransientChoiceException.class, null),
				Arguments.of(Named.of("metadata error type beats a transient code", "server_error"),
						Map.<String, Object>of("error_type", "invalid_request"),
						OpenRouterNonTransientChoiceException.class, "invalid_request"),
				Arguments.of(Named.of("upper-case transient code is normalized", "SERVER_ERROR"),
						Map.<String, Object>of(), OpenRouterTransientChoiceException.class, null));
	}

	@ParameterizedTest
	@MethodSource("symbolicChoiceCodes")
	void symbolicChoiceCodesClassifyTheFailure(String code, Map<String, Object> metadata,
			Class<? extends OpenRouterChoiceFailure> failureType, String errorType) {
		ChatCompletionResponse response = choiceErrorResponse(code, metadata);

		assertThatThrownBy(() -> this.responseMapper.map(response)).isInstanceOf(failureType).satisfies(thrown -> {
			var details = ((OpenRouterChoiceFailure) thrown).getErrorDetails();
			assertThat(details.code()).isEqualTo(code);
			assertThat(details.errorType()).isEqualTo(errorType);
		});
	}

	@ParameterizedTest(name = "streaming native failure {0} -> {1}")
	@CsvSource({ "error, UNKNOWN", "insufficient_system_resources, PROVIDER_UNAVAILABLE",
			"insufficient_quota, BILLING_CREDITS" })
	void streamingNativeFailureReasonsRaiseTypedFailures(String nativeReason, OpenRouterErrorCategory category) {
		Choice choice = new Choice(0, null, new Delta("assistant", "partial", null, null), nativeReason, nativeReason);
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-native", "chat.completion.chunk", 1L, "m", "p",
				List.of(choice), null, null);
		OpenRouterApi api = Mockito.mock(OpenRouterApi.class);
		Mockito.when(api.chatCompletionStream(ArgumentMatchers.any())).thenReturn(Flux.just(chunk));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.build();

		StepVerifier.create(model.stream(new Prompt("synthetic")))
			.expectErrorSatisfies(
					thrown -> assertThat(thrown).isInstanceOfSatisfying(OpenRouterChoiceFailure.class, failure -> {
						assertThat(failure.getCategory()).isEqualTo(category);
						assertThat(failure.getErrorDetails().nativeFinishReason()).isEqualTo(nativeReason);
					}))
			.verify();
	}

	@Test
	void missingContentMapsToAnEmptyAssistantMessage() {
		ChatCompletionResponse response = new ChatCompletionResponse("gen-1", "chat.completion", 1L, "m", "p",
				List.of(new Choice(0, new ChatMessage("assistant", null, null, null, null), null, "stop", "stop")),
				null);

		ChatResponse mapped = this.responseMapper.map(response);

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getOutput().getToolCalls()).isEmpty();
		assertThat(mapped.getResult().getOutput().getMedia()).isEmpty();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@Test
	void modelRetriesATransientChoiceFailureAndReturnsTheSuccessfulResponse() throws IOException {
		ChatCompletionResponse failed = parseFixture("chat-completion-choice-error.json", ChatCompletionResponse.class);
		ChatCompletionResponse successful = parseFixture("chat-completion-success.json", ChatCompletionResponse.class);
		OpenRouterApi api = Mockito.mock(OpenRouterApi.class);
		Mockito.when(api.chatCompletion(ArgumentMatchers.any())).thenReturn(failed, successful);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.retryTemplate(transientRetryTemplate(2))
			.build();

		ChatResponse response = model.call(new Prompt("synthetic"));

		Mockito.verify(api, Mockito.times(2)).chatCompletion(ArgumentMatchers.any());
		assertThat(response.getResult().getOutput().getText()).isEqualTo("The capital of France is Paris.");
	}

	@Test
	void modelDoesNotRetryADeterministicChoiceFailure() throws IOException {
		ChatCompletionResponse failed = parseFixture("chat-completion-mixed-choice-error.json",
				ChatCompletionResponse.class);
		OpenRouterApi api = Mockito.mock(OpenRouterApi.class);
		Mockito.when(api.chatCompletion(ArgumentMatchers.any())).thenReturn(failed);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.retryTemplate(transientRetryTemplate(2))
			.build();

		assertThatThrownBy(() -> model.call(new Prompt("synthetic")))
			.isInstanceOf(OpenRouterNonTransientChoiceException.class);
		Mockito.verify(api, Mockito.times(1)).chatCompletion(ArgumentMatchers.any());
	}

	@Test
	void everyProviderControlledDiagnosticFieldIsBoundedAndExcludedFromMessage() {
		String tailMarker = "must-not-reach-the-exception-message";
		String providerValue = "x".repeat(1200) + tailMarker;
		ChoiceError error = new ChoiceError(providerValue, providerValue,
				Map.of("error_type", providerValue, "provider_code", List.of(providerValue)));
		Choice choice = new Choice(0, new ChatMessage("assistant", providerValue, null, null, null), null,
				providerValue, providerValue, error);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-long-fields", "chat.completion", 1L, "m",
				providerValue, List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterNonTransientChoiceException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter chat-completion choice failed");
				assertThat(exception.getErrorDetails().provider()).hasSize(1003)
					.endsWith("...")
					.doesNotContain(tailMarker);
				assertThat(exception.getErrorDetails().message()).hasSize(1003).endsWith("...");
				assertThat(exception.getErrorDetails().partialOutput()).hasSize(503).endsWith("...");
				assertThat(exception.getErrorDetails().partialOutputTruncated()).isTrue();
			});
	}

	@Test
	void credentialShapedChoiceDiagnosticsAreRedactedFromStructuredFields() {
		String providerSecret = "sk-provider-secret";
		String messageSecret = "sk-message-secret";
		String metadataSecret = "sk-metadata-secret";
		String partialSecret = "sk-partial-secret";
		ChoiceError error = new ChoiceError("invalid_request", "{\"authorization\":\"Bearer " + messageSecret + "\"}",
				Map.of("error_type", "invalid_request", "provider_code", metadataSecret));
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial output " + partialSecret, null, null, null),
				null, "error", "error", error);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-secret-fields", "chat.completion", 1L, "m",
				"Bearer " + providerSecret, List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterNonTransientChoiceException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter chat-completion choice failed")
					.doesNotContain(providerSecret, messageSecret, metadataSecret, partialSecret);
				assertThat(exception.getErrorDetails().provider()).contains("[REDACTED]")
					.doesNotContain(providerSecret);
				assertThat(exception.getErrorDetails().message()).contains("[REDACTED]").doesNotContain(messageSecret);
				assertThat(exception.getErrorDetails().providerCode()).contains("[REDACTED]")
					.doesNotContain(metadataSecret);
				assertThat(exception.getErrorDetails().partialOutput()).contains("[REDACTED]")
					.doesNotContain(partialSecret);
			});
	}

	@Test
	void nativeFinishReasonIsDetachedBoundedAndCredentialSafe() {
		String nativeSecret = "sk-native-secret";
		String oversizedDetail = "x".repeat(1200);
		Map<String, Object> nativeReason = Map.of("token", nativeSecret, "detail", oversizedDetail);
		ChoiceError error = new ChoiceError("invalid_request", "provider failed", Map.of());
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial", null, null, null), null, "error",
				nativeReason, error);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-native-diagnostic", "chat.completion", 1L,
				"m", "p", List.of(choice), null);

		assertThatThrownBy(() -> this.responseMapper.map(response))
			.isInstanceOfSatisfying(OpenRouterNonTransientChoiceException.class, exception -> {
				assertThat(exception.getErrorDetails().nativeFinishReason()).isInstanceOfSatisfying(Map.class,
						value -> {
							assertThat(value).isNotSameAs(nativeReason).containsEntry("token", "[REDACTED]");
							assertThat((String) value.get("detail")).hasSize(1003).endsWith("...");
						});
				assertThat(exception.getErrorDetails().nativeFinishReason().toString()).doesNotContain(nativeSecret);
			});
	}

	@Test
	void usageJsonAliasesForResponsesApiNamesDeserialize() {
		// The Responses API reports usage as input_tokens/output_tokens (and
		// cached_tokens instead of cache_read_input_tokens); @JsonAlias maps them onto
		// the chat-style fields. Removing any alias must fail this test.
		Usage usage = this.objectMapper
			.readValue("{\"input_tokens\":7,\"output_tokens\":3,\"total_tokens\":10,\"cached_tokens\":2}", Usage.class);

		assertThat(usage.promptTokens()).isEqualTo(7);
		assertThat(usage.completionTokens()).isEqualTo(3);
		assertThat(usage.totalTokens()).isEqualTo(10);
		assertThat(usage.cachedTokens()).isEqualTo(2);
	}

	// ---------------------------------------------------------------------
	// In-memory edge cases that complement the fixtures
	// ---------------------------------------------------------------------

	@Test
	void mapsStructuredContentPartsToTextAndMedia() throws IOException {
		ChatCompletionResponse response = parseFixture("chat-completion-content-parts.json",
				ChatCompletionResponse.class);

		ChatResponse mapped = this.responseMapper.map(response);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("First part, second part.");
		assertThat(mapped.getResult().getOutput().getMedia()).singleElement().satisfies(media -> {
			assertThat(media.getMimeType()).isEqualTo(MimeTypeUtils.IMAGE_JPEG);
			assertThat(media.getData()).isEqualTo("data:image/jpeg;base64,aW1hZ2U=");
		});
	}

	@Test
	void missingUsageKeepsSpringAiEmptyUsageDefault() {
		ChatCompletionResponse response = new ChatCompletionResponse("gen-1", "chat.completion", 1L, "m", "p",
				List.of(new Choice(0, new ChatMessage("assistant", "hi", null, null, null), null, "stop", "stop")),
				null);

		ChatResponse mapped = this.responseMapper.map(response);

		// EmptyUsage preserves Spring AI's non-null contract without inventing a
		// provider usage record.
		assertThat(mapped.getMetadata().getUsage()).isInstanceOf(org.springframework.ai.chat.metadata.EmptyUsage.class);
		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hi");
	}

	private RetryTemplate transientRetryTemplate(long maxRetries) {
		RetryPolicy retryPolicy = RetryPolicy.builder()
			.maxRetries(maxRetries)
			.includes(TransientAiException.class)
			.delay(Duration.ZERO)
			.build();
		return new RetryTemplate(retryPolicy);
	}

	private ChatCompletionResponse choiceErrorResponse(String code, Map<String, Object> metadata) {
		ChoiceError error = new ChoiceError(code, "Provider failure", metadata);
		Choice choice = new Choice(0, new ChatMessage("assistant", "partial", null, null, null), null, "error", "error",
				error);
		return new ChatCompletionResponse("gen-symbolic", "chat.completion", 1L, "m", "p", List.of(choice), null);
	}

}
