package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ResponsesSnapshotHistoryTests {

	private final ObjectMapper mapper = new ObjectMapper();

	private static final String HISTORY_WIRE = """
			{"status":"completed","output":[
			  {"type":"reasoning","id":"r1","encrypted_content":"synthetic-secret","future":{"a":1}},
			  {"type":"message","role":"assistant","content":[
			    {"type":"output_text","text":"SYNTHETIC_OLD_TEXT"},
			    {"type":"refusal","refusal":"SYNTHETIC_REFUSAL"}]},
			  {"type":"function_call","call_id":"call1","name":"lookup","arguments":"{}"},
			  {"type":"reasoning","id":"r2","encrypted_content":"synthetic-second"}]}
			""";

	private static OpenRouterChatOptions responsesOptions() {
		return OpenRouterChatOptions.builder()
			.model("synthetic")
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.build();
	}

	private static AssistantMessage withText(AssistantMessage original, String text) {
		return AssistantMessage.builder()
			.content(text)
			.properties(original.getMetadata())
			.toolCalls(original.getToolCalls())
			.build();
	}

	private static AssistantMessage withToolCalls(AssistantMessage original, AssistantMessage.ToolCall... calls) {
		return AssistantMessage.builder()
			.content(original.getText())
			.properties(original.getMetadata())
			.toolCalls(List.of(calls))
			.build();
	}

	private static AssistantMessage withRefusal(AssistantMessage original, String refusal) {
		var metadata = new LinkedHashMap<>(original.getMetadata());
		metadata.put(RefusalMetadata.REFUSAL, refusal);
		return AssistantMessage.builder()
			.content(original.getText())
			.properties(metadata)
			.toolCalls(original.getToolCalls())
			.build();
	}

	private static AssistantMessage withoutRefusal(AssistantMessage original) {
		var metadata = new LinkedHashMap<>(original.getMetadata());
		metadata.remove(RefusalMetadata.REFUSAL);
		return AssistantMessage.builder()
			.content(original.getText())
			.properties(metadata)
			.toolCalls(original.getToolCalls())
			.build();
	}

	static Stream<Arguments> staleEdits() {
		Map<String, UnaryOperator<AssistantMessage>> edits = new LinkedHashMap<>();
		edits.put("replaced text", original -> withText(original, "SYNTHETIC_REPLACEMENT_TEXT"));
		edits.put("emptied text", original -> withText(original, ""));
		edits.put("blank text", original -> withText(original, " "));
		edits.put("dropped tool calls", original -> withToolCalls(original));
		edits.put("changed tool call id", original -> withToolCalls(original,
				new AssistantMessage.ToolCall("changed", "function", "lookup", "{}")));
		edits.put("changed tool name", original -> withToolCalls(original,
				new AssistantMessage.ToolCall("call1", "function", "changed", "{}")));
		edits.put("changed tool arguments", original -> withToolCalls(original,
				new AssistantMessage.ToolCall("call1", "function", "lookup", "{\"changed\":true}")));
		edits.put("replaced refusal", original -> withRefusal(original, "SYNTHETIC_REPLACEMENT_REFUSAL"));
		edits.put("emptied refusal", original -> withRefusal(original, ""));
		edits.put("removed refusal", ResponsesSnapshotHistoryTests::withoutRefusal);
		return edits.entrySet()
			.stream()
			.flatMap(edit -> Stream.of(false, true)
				.map(streamed -> Arguments.of(Named
					.of(edit.getKey() + (streamed ? " (streamed history)" : " (blocking history)"), edit.getValue()),
						streamed)));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void preservesUnchangedWireOrder(boolean streamed) {
		ResponsesResult wire = this.mapper.readValue(HISTORY_WIRE, ResponsesResult.class);
		AssistantMessage original = history(wire, streamed);
		var request = new OpenRouterResponsesRequestMapper(this.mapper).map(List.of(original), responsesOptions(),
				false, List.of());
		assertThat(this.mapper.readTree(this.mapper.writeValueAsString(request)).get("input"))
			.isEqualTo(this.mapper.valueToTree(wire.output()));
	}

	@ParameterizedTest
	@MethodSource("staleEdits")
	void rejectsEditedHistoryBeforeDispatch(UnaryOperator<AssistantMessage> edit, boolean streamed) {
		ResponsesResult wire = this.mapper.readValue(HISTORY_WIRE, ResponsesResult.class);
		AssistantMessage edited = edit.apply(history(wire, streamed));
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt(List.of(edited), responsesOptions());

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stale assistant output snapshot")
			.hasMessageNotContaining("SYNTHETIC_OLD_TEXT");
		assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stale assistant output snapshot");
		verifyNoInteractions(api);
	}

	static Stream<Arguments> incompleteSnapshots() {
		Map<String, UnaryOperator<Map<String, Object>>> snapshots = new LinkedHashMap<>();
		snapshots.put("missing snapshot", metadata -> {
			metadata.remove(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS);
			return metadata;
		});
		snapshots.put("invalid snapshot", metadata -> {
			metadata.put(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS, "invalid snapshot");
			return metadata;
		});
		snapshots.put("empty snapshot", metadata -> {
			metadata.put(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS, List.of());
			return metadata;
		});
		return snapshots.entrySet()
			.stream()
			.flatMap(snapshot -> Stream.of(false, true)
				.map(streamed -> Arguments
					.of(Named.of(snapshot.getKey() + (streamed ? " (streamed history)" : " (blocking history)"),
							snapshot.getValue()), streamed)));
	}

	@ParameterizedTest
	@MethodSource("incompleteSnapshots")
	void rejectsReasoningReplayWithoutACompleteSnapshotBeforeDispatch(UnaryOperator<Map<String, Object>> damage,
			boolean streamed) {
		ResponsesResult wire = this.mapper.readValue(HISTORY_WIRE, ResponsesResult.class);
		AssistantMessage original = history(wire, streamed);
		AssistantMessage incomplete = AssistantMessage.builder()
			.content(original.getText())
			.properties(damage.apply(new LinkedHashMap<>(original.getMetadata())))
			.toolCalls(original.getToolCalls())
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt(List.of(incomplete), responsesOptions());

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cannot replay reasoning without an output snapshot");
		assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cannot replay reasoning without an output snapshot");
		verifyNoInteractions(api);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rejectsRemovedGeneratedMediaInSnapshotsBeforeDispatch(boolean streamed) {
		ResponsesResult wire = this.mapper.readValue("""
				{"status":"completed","output":[
				  {"type":"reasoning","id":"r1","encrypted_content":"synthetic-secret"},
				  {"type":"image_generation_call","result":"AQID","output_format":"png"}]}
				""", ResponsesResult.class);
		AssistantMessage original = history(wire, streamed);
		assertThat(this.mapper.valueToTree(original.getMetadata().get(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS))
			.at("/1/result")
			.asString()).isEqualTo("AQID");
		AssistantMessage redacted = AssistantMessage.builder()
			.content(original.getText())
			.properties(original.getMetadata())
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt(List.of(redacted),
				OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build());
		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("assistant media history");
		assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("assistant media history");
		verifyNoInteractions(api);
	}

	@ParameterizedTest
	@ValueSource(strings = { "123", "[]", "\"synthetic\"" })
	void rejectsNonObjectSnapshotItemsBeforeDispatch(String item) {
		AssistantMessage history = AssistantMessage.builder()
			.content("")
			.properties(Map.of(ReasoningMetadata.RESPONSES_ITEMS, List.of(Map.of("type", "reasoning")),
					ReasoningMetadata.RESPONSES_OUTPUT_ITEMS,
					List.of(Map.of("type", "reasoning"), this.mapper.readValue(item, Object.class))))
			.build();
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt(List.of(history),
				OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build());
		assertThatThrownBy(() -> model.call(prompt)).hasMessageContaining("output item must be a JSON object");
		assertThatThrownBy(() -> model.stream(prompt).blockLast())
			.hasMessageContaining("output item must be a JSON object");
		verifyNoInteractions(api);
	}

	private AssistantMessage history(ResponsesResult wire, boolean streamed) {
		return streamed ? streamedHistory(wire) : blockingHistory(wire);
	}

	private AssistantMessage blockingHistory(ResponsesResult wire) {
		return ResponseMappers.responses().map(wire).getResult().getOutput();
	}

	private AssistantMessage streamedHistory(ResponsesResult wire) {
		AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
		Flux<ResponsesStreamEvent> events = Flux.range(0, wire.output().size())
			.map(index -> new ResponsesStreamEvent("response.output_item.done", null, wire.output().get(index), null,
					null, null, null, null, null, null, index, null))
			.concatWithValues(new ResponsesStreamEvent("response.completed", null, null, wire, null));
		new MessageAggregator().aggregate(ResponseMappers.responsesStreaming().map(events), aggregated::set)
			.blockLast();
		return aggregated.get().getResult().getOutput();
	}

}
