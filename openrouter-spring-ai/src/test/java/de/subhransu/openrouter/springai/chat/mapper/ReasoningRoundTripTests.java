package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import reactor.core.publisher.Flux;

class ReasoningRoundTripTests {

	private static final String REASONING = "openrouter.reasoning";

	private final ObjectMapper mapper = new ObjectMapper();

	private static final String DETAIL = """
			{"type":"reasoning.encrypted","data":"synthetic-ciphertext","id":"r1","index":0,
			 "format":"synthetic-v1","unknown":{"nested":[null,3,true]}}
			""";

	@Test
	void synchronousToolContinuationPreservesOpaqueDetails() {
		ChatCompletionResponse wire = this.mapper.readValue("""
				{"choices":[{"index":0,"finish_reason":"tool_calls","message":{
				  "role":"assistant","content":null,"reasoning":"checking",
				  "reasoning_details":[%s],
				  "tool_calls":[{"id":"call1","type":"function","function":{"name":"lookup","arguments":"{}"}}]
				}}]}
				""".formatted(DETAIL), ChatCompletionResponse.class);
		ChatResponse response = new OpenRouterChatResponseMapper().map(wire);
		AssistantMessage assistant = response.getResult().getOutput();
		JsonNode replay = chatRequest(assistant);
		assertThat(replay.at("/messages/0/reasoning_details/0")).isEqualTo(this.mapper.readTree(DETAIL));
		assertThat(assistant.getMetadata()).containsEntry(REASONING, "checking");
		assertThat(response.getResult().getMetadata().get(REASONING).toString()).isEqualTo("checking");
		assertThat(replay.at("/messages/0/reasoning").asString()).isEqualTo("checking");
		assertThat(replay.at("/messages/1/tool_call_id").asString()).isEqualTo("call1");
	}

	@Test
	void streamedReasoningSurvivesToolAggregationAndSpringAggregation() {
		ChatCompletionChunk first = chunk("{\"reasoning\":\"check\",\"reasoning_details\":[" + DETAIL + "]}", null);
		ChatCompletionChunk tool = chunk(
				"""
						{"reasoning":"ing","reasoning_details":[{"type":"reasoning.encrypted","data":"during"}],"tool_calls":[{"index":0,"id":"call1","type":"function",
						"function":{"name":"lookup","arguments":"{"}}]}
						""",
				null);
		ChatCompletionChunk last = chunk("""
				{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}
				""", "tool_calls");
		Flux<ChatResponse> stream = new OpenRouterStreamingResponseMapper()
			.map(new OpenRouterStreamingToolCallAggregator().aggregate(Flux.just(first, tool, last)));
		for (int i = 0; i < 2; i++) {
			AtomicReference<ChatResponse> result = new AtomicReference<>();
			new MessageAggregator().aggregate(stream, result::set).blockLast();
			AssistantMessage assistant = result.get().getResult().getOutput();
			assertThat(assistant.getMetadata()).containsEntry(REASONING, "checking");
			assertThat(chatRequest(assistant).at("/messages/0/reasoning_details/0"))
				.isEqualTo(this.mapper.readTree(DETAIL));
			assertThat(assistant.getToolCalls().get(0).arguments()).isEqualTo("{}");
			assertThat(chatRequest(assistant).at("/messages/0/reasoning_details/1/data").asString())
				.isEqualTo("during");
		}
	}

	@Test
	void responsesReasoningItemsRoundTripWithUnknownFields() {
		String item = """
				{"id":"r1","type":"reasoning","encrypted_content":"synthetic-ciphertext",
				 "summary":[{"type":"summary_text","text":"checking"}],"future":{"flag":true},
				 "status":null,"content":[{"type":"reasoning_text","text":"checking","signature":"synthetic"}]}
				""";
		ResponsesResult wire = this.mapper.readValue("{\"status\":\"completed\",\"output\":[" + item + "]}",
				ResponsesResult.class);
		AssistantMessage sync = new OpenRouterResponsesResponseMapper().map(wire).getResult().getOutput();
		assertThat(sync.getMetadata()).containsEntry(REASONING, "checking");
		assertThat(responsesRequest(sync).at("/input/0")).isEqualTo(this.mapper.readTree(item));
		ResponsesStreamEvent done = this.mapper
			.readValue("{\"type\":\"response.output_item.done\",\"item\":" + item + "}", ResponsesStreamEvent.class);
		AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
		new MessageAggregator()
			.aggregate(
					new OpenRouterResponsesStreamingResponseMapper()
						.map(Flux.just(done, new ResponsesStreamEvent("response.completed", null, null, wire, null))),
					aggregated::set)
			.blockLast();
		assertThat(responsesRequest(aggregated.get().getResult().getOutput()).at("/input/0"))
			.isEqualTo(this.mapper.readTree(item));
		assertThat(responsesRequest(aggregated.get().getResult().getOutput()).at("/input").size()).isEqualTo(2);
	}

	@Test
	void choicesKeepIndependentOrderedSnapshots() {
		ChatCompletionChunk first = this.mapper.readValue(
				"""
						{"choices":[
						{"index":0,"delta":{"reasoning":"a","reasoning_details":[{"type":"reasoning.encrypted","data":"first"}]}},
						{"index":1,"delta":{"reasoning":"b"}}]}
						""",
				ChatCompletionChunk.class);
		ChatCompletionChunk last = this.mapper.readValue(
				"""
						{"choices":[
						{"index":1,"delta":{"reasoning":"2"},"finish_reason":"stop"},
						{"index":0,"delta":{"reasoning":"1","reasoning_details":[{"type":"reasoning.encrypted","data":"second"}]},"finish_reason":"stop"}]}
						""",
				ChatCompletionChunk.class);
		List<ChatResponse> responses = new OpenRouterStreamingResponseMapper().map(Flux.just(first, last))
			.collectList()
			.block();
		assertThat(responses.get(0).getResults().get(0).getOutput().getMetadata()).containsEntry(REASONING, "a");
		assertThat(responses.get(1).getResults().get(0).getOutput().getMetadata()).containsEntry(REASONING, "b2")
			.doesNotContainKey("openrouter.reasoning_details");
		AssistantMessage choice = responses.get(1).getResults().get(1).getOutput();
		assertThat(choice.getMetadata()).containsEntry(REASONING, "a1");
		assertThat(chatRequest(choice).at("/messages/0/reasoning_details")).isEqualTo(this.mapper.readTree("""
				[{"type":"reasoning.encrypted","data":"first"},{"type":"reasoning.encrypted","data":"second"}]
				"""));
	}

	@Test
	void responsesReasoningTextAggregatesWithoutRepeatingTerminalItems() {
		ResponsesStreamEvent first = new ResponsesStreamEvent("response.reasoning_summary_text.delta", "check", null,
				null, null);
		ResponsesStreamEvent second = new ResponsesStreamEvent("response.reasoning_summary_text.delta", "ing", null,
				null, null);
		ResponsesStreamEvent done = this.mapper.readValue("""
				{"type":"response.output_item.done","item":{"type":"reasoning","id":"r1",
				"summary":[{"type":"summary_text","text":"checking"}]}}
				""", ResponsesStreamEvent.class);
		Flux<ChatResponse> stream = new OpenRouterResponsesStreamingResponseMapper()
			.map(Flux.just(first, second, done));
		for (int i = 0; i < 2; i++) {
			AtomicReference<ChatResponse> result = new AtomicReference<>();
			new MessageAggregator().aggregate(stream, result::set).blockLast();
			assertThat(result.get().getResult().getOutput().getMetadata()).containsEntry(REASONING, "checking");
			assertThat(responsesRequest(result.get().getResult().getOutput()).at("/input/0/id").asString())
				.isEqualTo("r1");
		}
	}

	@Test
	void responsesReplayPreservesInterleavedOutputOrder() {
		String output = """
				[{"type":"reasoning","id":"r1","encrypted_content":"synthetic-first"},
				 {"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"checking","annotations":[]}]},
				 {"type":"function_call","id":"fc1","call_id":"call1","name":"lookup","arguments":"{}"},
				 {"type":"reasoning","id":"r2","encrypted_content":"synthetic-second"}]
				""";
		ResponsesResult wire = this.mapper.readValue("{\"status\":\"completed\",\"output\":" + output + "}",
				ResponsesResult.class);
		AssistantMessage sync = new OpenRouterResponsesResponseMapper().map(wire).getResult().getOutput();
		assertOrderedReplay(sync, output);
		Flux<ResponsesStreamEvent> events = Flux.fromIterable(wire.output())
			.map(item -> new ResponsesStreamEvent("response.output_item.done", null, item, null, null))
			.startWith(new ResponsesStreamEvent("response.output_text.delta", "checking", null, null, null));
		for (boolean terminal : List.of(false, true)) {
			Flux<ResponsesStreamEvent> stream = terminal
					? events.concatWithValues(new ResponsesStreamEvent("response.completed", null, null, wire, null))
					: events.concatWithValues(new ResponsesStreamEvent("response.completed", null, null,
							new ResponsesResult(null, null, null, null, "completed", null, null, null), null));
			AtomicReference<ChatResponse> result = new AtomicReference<>();
			new MessageAggregator().aggregate(new OpenRouterResponsesStreamingResponseMapper().map(stream), result::set)
				.blockLast();
			assertOrderedReplay(result.get().getResult().getOutput(), output);
		}
	}

	@Test
	void streamedDetailsAssembleTextAndSummaryButKeepEncryptedBlobsOpaque() {
		ChatCompletionChunk first = chunk(
				"""
						{"reasoning_details":[{"type":"reasoning.text","index":0,"text":"check","signature":null,"future":{"flag":true}}],
						 "tool_calls":[{"id":"call1","index":0,"type":"function","function":{"name":"lookup","arguments":"{}"}}]}
						""",
				null);
		ChatCompletionChunk second = chunk("""
				{"reasoning_details":[{"type":"reasoning.text","index":0,"text":"ing"},
				{"type":"reasoning.text","index":0,"signature":"synthetic-signature"},
				{"type":"reasoning.summary","index":0,"summary":"sum"}]}
				""", null);
		ChatCompletionChunk last = chunk("""
				{"reasoning_details":[{"type":"reasoning.summary","index":0,"summary":"mary"},
				{"type":"reasoning.encrypted","index":0,"id":"a","data":"synthetic-a"},
				{"type":"reasoning.encrypted","index":0,"id":"b","data":"synthetic-b"}]}
				""", "tool_calls");
		JsonNode expected = this.mapper.readTree(
				"""
						[{"type":"reasoning.text","index":0,"text":"checking","signature":"synthetic-signature","future":{"flag":true}},
						 {"type":"reasoning.summary","index":0,"summary":"summary"},
						 {"type":"reasoning.encrypted","index":0,"id":"a","data":"synthetic-a"},
						 {"type":"reasoning.encrypted","index":0,"id":"b","data":"synthetic-b"}]
						""");
		for (boolean bufferTools : List.of(false, true)) {
			ChatCompletionChunk initial = bufferTools ? first
					: chunk("{\"reasoning_details\":"
							+ this.mapper.writeValueAsString(first.choices().get(0).delta().reasoningDetails()) + "}",
							null);
			Flux<ChatCompletionChunk> chunks = Flux.just(initial, second, last);
			if (bufferTools) {
				chunks = new OpenRouterStreamingToolCallAggregator().aggregate(chunks);
			}
			AtomicReference<ChatResponse> result = new AtomicReference<>();
			new MessageAggregator().aggregate(new OpenRouterStreamingResponseMapper().map(chunks), result::set)
				.blockLast();
			assertThat(chatRequest(result.get().getResult().getOutput()).at("/messages/0/reasoning_details"))
				.isEqualTo(expected);
		}
		assertThat(first.choices().get(0).delta().reasoningDetails().get(0).get("text").asString()).isEqualTo("check");
	}

	private void assertOrderedReplay(AssistantMessage assistant, String output) {
		JsonNode replay = responsesRequest(assistant).at("/input");
		JsonNode expected = this.mapper.readTree(output);
		assertThat(replay.size()).isEqualTo(expected.size() + 1);
		for (int i = 0; i < expected.size(); i++) {
			assertThat(replay.get(i)).isEqualTo(expected.get(i));
		}
		assertThat(replay.get(expected.size()).get("type").asString()).isEqualTo("function_call_output");
	}

	private ChatCompletionChunk chunk(String delta, String finish) {
		return this.mapper.readValue("{\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ (finish == null ? "null" : "\"" + finish + "\"") + "}]}", ChatCompletionChunk.class);
	}

	private JsonNode chatRequest(AssistantMessage assistant) {
		return this.mapper
			.valueToTree(new OpenRouterChatRequestMapper(this.mapper).map(List.of(assistant, toolResult()),
					OpenRouterChatOptions.builder().model("synthetic-model").build(), false, List.of()));
	}

	private JsonNode responsesRequest(AssistantMessage assistant) {
		return this.mapper
			.valueToTree(new OpenRouterResponsesRequestMapper(this.mapper).map(List.of(assistant, toolResult()),
					OpenRouterChatOptions.builder().model("synthetic-model").build(), false, List.of()));
	}

	private ToolResponseMessage toolResult() {
		return ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call1", "lookup", "synthetic-result")))
			.build();
	}

}
