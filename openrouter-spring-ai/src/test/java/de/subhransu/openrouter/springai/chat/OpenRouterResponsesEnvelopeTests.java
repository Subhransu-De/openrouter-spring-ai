package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import io.micrometer.observation.tck.TestObservationRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

class OpenRouterResponsesEnvelopeTests {

	private static final String TOOL = """
			{"type":"function_call","id":"synthetic","call_id":"synthetic","name":"synthetic_action","arguments":"{}","status":"completed"}
			"""
		.strip();

	private final TestObservationRegistry observations = TestObservationRegistry.create();

	static Stream<Arguments> invalidResponses() {
		return Stream.of(
				Arguments.of("{}", OpenRouterProtocolException.class, "OpenRouter response requires Responses status"),
				Arguments.of("{\"output\":[]}", OpenRouterProtocolException.class,
						"OpenRouter response requires Responses status"),
				Arguments.of("{\"status\":\"completed\"}", OpenRouterProtocolException.class,
						"OpenRouter response requires Responses output"),
				Arguments.of("{\"status\":\"completed\",\"output\":null}", OpenRouterProtocolException.class,
						"OpenRouter response requires Responses output"),
				Arguments.of("{\"status\":\"in_progress\",\"output\":[]}", OpenRouterTruncatedResponseException.class,
						"Responses result is not final: status=in_progress"),
				Arguments.of("{\"status\":\"queued\",\"output\":[]}", OpenRouterTruncatedResponseException.class,
						"Responses result is not final: status=queued"),
				Arguments.of("{\"status\":\"cancelled\",\"output\":[]}", OpenRouterTruncatedResponseException.class,
						"Responses result is not final: status=cancelled"),
				Arguments.of("{\"status\":\"future_status\",\"output\":[]}", OpenRouterTruncatedResponseException.class,
						"Responses result is not final: status=future_status"),
				Arguments.of("{\"status\":\"failed\"}", OpenRouterNonTransientApiException.class,
						"OpenRouter responses request failed"),
				Arguments.of("{\"error\":{}}", OpenRouterNonTransientApiException.class,
						"OpenRouter responses request failed"),
				Arguments.of("{\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}",
						OpenRouterTransientApiException.class, "OpenRouter responses request failed"));
	}

	@ParameterizedTest
	@MethodSource("modesWithInvalidResponses")
	void invalidBodiesRecordFailuresInBothModes(String mode, String json, Class<? extends Throwable> error,
			String message) {
		boolean streaming = !"call".equals(mode);
		OpenRouterChatModel model = model(streaming, wire(mode, json));

		assertThatThrownBy(() -> invoke(model, streaming)).isInstanceOf(error).hasMessageContaining(message);
		assertFailure(error);
	}

	static Stream<Arguments> modesWithInvalidResponses() {
		return Stream.of("call", "completed", "incomplete")
			.flatMap(mode -> invalidResponses().map(arguments -> Arguments.of(Named.of(mode, mode), arguments.get()[0],
					arguments.get()[1], messageFor(mode, (String) arguments.get()[2]))));
	}

	// A completed snapshot inside an "incomplete" terminal event is rejected before its
	// missing output is read.
	private static String messageFor(String mode, String message) {
		return "incomplete".equals(mode) && message.endsWith("Responses output")
				? "Responses terminal event disagrees with response status" : message;
	}

	static Stream<Arguments> nonObjectOutputItems() {
		return Stream
			.of(Arguments.of("call", "Failed to decode OpenRouter /responses response"),
					Arguments.of("completed", "Failed to decode OpenRouter stream chunk"),
					Arguments.of("item", "Failed to decode OpenRouter stream chunk"))
			.flatMap(row -> Stream.of("123", "[]", "\"synthetic\"", "true")
				.map(item -> Arguments.of(row.get()[0], row.get()[1], item)));
	}

	@ParameterizedTest
	@MethodSource("nonObjectOutputItems")
	void nonObjectOutputItemsFailAsMalformed(String mode, String message, String item) {
		String output = "{\"status\":\"completed\",\"output\":[" + item + "]}";
		String body = switch (mode) {
			case "call" -> output;
			case "completed" -> wire(mode, output);
			default -> "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":" + item + "}\n\n"
					+ wire("completed", "{\"status\":\"completed\",\"output\":[]}");
		};
		boolean streaming = !"call".equals(mode);
		assertThatThrownBy(() -> invoke(model(streaming, body), streaming))
			.isInstanceOf(OpenRouterProtocolException.class)
			.hasMessageContaining(message);
		assertFailure(OpenRouterProtocolException.class);
	}

	@ParameterizedTest
	@MethodSource("modesWithErrorEnvelopes")
	void errorEnvelopeKeepsItsClassificationAndBoundedTypedDiagnostics(String mode, String json,
			Class<? extends Throwable> type, int status, OpenRouterErrorCategory category, String code,
			String providerCode) {
		boolean streaming = !"call".equals(mode);
		assertThatThrownBy(() -> invoke(model(streaming, wire(mode, json)), streaming)).isInstanceOf(type)
			.satisfies(thrown -> {
				OpenRouterHttpException error = (OpenRouterHttpException) thrown;
				assertThat(error.getStatusCode().value()).isEqualTo(status);
				assertThat(error.getCategory()).isEqualTo(category);
				assertThat(error.getErrorDetails().code()).isEqualTo(code);
				assertThat(error.getErrorDetails().providerCode()).isEqualTo(providerCode);
				assertThat(error.getErrorDetails().message()).doesNotContain("\n")
					.hasSizeLessThanOrEqualTo(OpenRouterExceptionMessage.MAX_DIAGNOSTIC_LENGTH + 3);
				assertThat(error.getMessage()).doesNotContain("synthetic");
			});
		assertFailure(type);
	}

	static Stream<Arguments> modesWithErrorEnvelopes() {
		String overridesStatus = "{\"status\":\"completed\",\"output\":[],\"error_type\":\"provider_unavailable\","
				+ "\"error\":{\"code\":503,\"message\":\"synthetic\\nfailure " + "x".repeat(1500)
				+ "\",\"metadata\":{\"provider_code\":\"synthetic_code\"}}}";
		String errorOnly = "{\"error\":{\"code\":401,\"message\":\"synthetic failure\"}}";
		return Stream.of("call", "completed", "incomplete")
			.flatMap(mode -> Stream
				.of(Arguments.of(Named.of("an error envelope overrides a completed status", overridesStatus),
						OpenRouterTransientApiException.class, 503, OpenRouterErrorCategory.PROVIDER_UNAVAILABLE, "503",
						"synthetic_code"),
						Arguments.of(Named.of("an error-only body stays non-transient", errorOnly),
								OpenRouterNonTransientApiException.class, 401, OpenRouterErrorCategory.AUTHENTICATION,
								"401", null))
				.map(arguments -> Arguments.of(Named.of(mode, mode), arguments.get()[0], arguments.get()[1],
						arguments.get()[2], arguments.get()[3], arguments.get()[4], arguments.get()[5])));
	}

	@ParameterizedTest
	@MethodSource("malformedTerminals")
	void malformedTerminalNeverReleasesBufferedTools(String terminal, Class<? extends Throwable> error) {
		String body = "data: {\"type\":\"response.output_item.done\",\"item\":" + TOOL + "}\n\n" + "data: " + terminal
				+ "\n\n";
		assertNoToolExecution(true, body, error);
	}

	static Stream<Arguments> malformedTerminals() {
		return Stream.of(Arguments.of("{\"type\":\"response.completed\"}", OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"type\":\"response.incomplete\",\"response\":null}",
						OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\",\"output\":[]}}",
						OpenRouterProtocolException.class),
				Arguments.of("{\"type\":\"response.incomplete\",\"response\":{\"status\":\"completed\",\"output\":[]}}",
						OpenRouterProtocolException.class),
				Arguments.of("{\"type\":\"response.completed\",\"error\":{\"code\":503}}",
						OpenRouterTransientApiException.class));
	}

	@ParameterizedTest
	@ValueSource(strings = { "{\"delta\":\"synthetic\"}", "{\"type\":null,\"delta\":\"synthetic\"}",
			"{\"type\":\"\",\"delta\":\"synthetic\"}", "{\"type\":\" \",\"delta\":\"synthetic\"}" })
	void typelessEventFailsBeforeSuccessfulTerminal(String event) {
		String body = "data: " + event + "\n\n" + wire("completed", "{\"status\":\"completed\",\"output\":[]}");
		assertThatThrownBy(() -> invoke(model(true, body), true)).isInstanceOf(OpenRouterProtocolException.class);
		assertFailure(OpenRouterProtocolException.class);
	}

	@Test
	void typelessErrorEnvelopeKeepsProviderFailure() {
		String body = "data: {\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}\n\n";
		assertThatThrownBy(() -> invoke(model(true, body), true))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, error -> {
				assertThat(error.getStatusCode().value()).isEqualTo(503);
				assertThat(error.getCategory()).isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
				assertThat(error.getErrorDetails().code()).isEqualTo("503");
				assertThat(error.getErrorDetails().message()).isEqualTo("synthetic failure");
			});
		assertFailure(OpenRouterTransientApiException.class);
	}

	@Test
	void unknownNamedEventRemainsANoOp() {
		String body = "data: {\"type\":\"response.synthetic_future\",\"delta\":\"synthetic\"}\n\n"
				+ wire("completed", "{\"status\":\"completed\",\"output\":[]}");
		List<ChatResponse> responses = model(true, body).stream(new Prompt("synthetic"))
			.collectList()
			.block(Duration.ofSeconds(5));
		assertThat(responses).allSatisfy(response -> assertThat(response.getResult().getOutput().getText()).isEmpty());
		assertThat(responses.get(responses.size() - 1).getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void providerErrorNeverExecutesAdvertisedTools(boolean streaming) {
		String json = "{\"status\":\"completed\",\"output\":[" + TOOL + "],\"error\":{\"code\":503}}";
		assertNoToolExecution(streaming, streaming ? wire("completed", json) : json,
				OpenRouterTransientApiException.class);
	}

	private void assertNoToolExecution(boolean streaming, String body, Class<? extends Throwable> error) {
		AtomicInteger executions = new AtomicInteger();
		var callback = FunctionToolCallback.builder("synthetic_action", (Map<String, Object> input) -> {
			executions.incrementAndGet();
			return "ok";
		}).description("Synthetic counter").inputType(Map.class).build();
		var client = ChatClient.builder(model(streaming, body))
			.defaultAdvisors(ToolCallingAdvisor.builder().build())
			.build();
		assertThatThrownBy(() -> {
			var request = client.prompt()
				.user("synthetic")
				.options(OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.toolCallbacks(callback));
			if (streaming) {
				request.stream().content().collectList().block(Duration.ofSeconds(5));
			}
			else {
				request.call().content();
			}
		}).isInstanceOf(error);
		assertThat(executions).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "completed", "incomplete" })
	void terminalEventWithoutASnapshotFailsAsTruncatedAndRecordsTheFailure(String status) {
		assertThatThrownBy(() -> invoke(model(true, "data: {\"type\":\"response." + status + "\"}\n\n"), true))
			.isInstanceOf(OpenRouterTruncatedResponseException.class);
		assertFailure(OpenRouterTruncatedResponseException.class);
	}

	private static final String EMPTY_TEXT_MESSAGE = "[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"\"}]}]";

	private static final String IMAGE_ITEM = "{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"aW1hZ2U=\"}";

	static Stream<Arguments> emptyOutputs() {
		return Stream.of(Arguments.of(Named.of("no output items", "[]"), false),
				Arguments.of(Named.of("no output items", "[]"), true),
				Arguments.of(Named.of("an empty text message", EMPTY_TEXT_MESSAGE), false),
				Arguments.of(Named.of("an empty text message", EMPTY_TEXT_MESSAGE), true));
	}

	@ParameterizedTest(name = "[{index}] {0}, streaming={1}")
	@MethodSource("emptyOutputs")
	void emptyTextDoesNotInvalidateCompletedOutput(String output, boolean streaming) {
		ChatResponse response = lastOf(completedResponses(output, streaming, false));
		assertThat(response.getResult().getOutput().getText()).isEmpty();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedRefusalKeepsItsTextAsMetadata(boolean streaming) {
		String output = "[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"synthetic refusal\"}]}]";
		ChatResponse response = lastOf(completedResponses(output, streaming, false));
		assertThat(response.getResult().getOutput().getText()).isEmpty();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(response.getResult().getOutput().getMetadata()).containsEntry("openrouter.refusal",
				"synthetic refusal");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedToolCallFinishesWithToolCalls(boolean streaming) {
		ChatResponse response = lastOf(completedResponses("[" + TOOL + "]", streaming, false));
		assertThat(response.getResult().getOutput().getText()).isEmpty();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void completedImageGenerationYieldsOneMediaItem(boolean streaming) {
		List<ChatResponse> responses = completedResponses("[" + IMAGE_ITEM + "]", streaming, true);
		ChatResponse response = lastOf(responses);
		assertThat(response.getResult().getOutput().getText()).isEmpty();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(responses).anySatisfy(part -> assertThat(part.getResult().getOutput().getMedia()).hasSize(1));
	}

	private List<ChatResponse> completedResponses(String output, boolean streaming, boolean imageItemEvent) {
		String json = "{\"status\":\"completed\",\"output\":" + output + ",\"future_field\":true}";
		String body = streaming ? wire("completed", json) : json;
		if (streaming && imageItemEvent) {
			body = "data: {\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
					+ output.substring(1, output.length() - 1) + "}\n\n" + body;
		}
		var model = model(streaming, body);
		return streaming ? model.stream(new Prompt("synthetic")).collectList().block(Duration.ofSeconds(5))
				: List.of(model.call(new Prompt("synthetic")));
	}

	private static ChatResponse lastOf(List<ChatResponse> responses) {
		return responses.get(responses.size() - 1);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void validIncompleteTextPreservesItsReason(boolean streaming) {
		String json = """
				{"status":"incomplete","output":[{"type":"message","content":[{"type":"output_text","text":"partial"}]}],
				 "incomplete_details":{"reason":"max_output_tokens"}}
				"""
			.replace("\n", "");
		ChatResponse response = invoke(model(streaming, streaming ? wire("incomplete", json) : json), streaming);
		assertThat(response.getResult().getOutput().getText()).isEqualTo("partial");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("LENGTH");
		assertThat(response.getResult().getMetadata().<String>get("openrouter.native_finish_reason"))
			.isEqualTo("max_output_tokens");
	}

	private void assertFailure(Class<? extends Throwable> error) {
		io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(this.observations)
			.hasObservationWithNameEqualTo("gen_ai.client.operation")
			.that()
			.hasBeenStopped()
			.thenError()
			.isInstanceOf(error);
	}

	private static String wire(String mode, String json) {
		return "call".equals(mode) ? json : "data: {\"type\":\"response." + mode + "\",\"response\":" + json + "}\n\n";
	}

	private static ChatResponse invoke(OpenRouterChatModel model, boolean streaming) {
		return streaming ? model.stream(new Prompt("synthetic")).blockLast(Duration.ofSeconds(5))
				: model.call(new Prompt("synthetic"));
	}

	private OpenRouterChatModel model(boolean streaming, String body) {
		RestClient.Builder rest = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		if (!streaming) {
			server.expect(requestTo("https://openrouter.test/responses"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		}
		var web = WebClient.builder().clientConnector((method, uri, callback) -> {
			var outgoing = new org.springframework.mock.http.client.reactive.MockClientHttpRequest(method, uri);
			var incoming = new org.springframework.mock.http.client.reactive.MockClientHttpResponse(HttpStatus.OK);
			incoming.getHeaders().setContentType(MediaType.TEXT_EVENT_STREAM);
			incoming.setBody(body);
			return callback.apply(outgoing).thenReturn(incoming);
		});
		var api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.baseUrl("https://openrouter.test")
			.restClientBuilder(rest)
			.webClientBuilder(web)
			.build();
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.observationRegistry(this.observations)
			.retryTemplate(new RetryTemplate(RetryPolicy.builder().maxRetries(0).build()))
			.defaultOptions(OpenRouterChatOptions.builder()
				.model("synthetic")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.build())
			.build();
	}

}
