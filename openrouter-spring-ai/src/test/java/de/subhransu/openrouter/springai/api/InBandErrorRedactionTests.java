package de.subhransu.openrouter.springai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceErrorDetails;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceFailure;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingModel;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Errors carried inside a successful response or stream redact the configured API key and
 * its encoded forms, as HTTP errors do.
 */
class InBandErrorRedactionTests {

	// Without an sk- prefix, only the configured-key redaction can catch this key.
	private static final String KEY = "proxy-Key+/=_0123456789";

	private static final String BASE_URL = "https://openrouter.test";

	private static final String CHAT_COMPLETIONS = "/chat/completions";

	private static final Prompt PROMPT = new Prompt("synthetic prompt");

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final RetryTemplate NO_RETRY = new RetryTemplate(RetryPolicy.builder().maxRetries(0).build());

	@ParameterizedTest
	@MethodSource("echoes")
	void chatCompletionsErrorInASuccessfulBody(String echo) {
		Throwable failure = catchThrowable(
				() -> chatModel(blocking(CHAT_COMPLETIONS, json(Map.of("error", error(echo)))), false).call(PROMPT));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void chatCompletionsStreamErrorChunk(String echo) {
		Throwable failure = streamFailure(
				chatModel(streaming(json(Map.of("error", error(echo)))), false).stream(PROMPT));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void chatCompletionsChoiceError(String echo) {
		Map<String, Object> choice = Map.of("index", 0, "finish_reason", "error", "error", error(echo), "message",
				Map.of("role", "assistant", "content", "partial " + echo));
		Throwable failure = catchThrowable(
				() -> chatModel(blocking(CHAT_COMPLETIONS, json(Map.of("id", "gen-1", "choices", List.of(choice)))),
						false)
					.call(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterChoiceFailure.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void chatCompletionsStreamChoiceError(String echo) {
		Map<String, Object> choice = Map.of("index", 0, "finish_reason", "error", "error", error(echo), "delta",
				Map.of("content", "partial " + echo));
		Throwable failure = streamFailure(
				chatModel(streaming(json(Map.of("id", "gen-1", "choices", List.of(choice)))), false).stream(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterChoiceFailure.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void responsesFailedResult(String echo) {
		Throwable failure = catchThrowable(
				() -> chatModel(blocking("/responses", json(Map.of("status", "failed", "error", error(echo)))), true)
					.call(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterHttpException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void responsesStreamErrorEvent(String echo) {
		Throwable failure = streamFailure(
				chatModel(streaming(json(Map.of("type", "error", "error", error(echo)))), true).stream(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterHttpException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void embeddingsErrorInASuccessfulBody(String echo) {
		OpenRouterEmbeddingModel model = OpenRouterEmbeddingModel.builder()
			.openRouterApi(blocking("/embeddings", json(Map.of("error", error(echo)))))
			.defaultOptions(OpenRouterEmbeddingOptions.builder().model("test-embedding-model").build())
			.retryTemplate(NO_RETRY)
			.build();

		Throwable failure = catchThrowable(() -> model.embed("synthetic input"));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void imagesErrorInASuccessfulBody(String echo) {
		Throwable failure = catchThrowable(
				() -> imageModel(blocking("/images", json(Map.of("error", error(echo))))).call(imagePrompt()));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void imagesStreamErrorEvent(String echo) {
		Throwable failure = streamFailure(
				imageModel(streaming(json(Map.of("type", "error", "error", error(echo))))).stream(imagePrompt()));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void responsesStreamFailedEvent(String echo) {
		Map<String, Object> failed = Map.of("status", "failed", "error", error(echo));
		Throwable failure = streamFailure(
				chatModel(streaming(json(Map.of("type", "response.failed", "response", failed))), true).stream(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterHttpException.class);
		assertRedacted(failure, echo);
	}

	@Test
	void choicePartialOutputIsRedactedBeforeItIsCut() {
		String output = "y".repeat(495) + " " + KEY + " " + "z".repeat(100);
		Map<String, Object> choice = Map.of("index", 0, "finish_reason", "error", "error", error(KEY), "message",
				Map.of("role", "assistant", "content", output));

		Throwable failure = catchThrowable(
				() -> chatModel(blocking(CHAT_COMPLETIONS, json(Map.of("id", "gen-1", "choices", List.of(choice)))),
						false)
					.call(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterChoiceFailure.class);
		OpenRouterChoiceErrorDetails details = ((OpenRouterChoiceFailure) failure).getErrorDetails();
		assertThat(details).isNotNull();
		assertThat(details.partialOutputTruncated()).isTrue();
		assertThat(details.partialOutput()).doesNotContain(KEY.substring(0, 4)).endsWith("y [RED...");
	}

	@Test
	void streamedPartialOutputKeepsItsTruncationFlagWhenRedactionShortensIt() {
		String output = "y".repeat(400) + " " + KEY + " " + "z".repeat(200);
		Map<String, Object> choice = Map.of("index", 0, "finish_reason", "error", "error", error(KEY), "delta",
				Map.of("content", output));

		Throwable failure = streamFailure(
				chatModel(streaming(json(Map.of("id", "gen-1", "choices", List.of(choice)))), false).stream(PROMPT));

		assertThat(failure).isInstanceOf(OpenRouterChoiceFailure.class);
		OpenRouterChoiceErrorDetails details = ((OpenRouterChoiceFailure) failure).getErrorDetails();
		assertThat(details).isNotNull();
		assertThat(details.partialOutputTruncated()).isTrue();
		assertThat(details.partialOutput()).isEqualTo("y".repeat(400) + " [REDACTED] " + "z".repeat(75) + "...");
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void responsesIncompleteToolRound(String echo) {
		Map<String, Object> result = incompleteToolRound(echo);
		Throwable blocking = catchThrowable(() -> chatModel(blocking("/responses", json(result)), true).call(PROMPT));
		Throwable streamed = streamFailure(
				chatModel(streaming(json(Map.of("type", "response.incomplete", "response", result))), true)
					.stream(PROMPT));

		for (Throwable failure : List.of(blocking, streamed)) {
			assertThat(failure).isExactlyInstanceOf(OpenRouterTruncatedResponseException.class);
			assertThat(failure.getMessage()).doesNotContain(echo, KEY)
				.endsWith("item status=[REDACTED], incomplete reason=upstream [REDACTED]");
		}
	}

	@ParameterizedTest
	@MethodSource("echoes")
	void imagesStreamJsonFallbackError(String echo) {
		Throwable failure = streamFailure(
				imageModel(streaming(json(Map.of("error", error(echo))), MediaType.APPLICATION_JSON))
					.stream(imagePrompt()));

		assertThat(failure).isExactlyInstanceOf(OpenRouterApiException.class);
		assertRedacted(failure, echo);
	}

	@Test
	void categoryComesFromTheOriginalProviderTextBeforeRedaction() {
		// The classifier reads "moderation" in the message; redacting it first would lose
		// it.
		String key = "moderation-proxy-key-0123";
		Map<String, Object> error = Map.of("message", key);
		Map<String, Object> choice = Map.of("index", 0, "finish_reason", "error", "error", error, "message",
				Map.of("role", "assistant", "content", ""));

		Throwable inBand = catchThrowable(
				() -> chatModel(blocking(CHAT_COMPLETIONS, json(Map.of("error", error)), key), false).call(PROMPT));
		Throwable choiceFailure = catchThrowable(
				() -> chatModel(blocking(CHAT_COMPLETIONS, json(Map.of("choices", List.of(choice))), key), false)
					.call(PROMPT));

		assertThat(inBand).isExactlyInstanceOf(OpenRouterApiException.class);
		OpenRouterApiException api = (OpenRouterApiException) inBand;
		assertThat(api.getCategory()).isEqualTo(OpenRouterErrorCategory.CONTENT_FILTER_REFUSAL);
		assertThat(api.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(api.getErrorDetails()).extracting(OpenRouterErrorDetails::message).isEqualTo("[REDACTED]");
		assertThat(choiceFailure).isInstanceOf(OpenRouterChoiceFailure.class);
		OpenRouterChoiceErrorDetails details = ((OpenRouterChoiceFailure) choiceFailure).getErrorDetails();
		assertThat(details).isNotNull();
		assertThat(details.category()).isEqualTo(OpenRouterErrorCategory.CONTENT_FILTER_REFUSAL);
		assertThat(details.message()).isEqualTo("[REDACTED]");
	}

	private static Stream<Named<String>> echoes() {
		StringBuilder percent = new StringBuilder();
		StringBuilder unicode = new StringBuilder();
		for (int i = 0; i < KEY.length(); i++) {
			percent.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) KEY.charAt(i)));
			unicode.append("\\u").append(HexFormat.of().toHexDigits(KEY.charAt(i)));
		}
		return Stream.of(Named.of("plain", KEY), Named.of("percent-encoded", percent.toString()),
				Named.of("form-encoded", URLEncoder.encode(KEY, StandardCharsets.UTF_8)),
				Named.of("JSON unicode-escaped", unicode.toString()));
	}

	private static Map<String, Object> incompleteToolRound(String echo) {
		Map<String, Object> call = Map.of("type", "function_call", "call_id", "call-1", "name", "synthetic_tool",
				"arguments", "{}", "status", echo);
		return Map.of("status", "incomplete", "incomplete_details", Map.of("reason", "upstream " + echo), "output",
				List.of(call));
	}

	private static Map<String, Object> error(String echo) {
		return Map.of("code", "401", "message", "upstream rejected " + echo, "metadata",
				Map.of("provider_code", echo, "raw", "{\"auth\":\"" + echo + "\"}", "nested", List.of(echo)));
	}

	private static void assertRedacted(Throwable failure, String echo) {
		assertThat(failure.toString()).doesNotContain(echo, KEY);
		List<@Nullable Object> retained = new ArrayList<>();
		@Nullable String message = null;
		if (failure instanceof OpenRouterHttpException http) {
			retained.add(http.getResponseBody());
			retained.add(http.getErrorDetails());
			OpenRouterErrorDetails details = http.getErrorDetails();
			message = details != null ? details.message() : null;
		}
		if (failure instanceof OpenRouterApiException api) {
			retained.add(api.getResponseBody());
			retained.add(api.getErrorDetails());
			OpenRouterErrorDetails details = api.getErrorDetails();
			message = details != null ? details.message() : null;
		}
		if (failure instanceof OpenRouterChoiceFailure choice) {
			OpenRouterChoiceErrorDetails details = choice.getErrorDetails();
			retained.add(details);
			message = details != null ? details.message() : null;
			assertThat(details).isNotNull();
			assertThat(details.partialOutput()).isEqualTo("partial [REDACTED]");
		}
		assertThat(message).isEqualTo("upstream rejected [REDACTED]");
		assertThat(retained).isNotEmpty().allSatisfy(value -> {
			assertThat(value).isNotNull();
			assertThat(String.valueOf(value)).doesNotContain(echo, KEY).contains("[REDACTED]");
		});
	}

	private static Throwable streamFailure(Flux<?> stream) {
		return catchThrowable(() -> stream.blockLast());
	}

	private static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

	private static ImagePrompt imagePrompt() {
		return new ImagePrompt("synthetic image");
	}

	private static OpenRouterChatModel chatModel(OpenRouterApi api, boolean responses) {
		OpenRouterChatOptions.Builder options = OpenRouterChatOptions.builder().model("test-model");
		if (responses) {
			options.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES);
		}
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(options.build())
			.retryTemplate(NO_RETRY)
			.build();
	}

	private static OpenRouterImageModel imageModel(OpenRouterApi api) {
		return OpenRouterImageModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterImageOptions.builder().model("test-image-model").build())
			.retryTemplate(NO_RETRY)
			.build();
	}

	private static OpenRouterApi blocking(String path, String body) {
		return blocking(path, body, KEY);
	}

	private static OpenRouterApi blocking(String path, String body, String apiKey) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer.bindTo(rest)
			.build()
			.expect(manyTimes(), requestTo(BASE_URL + path))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		return OpenRouterApi.builder().apiKey(apiKey).baseUrl(BASE_URL).restClientBuilder(rest).build();
	}

	private static OpenRouterApi streaming(String event) {
		return streaming("data: " + event + "\n\n", MediaType.TEXT_EVENT_STREAM);
	}

	private static OpenRouterApi streaming(String body, MediaType contentType) {
		return OpenRouterApi.builder()
			.apiKey(KEY)
			.baseUrl(BASE_URL)
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", contentType.toString())
					.body(body)
					.build())))
			.build();
	}

}
