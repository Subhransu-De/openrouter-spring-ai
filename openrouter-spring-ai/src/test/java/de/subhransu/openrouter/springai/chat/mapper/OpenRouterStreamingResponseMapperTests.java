package de.subhransu.openrouter.springai.chat.mapper;

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
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper(1, 1).map(chunk))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessage("OpenRouter response requires choice");
	}

	@Test
	void choiceFailureTakesPrecedenceOverANullSibling() {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"choices":[null,{"index":1,"error":{"code":"502","message":"synthetic"}}]}
				""", ChatCompletionChunk.class);
		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper(1, 1).map(chunk)).isInstanceOfSatisfying(
				OpenRouterTransientChoiceException.class,
				error -> assertThat(error.getErrorDetails().choiceIndex()).isEqualTo(1));
	}

	@Test
	void mapsContentDeltaChunk() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", "hello", null, null), null, null)), null, null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(chunk);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hello");
		assertThat(mapped.getMetadata().getId()).isEqualTo("gen-1");
	}

	@Test
	void preservesWireChoiceIndexInGenerationMetadata() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(7, null, new Delta("assistant", "hello", null, null), null, null)), null, null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(chunk);

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

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(chunk);

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

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk))
			.isInstanceOf(OpenRouterApiException.class)
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

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk))
			.isInstanceOf(OpenRouterApiException.class)
			.hasMessage("OpenRouter chat completion stream failed");
	}

	@Test
	void parsesNonStringStreamErrorMessageWithoutLosingTaxonomy() throws Exception {
		ChatCompletionChunk chunk = new ObjectMapper().readValue("""
				{"error":{"code":"invalid_api_key","message":{"detail":"invalid key"},
				 "error_type":"authentication"}}
				""", ChatCompletionChunk.class);

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk))
			.isInstanceOfSatisfying(OpenRouterApiException.class, exception -> {
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

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk))
			.isInstanceOfSatisfying(OpenRouterApiException.class, exception -> {
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

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk))
			.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
				assertThat(exception.getErrorDetails().choiceIndex()).isZero();
				assertThat(exception.getErrorDetails().partialOutput()).isEqualTo("partial output");
				assertThat(exception.getErrorDetails().providerCode()).isEqualTo("upstream_reset");
			});
	}

	@Test
	void includesEarlierStreamedTextInChoiceErrorDiagnostics() {
		ChatCompletionChunk partial = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", "partial output", null, null), null, null)), null,
				null);
		ChoiceError error = new ChoiceError("502", "Provider disconnected",
				Map.of("error_type", "provider_unavailable"));
		ChatCompletionChunk failed = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta(null, "", null, null), "error", "error", error)), null, null);

		StepVerifier.create(new OpenRouterStreamingResponseMapper().map(Flux.just(partial, failed)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getText()).isEqualTo("partial output"))
			.expectErrorSatisfies(thrown -> {
				assertThat(thrown).isInstanceOf(OpenRouterTransientChoiceException.class);
				OpenRouterTransientChoiceException exception = (OpenRouterTransientChoiceException) thrown;
				assertThat(exception.getErrorDetails().partialOutput()).isEqualTo("partial output");
			})
			.verify();
	}

	@Test
	void boundsAccumulatedStreamedTextInChoiceErrorDiagnostics() {
		String partialOutput = "x".repeat(600);
		ChatCompletionChunk partial = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", partialOutput, null, null), null, null)), null,
				null);
		ChoiceError error = new ChoiceError("503", "Provider disconnected",
				Map.of("error_type", "provider_unavailable"));
		ChatCompletionChunk failed = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta(null, "", null, null), "error", "error", error)), null, null);

		StepVerifier.create(new OpenRouterStreamingResponseMapper().map(Flux.just(partial, failed)))
			.expectNextCount(1)
			.expectErrorSatisfies(thrown -> {
				assertThat(thrown).isInstanceOf(OpenRouterTransientChoiceException.class);
				OpenRouterTransientChoiceException exception = (OpenRouterTransientChoiceException) thrown;
				assertThat(exception.getErrorDetails().partialOutput()).hasSize(503).endsWith("...");
				assertThat(exception.getErrorDetails().partialOutputTruncated()).isTrue();
			})
			.verify();
	}

	@Test
	void truncationPreservesWhitespaceAndSplitBoundaryDiagnostics() {
		for (int size : List.of(0, 498, 499, 500, 501, 600, 4096)) {
			for (String tail : List.of("", " \t\n", " \t\nnext", "é😀", "x".repeat(4096))) {
				String earlier = "x".repeat(size) + " \t\r\n";
				String normalized = (earlier + tail).replaceAll("\\s+", " ").trim();
				boolean truncated = normalized.length() > OpenRouterChoiceErrorExceptionFactory.MAX_DIAGNOSTIC_LENGTH;
				String expected = truncated ? normalized.substring(0, 500) + "..." : normalized;
				var first = new ChatCompletionChunk(null, null, null, null, null,
						List.of(new Choice(0, null, new Delta(null, earlier, null, null), null, null)), null, null);
				var failure = new ChatCompletionChunk(null, null, null, null, null,
						List.of(new Choice(0, null, new Delta(null, tail, null, null), "error", null,
								new ChoiceError("502", "synthetic", Map.of()))),
						null, null);
				StepVerifier.create(new OpenRouterStreamingResponseMapper().map(Flux.just(first, failure)))
					.expectNextCount(1)
					.expectErrorSatisfies(error -> assertThat(error)
						.isInstanceOfSatisfying(OpenRouterTransientChoiceException.class, exception -> {
							assertThat(exception.getErrorDetails().partialOutput())
								.isEqualTo(expected.isEmpty() ? null : expected);
							assertThat(exception.getErrorDetails().partialOutputTruncated()).isEqualTo(truncated);
						}))
					.verify();
			}
		}
	}

	@Test
	void failedChoiceFailsMixedChunkBeforeSuccessfulSiblingIsMapped() {
		Choice successful = new Choice(0, null, new Delta("assistant", "complete answer", null, null), "stop", "stop");
		ChoiceError error = new ChoiceError("invalid_request", "Unsupported provider parameter",
				Map.of("error_type", "invalid_request"));
		Choice failed = new Choice(1, null, new Delta("assistant", "partial", null, null), "error", "error", error);
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(successful, failed), null, null);

		assertThatThrownBy(() -> new OpenRouterStreamingResponseMapper().map(chunk)).isInstanceOfSatisfying(
				OpenRouterNonTransientChoiceException.class,
				exception -> assertThat(exception.getErrorDetails().choiceIndex()).isEqualTo(1));
	}

	@Test
	void nullDeltaProducesEmptyText() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, null, null, null)), null, null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(chunk);

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
	}

	@Test
	void surfacesReasoningDeltaUnderReasoningMetadataKey() {
		ChatCompletionChunk chunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, "openai/gpt-oss-120b",
				"openai", List.of(new Choice(0, null, new Delta("assistant", "", "thinking aloud", null), null, null)),
				null, null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(chunk);

		assertThat(mapped.getResult().getMetadata().<String>get("openrouter.reasoning")).isEqualTo("thinking aloud");
	}

	@Test
	void terminalChunkWithoutTextDeltaDoesNotRepeatPriorText() {
		// A finish_reason-only terminal chunk carries an empty delta; mapping it must not
		// re-emit any earlier streamed content.
		ChatCompletionChunk terminal = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(new Choice(0, null, new Delta("assistant", null, null, null), "stop", "stop")), null, null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(terminal);

		assertThat(mapped.getResult().getOutput().getText()).isEmpty();
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@Test
	void mapsFinalUsageChunkWithEmptyChoices() {
		ChatCompletionChunk usageChunk = new ChatCompletionChunk("gen-1", CHUNK_OBJECT, 123L, MODEL, "openai",
				List.of(), new Usage(5, 2, 7, null, null, null, null, null, null), null);

		ChatResponse mapped = new OpenRouterStreamingResponseMapper().map(usageChunk);

		assertThat(mapped.getResults()).isEmpty();
		assertThat(mapped.getMetadata().getUsage().getTotalTokens()).isEqualTo(7);
	}

}
