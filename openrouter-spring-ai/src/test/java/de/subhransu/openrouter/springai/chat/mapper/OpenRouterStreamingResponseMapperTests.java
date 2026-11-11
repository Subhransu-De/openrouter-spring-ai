package de.subhransu.openrouter.springai.chat.mapper;

import static de.subhransu.openrouter.springai.chat.mapper.StreamingMapperSupport.chatMapper;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingMapperSupport.map;
import static de.subhransu.openrouter.springai.chat.mapper.StreamingMapperSupport.mapFirst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenRouterStreamingResponseMapperTests {

	private static final String CHUNK_OBJECT = "chat.completion.chunk";

	private static final String MODEL = "openai/gpt-5.4-mini";

	@Test
	void validatesEveryChoiceBeforeOpeningTheBudget() {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"choices":[{"index":0,"delta":{"content":"text"}},null]}
				""", ChatCompletionChunk.class);
		assertThatThrownBy(() -> mapFirst(chatMapper(1, 1), chunk)).isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("OpenRouter response requires choice");
	}

	@Test
	void choiceFailureTakesPrecedenceOverANullSibling() {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"choices":[null,{"index":1,"error":{"code":"502","message":"synthetic"}}]}
				""", ChatCompletionChunk.class);
		assertThatThrownBy(() -> mapFirst(chatMapper(1, 1), chunk)).isInstanceOfSatisfying(
				OpenRouterTransientChoiceException.class,
				error -> assertThat(error.getErrorDetails().choiceIndex()).isEqualTo(1));
	}

	@Test
	void mapsContentDeltaChunkWithWireChoiceIndex() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(7, null, new Delta("assistant", "hello", null, null), null, null)), null, null);

		ChatResponse mapped = mapFirst(chatMapper(), chunk);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hello");
		assertThat(mapped.getMetadata().getId()).isEqualTo("gen-1");
		assertThat(mapped.getMetadata().getModel()).isEqualTo(MODEL);
		assertThat(mapped.getResult().getMetadata().<Integer>get("openrouter.choice_index")).isEqualTo(7);
	}

	@Test
	void mapsDeltaToolCallsToAssistantToolCalls() {
		// Streaming providers deliver tool calls on the Delta, not on a Message; this
		// path is what the model-level streaming tool-call surfacing relies on.
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null,
						new Delta("assistant", null, null,
								List.of(new ToolCall("call-9", "function",
										new FunctionCall("get_weather", "{\"city\":\"Oslo\"}")))),
						"tool_calls", "tool_calls")),
				null, null);

		ChatResponse mapped = mapFirst(chatMapper(), chunk);

		assertThat(mapped.hasToolCalls()).isTrue();
		assertThat(mapped.getResult().getOutput().getToolCalls().get(0).id()).isEqualTo("call-9");
		assertThat(mapped.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("get_weather");
		assertThat(mapped.getResult().getOutput().getToolCalls().get(0).arguments()).isEqualTo("{\"city\":\"Oslo\"}");
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
	}

	@Test
	void throwsOnMidStreamErrorChunk() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", "", null, null), "error", "error")), null,
				new StreamError("server_error",
						"Provider disconnected unexpectedly\r\nforged\u001B[31m Bearer stream-secret"));

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk)).isInstanceOf(OpenRouterApiException.class)
			.hasMessage("OpenRouter chat completion stream failed")
			.satisfies(thrown -> {
				OpenRouterApiException ex = (OpenRouterApiException) thrown;
				// HTTP 200 carried the failure; the mapper synthesizes a 500 so callers
				// see a server-side fault rather than a clean completion.
				assertThat(ex.getStatusCode().value()).isEqualTo(500);
				assertThat(ex.getResponseBody()).contains("server_error");
				assertThat(ex.getErrorDetails().message())
					.isEqualTo("Provider disconnected unexpectedly forged [31m Bearer [REDACTED]");
				assertThat(ex.getResponseBody()).doesNotContain("\r", "\n", "\u001B", "stream-secret");
			});
	}

	@Test
	void usesFallbackMessageWhenStreamErrorMessageIsNull() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", null, null,
				new StreamError("server_error", null));

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk)).isInstanceOfSatisfying(OpenRouterApiException.class,
				exception -> {
					assertThat(exception).hasMessage("OpenRouter chat completion stream failed");
					assertThat(exception.getErrorDetails().code()).isEqualTo("server_error");
					assertThat(exception.getErrorDetails().message())
						.isEqualTo("OpenRouter chat completion stream failed");
					assertThat(exception.getStatusCode().value()).isEqualTo(500);
				});
	}

	@Test
	void parsesNonStringStreamErrorMessageWithoutLosingTaxonomy() throws Exception {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"error":{"code":"invalid_api_key","message":{"detail":"invalid key"},
				 "error_type":"authentication"}}
				""", ChatCompletionChunk.class);

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk)).isInstanceOfSatisfying(OpenRouterApiException.class,
				exception -> {
					assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
					assertThat(exception.getErrorDetails().message()).isEqualTo("{\"detail\":\"invalid key\"}");
				});
	}

	@Test
	void parsesNonStringStreamErrorTypeWithoutDroppingTheEvent() throws Exception {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"error":{"code":"invalid_api_key","message":"invalid key",
				 "error_type":{"kind":"authentication"}}}
				""", ChatCompletionChunk.class);

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk)).isInstanceOfSatisfying(OpenRouterApiException.class,
				exception -> {
					assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
					assertThat(exception.getErrorDetails().code()).isEqualTo("invalid_api_key");
					assertThat(exception.getErrorDetails().errorType()).isEqualTo("{\"kind\":\"authentication\"}");
				});
	}

	@Test
	void throwsTypedTransientExceptionForChoiceError() {
		ChoiceError error = new ChoiceError("502", "Provider disconnected",
				Map.of("error_type", "provider_unavailable", "provider_code", "upstream_reset"));
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai", List
			.of(new Choice(0, null, new Delta("assistant", "partial output", null, null), "error", "error", error)),
				null, null);

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk))
			.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
				assertThat(exception.getErrorDetails().choiceIndex()).isZero();
				assertThat(exception.getErrorDetails().partialOutput()).isEqualTo("partial output");
				assertThat(exception.getErrorDetails().providerCode()).isEqualTo("upstream_reset");
			});
	}

	static Stream<Arguments> truncationCases() {
		String x498 = "x".repeat(498);
		String x499 = "x".repeat(499);
		String x500 = "x".repeat(500);
		return Stream.of(Arguments.of(Named.of("whitespace only", ""), "", null, false),
				Arguments.of(Named.of("whitespace-only prefix then text", ""), " \t\nnext", "next", false),
				Arguments.of(Named.of("498 chars", x498), "", x498, false),
				Arguments.of(Named.of("499 chars", x499), "", x499, false),
				Arguments.of(Named.of("exactly 500 chars", x500), "", x500, false),
				Arguments.of(Named.of("501 chars", "x".repeat(501)), "", x500 + "...", true),
				Arguments.of(Named.of("600 chars", "x".repeat(600)), "", x500 + "...", true),
				Arguments.of(Named.of("cut inside the failing text", x498), " \t\nnext", x498 + " n...", true),
				Arguments.of(Named.of("cut right after a space", x499), "\u00e9\uD83D\uDE00", x499 + " ...", true),
				Arguments.of(Named.of("cut before a surrogate pair", x498), "\u00e9\uD83D\uDE00", x498 + " \u00e9...",
						true),
				Arguments.of(Named.of("4096 chars in each chunk", "x".repeat(4096)), "x".repeat(4096), x500 + "...",
						true));
	}

	@ParameterizedTest
	@MethodSource("truncationCases")
	void earlierStreamedTextShapesBoundedChoiceErrorDiagnostics(String prefix, String tail, String expectedPartial,
			boolean expectedTruncated) {
		String earlier = prefix + " \t\r\n";
		var first = new ChatCompletionChunk(null, null, null, null, null,
				List.of(new Choice(0, null, new Delta(null, earlier, null, null), null, null)), null, null);
		var failure = new ChatCompletionChunk(null, null, null, null, null, List.of(new Choice(0, null,
				new Delta(null, tail, null, null), "error", null, new ChoiceError("502", "synthetic", Map.of()))), null,
				null);

		StepVerifier.create(map(chatMapper(), Flux.just(first, failure)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo(earlier))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
					assertThat(exception.getErrorDetails().partialOutput()).isEqualTo(expectedPartial);
					assertThat(exception.getErrorDetails().partialOutputTruncated()).isEqualTo(expectedTruncated);
				}))
			.verify();
	}

	private static final String REDACTION_KEY = "K".repeat(9);

	static Stream<Arguments> redactedCutCases() {
		// A nine-character key grows by one into "[REDACTED]".
		return Stream.of(
				Arguments.of(
						Named.of("redaction expands the text past the cut",
								"a".repeat(489) + REDACTION_KEY + "\uD83D\uDE00" + "z".repeat(20)),
						"a".repeat(489) + "[REDACTED]...", true),
				Arguments.of(
						Named.of("redacted key at the start",
								REDACTION_KEY + " " + "a".repeat(489) + " " + "z".repeat(20)),
						"[REDACTED] " + "a".repeat(489) + "...", true),
				Arguments.of(Named.of("cut lands inside a surrogate pair",
						"a".repeat(499) + "\uD83D\uDE00" + "z".repeat(20)), "a".repeat(499) + "...", true),
				Arguments.of(Named.of("leading Unicode whitespace is dropped", "\u2003".repeat(500) + "text"), "text",
						false));
	}

	@ParameterizedTest
	@MethodSource("redactedCutCases")
	void streamPartialOutputMatchesBlockingCutsAfterRedaction(String content, String expectedPartial,
			boolean expectedTruncated) {
		OpenRouterStreamingResponseMapper mapper = new OpenRouterStreamingResponseMapper(
				OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_BYTES,
				OpenRouterStreamingResponseMapper.DEFAULT_MAX_STATE_CHOICES,
				OpenRouterDiagnosticSanitizer.forApiKey(REDACTION_KEY));
		var failure = new ChatCompletionChunk(null, null, null, null, null, List.of(new Choice(0, null,
				new Delta(null, content, null, null), "error", null, new ChoiceError("502", "synthetic", Map.of()))),
				null, null);

		StepVerifier.create(map(mapper, Flux.just(failure)))
			.expectErrorSatisfies(error -> assertThat(error)
				.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
					assertThat(exception.getErrorDetails().partialOutput()).isEqualTo(expectedPartial);
					assertThat(exception.getErrorDetails().partialOutputTruncated()).isEqualTo(expectedTruncated);
				}))
			.verify();
	}

	@Test
	void failedChoiceFailsMixedChunkBeforeSuccessfulSiblingIsMapped() {
		Choice successful = new Choice(0, null, new Delta("assistant", "complete answer", null, null), "stop", "stop");
		ChoiceError error = new ChoiceError("invalid_request", "Unsupported provider parameter",
				Map.of("error_type", "invalid_request"));
		Choice failed = new Choice(1, null, new Delta("assistant", "partial", null, null), "error", "error", error);
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(successful, failed), null, null);

		assertThatThrownBy(() -> mapFirst(chatMapper(), chunk)).isInstanceOfSatisfying(
				OpenRouterNonTransientChoiceException.class,
				exception -> assertThat(exception.getErrorDetails().choiceIndex()).isEqualTo(1));
	}

	@Test
	void nullDeltaProducesEmptyTextAndNoFinishReason() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, null, null, null)), null, null);

		ChatResponse mapped = mapFirst(chatMapper(), chunk);

		assertThat(mapped.getResults()).hasSize(1);
		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getOutput().getToolCalls()).isEmpty();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isNull();
	}

	@Test
	void surfacesReasoningDeltasPerChunkAndAccumulatesThemOnTheMessage() {
		ChatCompletionChunk first = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, "openai/gpt-oss-120b",
				"openai", List.of(new Choice(0, null, new Delta("assistant", "", "thinking ", null), null, null)), null,
				null);
		ChatCompletionChunk second = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, "openai/gpt-oss-120b",
				"openai", List.of(new Choice(0, null, new Delta("assistant", "", "aloud", null), null, null)), null,
				null);

		StepVerifier.create(map(chatMapper(), Flux.just(first, second))).assertNext(response -> {
			assertThat(response.getResult().getMetadata().<String>get("openrouter.reasoning")).isEqualTo("thinking ");
			assertThat(response.getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
					"thinking ");
		}).assertNext(response -> {
			assertThat(response.getResult().getMetadata().<String>get("openrouter.reasoning")).isEqualTo("aloud");
			assertThat(response.getResult().getOutput().getMetadata()).containsEntry(ReasoningMetadata.REASONING,
					"thinking aloud");
		}).verifyComplete();
	}

	@Test
	void terminalChunkWithoutTextDeltaDoesNotRepeatPriorText() {
		ChatCompletionChunk earlier = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", "prior text", null, null), null, null)), null, null);
		// A finish_reason-only terminal chunk carries an empty delta.
		ChatCompletionChunk terminal = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", null, null, null), "stop", "stop")), null, null);

		StepVerifier.create(map(chatMapper(), Flux.just(earlier, terminal)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("prior text"))
			.assertNext(response -> {
				assertThat(response.getResult().getOutput().getText()).isEmpty();
				assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
			})
			.verifyComplete();
	}

	@Test
	void mapsFinalUsageChunkWithEmptyChoices() {
		ChatCompletionChunk usageChunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(), new Usage(5, 2, 7, null, null, null, null, null, null), null);

		ChatResponse mapped = mapFirst(chatMapper(), usageChunk);

		assertThat(mapped.getResults()).isEmpty();
		assertThat(mapped.getMetadata().getUsage().getTotalTokens()).isEqualTo(7);
	}

}
