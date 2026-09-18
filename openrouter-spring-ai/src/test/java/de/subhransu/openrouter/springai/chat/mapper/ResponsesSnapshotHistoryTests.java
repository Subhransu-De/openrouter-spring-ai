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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class ResponsesSnapshotHistoryTests {

	private final ObjectMapper mapper = new ObjectMapper();

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rejectsEditedHistoryBeforeDispatchAndPreservesUnchangedWireOrder(boolean streamed) {
		ResponsesResult wire = this.mapper.readValue("""
				{"status":"completed","output":[
				  {"type":"reasoning","id":"r1","encrypted_content":"synthetic-secret","future":{"a":1}},
				  {"type":"message","role":"assistant","content":[
				    {"type":"output_text","text":"SYNTHETIC_OLD_TEXT"},
				    {"type":"refusal","refusal":"SYNTHETIC_REFUSAL"}]},
				  {"type":"function_call","call_id":"call1","name":"lookup","arguments":"{}"},
				  {"type":"reasoning","id":"r2","encrypted_content":"synthetic-second"}]}
				""", ResponsesResult.class);
		AssistantMessage original;
		if (streamed) {
			AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
			Flux<ResponsesStreamEvent> events = Flux.range(0, wire.output().size())
				.map(index -> new ResponsesStreamEvent("response.output_item.done", null, wire.output().get(index),
						null, null, null, null, null, null, null, index, null))
				.startWith(
						new ResponsesStreamEvent("response.output_text.delta", "SYNTHETIC_OLD_TEXT", null, null, null))
				.concatWithValues(new ResponsesStreamEvent("response.completed", null, null, wire, null));
			new MessageAggregator()
				.aggregate(new OpenRouterResponsesStreamingResponseMapper().map(events), aggregated::set)
				.blockLast();
			original = aggregated.get().getResult().getOutput();
		}
		else {
			original = new OpenRouterResponsesResponseMapper().map(wire).getResult().getOutput();
		}
		OpenRouterChatOptions options = OpenRouterChatOptions.builder()
			.model("synthetic")
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.build();
		var request = new OpenRouterResponsesRequestMapper(this.mapper).map(List.of(original), options, false,
				List.of());
		assertThat(this.mapper.readTree(this.mapper.writeValueAsString(request)).get("input"))
			.isEqualTo(this.mapper.valueToTree(wire.output()));

		List<AssistantMessage> edits = new ArrayList<>();
		for (String text : List.of("SYNTHETIC_REPLACEMENT_TEXT", "", " ")) {
			edits.add(AssistantMessage.builder()
				.content(text)
				.properties(original.getMetadata())
				.toolCalls(original.getToolCalls())
				.build());
		}
		for (List<AssistantMessage.ToolCall> calls : List.of(List.<AssistantMessage.ToolCall>of(),
				List.of(new AssistantMessage.ToolCall("changed", "function", "lookup", "{}")),
				List.of(new AssistantMessage.ToolCall("call1", "function", "changed", "{}")),
				List.of(new AssistantMessage.ToolCall("call1", "function", "lookup", "{\"changed\":true}")))) {
			edits.add(AssistantMessage.builder()
				.content(original.getText())
				.properties(original.getMetadata())
				.toolCalls(calls)
				.build());
		}
		for (String refusal : List.of("SYNTHETIC_REPLACEMENT_REFUSAL", "")) {
			var metadata = new LinkedHashMap<>(original.getMetadata());
			if (refusal.isEmpty()) {
				metadata.remove(RefusalMetadata.REFUSAL);
			}
			else {
				metadata.put(RefusalMetadata.REFUSAL, refusal);
			}
			edits.add(AssistantMessage.builder()
				.content(original.getText())
				.properties(metadata)
				.toolCalls(original.getToolCalls())
				.build());
		}
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		for (AssistantMessage edited : edits) {
			Prompt prompt = new Prompt(List.of(edited), options);
			assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("stale assistant output snapshot")
				.hasMessageNotContaining("SYNTHETIC_OLD_TEXT");
			assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("stale assistant output snapshot");
		}
		for (String snapshot : List.of("missing", "invalid", "empty")) {
			var metadata = new LinkedHashMap<>(original.getMetadata());
			if ("missing".equals(snapshot)) {
				metadata.remove(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS);
			}
			else {
				metadata.put(ReasoningMetadata.RESPONSES_OUTPUT_ITEMS,
						"empty".equals(snapshot) ? List.of() : "invalid snapshot");
			}
			AssistantMessage incomplete = AssistantMessage.builder()
				.content(original.getText())
				.properties(metadata)
				.toolCalls(original.getToolCalls())
				.build();
			Prompt prompt = new Prompt(List.of(incomplete), options);
			assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cannot replay reasoning without an output snapshot");
			assertThatThrownBy(() -> model.stream(prompt).blockLast()).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cannot replay reasoning without an output snapshot");
		}
		verifyNoInteractions(api);
	}

}
