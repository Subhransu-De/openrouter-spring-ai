package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

class OpenRouterResponsesToolLifecycleTests {

	private static final String OUTPUT_ITEM_DONE = "response.output_item.done";

	private static final String FIRST_CALL_ID = "synthetic_call_1";

	private final OpenRouterApi api = mock(OpenRouterApi.class);

	private final AtomicInteger executions = new AtomicInteger();

	static Stream<Arguments> nonFinalRounds() {
		return Stream.of(false, true)
			.flatMap(streaming -> Stream.of(Arguments.of(streaming, "incomplete", "incomplete", "{}", false),
					Arguments.of(streaming, "incomplete", "incomplete", "{", false),
					Arguments.of(streaming, "incomplete", "incomplete", "{\"optional\":true}", true),
					Arguments.of(streaming, "completed", "incomplete", "{}", true),
					Arguments.of(streaming, "completed", "in_progress", "{}", false),
					Arguments.of(streaming, "completed", "future_status", "{}", false),
					Arguments.of(streaming, "incomplete", "completed", "{}", true),
					Arguments.of(streaming, "incomplete", null, "{}", false),
					Arguments.of(streaming, "in_progress", "completed", "{}", false)));
	}

	@ParameterizedTest
	@MethodSource("nonFinalRounds")
	void advisorRejectsWholeNonFinalRound(boolean streaming, String responseStatus, String itemStatus, String arguments,
			boolean mixed) {
		ResponsesOutputItem unfinished = call("synthetic_call_2", itemStatus, arguments);
		List<ResponsesOutputItem> items = mixed ? List.of(call(FIRST_CALL_ID, "completed", "{}"), unfinished)
				: List.of(unfinished);
		stub(result(responseStatus, items));

		assertThatThrownBy(() -> invoke(streaming)).isInstanceOf(OpenRouterTruncatedResponseException.class)
			.hasMessageContaining("incomplete reason=max_output_tokens");
		assertThat(this.executions).hasValue(0);
		verifyRequests(streaming, 1);
	}

	static Stream<Arguments> completedRounds() {
		return Stream.of(false, true)
			.flatMap(streaming -> Stream.of(Arguments.of(streaming, "completed"),
					Arguments.of(streaming, (String) null)));
	}

	@ParameterizedTest
	@MethodSource("completedRounds")
	void advisorExecutesEachCompletedOrStatuslessCallExactlyOnce(boolean streaming, String itemStatus) {
		ResponsesOutputItem reasoning = new ResponsesOutputItem("synthetic_reasoning", "reasoning", "completed", null,
				null);
		stub(result("completed", List.of(reasoning, call(FIRST_CALL_ID, itemStatus, "{}"),
				call("synthetic_call_2", itemStatus, "{\"optional\":true}"))));

		assertThat(invoke(streaming)).isEqualTo("done");
		assertThat(this.executions).hasValue(2);
		ArgumentCaptor<ResponsesRequest> requests = ArgumentCaptor.forClass(ResponsesRequest.class);
		if (streaming) {
			verify(this.api, times(2)).responsesStream(requests.capture());
		}
		else {
			verify(this.api, times(2)).responses(requests.capture());
		}
		assertThat((List<?>) requests.getAllValues().get(1).input())
			.anySatisfy(item -> assertThat(item).isEqualTo(reasoning));
		var mapper = JsonMapper.builder().build();
		var input = mapper.readTree(mapper.writeValueAsString(requests.getAllValues().get(1))).get("input");
		assertThat(input.get(1)).isEqualTo(mapper.valueToTree(reasoning));
		assertThat(input.get(2)).isEqualTo(mapper.valueToTree(call(FIRST_CALL_ID, itemStatus, "{}")));
		assertThat(input.get(3))
			.isEqualTo(mapper.valueToTree(call("synthetic_call_2", itemStatus, "{\"optional\":true}")));
		assertThat(input.get(4).get("type").asString()).isEqualTo("function_call_output");
		assertThat(input.get(5).get("type").asString()).isEqualTo("function_call_output");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void streamingValidatesBothDoneItemsAndTerminalSnapshot(boolean invalidDoneItem) {
		ResponsesOutputItem done = call(FIRST_CALL_ID, invalidDoneItem ? "incomplete" : "completed", "{}");
		ResponsesResult terminal = result("completed",
				List.of(call(FIRST_CALL_ID, invalidDoneItem ? "completed" : "incomplete", "{}")));
		when(this.api.responsesStream(any()))
			.thenReturn(Flux.just(event(OUTPUT_ITEM_DONE, done, null), event("response.completed", null, terminal)));

		assertThatThrownBy(() -> invoke(true)).isInstanceOf(OpenRouterTruncatedResponseException.class)
			.hasMessageContaining("item status=incomplete");
		assertThat(this.executions).hasValue(0);
		verifyRequests(true, 1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"malformed\"", "{\"status\":\"incomplete\",\"usage\":\"malformed\"}",
			"{\"status\":\"completed\",\"output\":\"malformed\"}",
			"{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"status\":\"incomplete\"}],\"usage\":\"malformed\"}" })
	void malformedTerminalResponseCannotReleaseBufferedCalls(String response) {
		Flux<ResponsesStreamEvent> terminal = Flux.defer(() -> Flux.just(JsonMapper.builder()
			.build()
			.readValue("{\"type\":\"response.completed\",\"response\":" + response + "}", ResponsesStreamEvent.class)));
		when(this.api.responsesStream(any())).thenReturn(
				Flux.just(event(OUTPUT_ITEM_DONE, call(FIRST_CALL_ID, "completed", "{}"), null)).concatWith(terminal));

		assertThatThrownBy(() -> invoke(true)).isInstanceOf(JacksonException.class);
		assertThat(this.executions).hasValue(0);
		verifyRequests(true, 1);
	}

	@ParameterizedTest
	@ValueSource(
			strings = { "{\"type\":\"response.completed\"}", "{\"type\":\"response.completed\",\"response\":null}" })
	void missingTerminalResponseCannotReleaseBufferedCalls(String json) {
		ResponsesStreamEvent terminal = JsonMapper.builder().build().readValue(json, ResponsesStreamEvent.class);
		when(this.api.responsesStream(any()))
			.thenReturn(Flux.just(event(OUTPUT_ITEM_DONE, call(FIRST_CALL_ID, "completed", "{}"), null), terminal));

		assertThatThrownBy(() -> invoke(true)).isInstanceOf(OpenRouterTruncatedResponseException.class)
			.hasMessageContaining("without a response snapshot");
		assertThat(this.executions).hasValue(0);
		verifyRequests(true, 1);
	}

	@Test
	void streamEndingBeforeTerminalResponseDoesNotExecuteBufferedCalls() {
		when(this.api.responsesStream(any()))
			.thenReturn(Flux.just(event(OUTPUT_ITEM_DONE, call("synthetic_call", "completed", "{}"), null)));

		assertThatThrownBy(() -> invoke(true)).isInstanceOf(OpenRouterTruncatedResponseException.class)
			.hasMessageContaining("before tool round completion");
		assertThat(this.executions).hasValue(0);
		verifyRequests(true, 1);
	}

	private String invoke(boolean streaming) {
		var callback = FunctionToolCallback.builder("synthetic_action", (Map<String, Object> input) -> {
			this.executions.incrementAndGet();
			return "ok";
		}).description("Synthetic counter").inputType(Map.class).build();
		var request = ChatClient.builder(OpenRouterChatModel.builder().openRouterApi(this.api).build())
			.defaultAdvisors(ToolCallingAdvisor.builder().build())
			.build()
			.prompt()
			.user("synthetic prompt")
			.options(OpenRouterChatOptions.builder()
				.model("synthetic")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.toolCallbacks(callback));
		return streaming ? request.stream().content().collectList().map(parts -> String.join("", parts)).block()
				: request.call().content();
	}

	private void stub(ResponsesResult first) {
		ResponsesResult answer = result("completed", List.of(new ResponsesOutputItem("synthetic_message", "message",
				"completed", "assistant", List.of(new ResponsesContent("output_text", "done")))));
		when(this.api.responses(any())).thenReturn(first, answer);
		String terminalType = "incomplete".equals(first.status()) ? "response.incomplete" : "response.completed";
		Flux<ResponsesStreamEvent> firstStream = Flux.fromIterable(first.output())
			.map(item -> event(OUTPUT_ITEM_DONE, item, null))
			.concatWithValues(event(terminalType, null, first));
		when(this.api.responsesStream(any())).thenReturn(firstStream,
				Flux.just(new ResponsesStreamEvent("response.output_text.delta", "done", null, null, null),
						event("response.completed", null, answer)));
	}

	private void verifyRequests(boolean streaming, int count) {
		if (streaming) {
			verify(this.api, times(count)).responsesStream(any());
		}
		else {
			verify(this.api, times(count)).responses(any());
		}
	}

	private static ResponsesOutputItem call(String id, String status, String arguments) {
		return new ResponsesOutputItem(id, "function_call", status, null, null, id, "synthetic_action", arguments,
				null);
	}

	private static ResponsesResult result(String status, List<ResponsesOutputItem> items) {
		return new ResponsesResult("synthetic_response", "response", 123L, "synthetic", status, items, null, null,
				new ResponsesResult.IncompleteDetails("max_output_tokens"));
	}

	private static ResponsesStreamEvent event(String type, ResponsesOutputItem item, ResponsesResult result) {
		return new ResponsesStreamEvent(type, null, item, result, null);
	}

}
