package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
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
		return Stream.of(Arguments.of("{}", OpenRouterProtocolException.class),
				Arguments.of("{\"output\":[]}", OpenRouterProtocolException.class),
				Arguments.of("{\"status\":\"completed\"}", OpenRouterProtocolException.class),
				Arguments.of("{\"status\":\"completed\",\"output\":null}", OpenRouterProtocolException.class),
				Arguments.of("{\"status\":\"in_progress\",\"output\":[]}", OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"status\":\"queued\",\"output\":[]}", OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"status\":\"cancelled\",\"output\":[]}", OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"status\":\"future_status\",\"output\":[]}",
						OpenRouterTruncatedResponseException.class),
				Arguments.of("{\"status\":\"failed\"}", OpenRouterNonTransientApiException.class),
				Arguments.of("{\"error\":{}}", OpenRouterNonTransientApiException.class),
				Arguments.of("{\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}",
						OpenRouterTransientApiException.class));
	}

	@ParameterizedTest
	@MethodSource("invalidResponses")
	void invalidBodiesRecordFailuresInBothModes(String json, Class<? extends Throwable> error) {
		for (String mode : List.of("call", "completed", "incomplete")) {
			OpenRouterChatModel model = model(!"call".equals(mode), wire(mode, json));
			assertThatThrownBy(() -> invoke(model, !"call".equals(mode))).isInstanceOf(error);
			assertFailure(error);
			this.observations.clear();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "call", "completed", "incomplete" })
	void errorEnvelopeOverridesStatusAndPreservesBoundedTypedDiagnostics(String mode) {
		String json = "{\"status\":\"completed\",\"output\":[],\"error_type\":\"provider_unavailable\","
				+ "\"error\":{\"code\":503,\"message\":\"synthetic\\nfailure " + "x".repeat(1500)
				+ "\",\"metadata\":{\"provider_code\":\"synthetic_code\"}}}";
		boolean streaming = !"call".equals(mode);
		assertThatThrownBy(() -> invoke(model(streaming, wire(mode, json)), streaming))
			.isInstanceOfSatisfying(OpenRouterTransientApiException.class, error -> {
				assertThat(error.getStatusCode().value()).isEqualTo(503);
				assertThat(error.getCategory()).isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
				assertThat(error.getErrorDetails().code()).isEqualTo("503");
				assertThat(error.getErrorDetails().providerCode()).isEqualTo("synthetic_code");
				assertThat(error.getErrorDetails().message()).doesNotContain("\n")
					.hasSizeLessThanOrEqualTo(OpenRouterExceptionMessage.MAX_DIAGNOSTIC_LENGTH + 3);
				assertThat(error.getMessage()).doesNotContain("synthetic");
			});
		assertFailure(OpenRouterTransientApiException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = { "call", "completed", "incomplete" })
	void errorOnlyBodiesRetainNonTransientClassification(String mode) {
		String json = "{\"error\":{\"code\":401,\"message\":\"synthetic failure\"}}";
		boolean streaming = !"call".equals(mode);
		assertThatThrownBy(() -> invoke(model(streaming, wire(mode, json)), streaming)).isInstanceOfSatisfying(
				OpenRouterNonTransientApiException.class,
				error -> assertThat(error.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION));
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "{\"type\":\"response.completed\"}", "{\"type\":\"response.incomplete\",\"response\":null}",
					"{\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\",\"output\":[]}}",
					"{\"type\":\"response.incomplete\",\"response\":{\"status\":\"completed\",\"output\":[]}}",
					"{\"type\":\"response.completed\",\"error\":{\"code\":503}}" })
	void malformedTerminalNeverReleasesBufferedTools(String terminal) {
		String body = "data: {\"type\":\"response.output_item.done\",\"item\":" + TOOL + "}\n\n" + "data: " + terminal
				+ "\n\n";
		assertNoToolExecution(true, body);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void providerErrorNeverExecutesAdvertisedTools(boolean streaming) {
		String json = "{\"status\":\"completed\",\"output\":[" + TOOL + "],\"error\":{\"code\":503}}";
		assertNoToolExecution(streaming, streaming ? wire("completed", json) : json);
	}

	private void assertNoToolExecution(boolean streaming, String body) {
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
		}).isInstanceOfAny(OpenRouterProtocolException.class, OpenRouterTruncatedResponseException.class,
				OpenRouterTransientApiException.class);
		assertThat(executions).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "completed", "incomplete" })
	void missingSnapshotsFailWithoutTools(String status) {
		assertThatThrownBy(() -> invoke(model(true, "data: {\"type\":\"response." + status + "\"}\n\n"), true))
			.isInstanceOf(OpenRouterTruncatedResponseException.class);
		assertFailure(OpenRouterTruncatedResponseException.class);
	}

	static Stream<Arguments> validResponses() {
		return Stream.of(Arguments.of("[]", "STOP"),
				Arguments.of("[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"\"}]}]", "STOP"),
				Arguments.of(
						"[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":\"synthetic refusal\"}]}]",
						"STOP"),
				Arguments.of("[" + TOOL + "]", "TOOL_CALLS"),
				Arguments.of("[{\"type\":\"image_generation_call\",\"status\":\"completed\",\"result\":\"aW1hZ2U=\"}]",
						"STOP"));
	}

	@ParameterizedTest
	@MethodSource("validResponses")
	void emptyTextDoesNotInvalidateCompletedOutput(String output, String finishReason) {
		String json = "{\"status\":\"completed\",\"output\":" + output + ",\"future_field\":true}";
		for (boolean streaming : new boolean[] { false, true }) {
			String body = streaming ? wire("completed", json) : json;
			if (streaming && output.contains("image_generation_call")) {
				body = "data: {\"type\":\"response.output_item.done\",\"item\":"
						+ output.substring(1, output.length() - 1) + "}\n\n" + body;
			}
			var model = model(streaming, body);
			List<ChatResponse> responses = streaming
					? model.stream(new Prompt("synthetic")).collectList().block(Duration.ofSeconds(5))
					: List.of(model.call(new Prompt("synthetic")));
			ChatResponse response = responses.get(responses.size() - 1);
			assertThat(response.getResult().getOutput().getText()).isEmpty();
			assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(finishReason);
			if (output.contains("refusal")) {
				assertThat(response.getResult().getOutput().getMetadata()).containsEntry("openrouter.refusal",
						"synthetic refusal");
			}
			if (output.contains("function_call")) {
				assertThat(response.getResult().getOutput().getToolCalls()).hasSize(1);
			}
			if (output.contains("image_generation_call")) {
				assertThat(responses)
					.anySatisfy(part -> assertThat(part.getResult().getOutput().getMedia()).hasSize(1));
			}
		}
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
