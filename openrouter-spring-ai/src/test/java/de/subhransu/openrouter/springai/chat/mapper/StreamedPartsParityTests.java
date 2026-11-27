package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The aggregated stream rebuilds what a blocking call returns, and replays the same next
 * request, for the stream shapes where index assignment or terminal reconciliation is
 * easy to get wrong.
 */
@Tag("api")
@Tag("api.reasoning")
class StreamedPartsParityTests {

	private static final String CREATED = "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}";

	private static final String GENERATION = "\"id\":\"gen-1\",";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void chatCompletionsMixedChunkKeepsReasoningAndTextBeforeTheToolCall() {
		String id = GENERATION;
		List<ChatResponse> responses = chatStream(chunk(id,
				"{\"role\":\"assistant\",\"reasoning\":\"Think.\",\"content\":\"Checking.\",\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\"}}]}",
				null),
				chunk(id, "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Oslo\\\"}\"}}]}", null),
				chunk(id, "{}", "tool_calls"));

		AssistantMessage blocking = chatCall(
				"""
						{"role":"assistant","reasoning":"Think.","content":"Checking.",
						 "tool_calls":[{"id":"call1","type":"function","function":{"name":"lookup","arguments":"{\\"city\\":\\"Oslo\\"}"}}]}
						""");
		AssistantMessage aggregated = aggregate(responses);
		assertThat(aggregated.getParts()).isEqualTo(blocking.getParts());
		assertThat(chatRequest(aggregated)).isEqualTo(chatRequest(blocking));
	}

	@Test
	void chatCompletionsInterleavedCallsKeepTheirOrder() {
		String id = GENERATION;
		List<ChatResponse> responses = chatStream(chunk(id, "{\"role\":\"assistant\",\"content\":\"Both.\"}", null),
				chunk(id,
						"{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\"}}]}",
						null),
				chunk(id,
						"{\"tool_calls\":[{\"index\":1,\"id\":\"call2\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\"}}]}",
						null),
				chunk(id, "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Oslo\\\"}\"}}]}", null),
				chunk(id, "{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"\\\"Rome\\\"}\"}}]}", null),
				chunk(id, "{}", "tool_calls"));

		AssistantMessage blocking = chatCall("""
				{"role":"assistant","content":"Both.","tool_calls":[
				 {"id":"call1","type":"function","function":{"name":"lookup","arguments":"{\\"city\\":\\"Oslo\\"}"}},
				 {"id":"call2","type":"function","function":{"name":"lookup","arguments":"{\\"city\\":\\"Rome\\"}"}}]}
				""");
		AssistantMessage aggregated = aggregate(responses);
		assertThat(aggregated.getParts()).isEqualTo(blocking.getParts());
		assertThat(toolCallDeltas(responses)).extracting(part -> ((ToolCallPart) part).toolCall().id())
			.containsExactly("call1", "call2", "call1", "call2");
	}

	@Test
	void chatCompletionsPublishesTheArgumentFragmentOfTheFinishChunk() {
		String id = GENERATION;
		List<ChatResponse> responses = chatStream(chunk(id,
				"{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\"}}]}",
				null),
				chunk(id, "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Oslo\\\"}\"}}]}",
						"tool_calls"));

		assertThat(toolCallDeltas(responses)).extracting(part -> ((ToolCallPart) part).toolCall().arguments())
			.containsExactly("{\"city\":", "\"Oslo\"}");
		ToolCallPart complete = (ToolCallPart) responses.get(responses.size() - 1)
			.getResult()
			.getOutput()
			.getParts()
			.stream()
			.filter(ToolCallPart.class::isInstance)
			.findFirst()
			.orElseThrow();
		assertThat(StreamingParts.partIndex(complete))
			.isEqualTo(StreamingParts.partIndex(toolCallDeltas(responses).get(0)));
	}

	@Test
	void chatCompletionsEncryptedReasoningAfterTextStaysFirst() {
		String id = GENERATION;
		List<ChatResponse> responses = chatStream(chunk(id, "{\"role\":\"assistant\",\"content\":\"Checking.\"}", null),
				chunk(id,
						"{\"reasoning_details\":[{\"type\":\"reasoning.encrypted\",\"data\":\"synthetic-ciphertext\",\"index\":0}],\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]}",
						null),
				chunk(id, "{}", "tool_calls"));

		AssistantMessage blocking = chatCall("""
				{"role":"assistant","content":"Checking.",
				 "reasoning_details":[{"type":"reasoning.encrypted","data":"synthetic-ciphertext","index":0}],
				 "tool_calls":[{"id":"call1","type":"function","function":{"name":"lookup","arguments":"{}"}}]}
				""");
		AssistantMessage aggregated = aggregate(responses);
		assertThat(aggregated.getParts()).extracting(MessagePart::getClass)
			.containsExactly(ReasoningPart.class, TextPart.class, ToolCallPart.class);
		assertThat(chatRequest(aggregated)).isEqualTo(chatRequest(blocking));
	}

	@Test
	void responsesTerminalSnapshotRefreshesReasoningAndTextPayloads() {
		String output = """
				[{"type":"reasoning","id":"rs1","encrypted_content":"synthetic-encrypted","summary":[{"type":"summary_text","text":"Plan more."}]},
				 {"type":"message","id":"msg1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"hello world","annotations":[]}]}]
				""";
		List<ChatResponse> responses = responsesStream(event(CREATED),
				event("{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"delta\":\"Plan\"}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"rs1\",\"summary\":[]}}"),
				event("{\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"delta\":\"hello\"}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"message\",\"id\":\"msg1\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\",\"annotations\":[]}]}}"),
				completed("resp_1", output));

		assertThat(streamedText(responses)).isEqualTo("hello world");
		assertThat(streamedSummary(responses)).isEqualTo("Plan more.");
		AssistantMessage aggregated = aggregate(responses);
		AssistantMessage blocking = responsesCall("resp_1", output);
		assertThat(aggregated.getParts()).isEqualTo(blocking.getParts());
		assertReplays(aggregated, output);
	}

	@Test
	void responsesUnindexedItemIsCorrelatedOrDeferredToTheTerminalSnapshot() {
		String output = """
				[{"type":"reasoning","id":"rs1","encrypted_content":"synthetic-encrypted","summary":[{"type":"summary_text","text":"Plan."}]},
				 {"type":"web_search_call","id":"ws1","status":"completed","action":{"query":"oslo"}},
				 {"type":"message","id":"msg1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Checking.","annotations":[]}]}]
				""";
		JsonNode items = this.mapper.readTree(output);
		List<ChatResponse> responses = responsesStream(event(CREATED),
				event("{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"delta\":\"Plan.\"}"),
				itemDone(0, items.get(0)),
				event("{\"type\":\"response.output_item.done\",\"item\":" + items.get(1) + "}"),
				event("{\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"type\":\"message\",\"id\":\"msg1\",\"role\":\"assistant\",\"content\":[]}}"),
				event("{\"type\":\"response.output_text.delta\",\"output_index\":2,\"content_index\":0,\"delta\":\"Checking.\"}"),
				event("{\"type\":\"response.output_item.done\",\"item\":" + items.get(2) + "}"),
				completed("resp_1", output));

		AssistantMessage aggregated = aggregate(responses);
		assertThat(aggregated.getParts()).isEqualTo(responsesCall("resp_1", output).getParts());
		assertReplays(aggregated, output);
	}

	// The id arrives only with the terminal event, or never, as streams accepted by
	// 0.3.0.
	@ParameterizedTest
	@ValueSource(strings = { "resp_late", "" })
	void responsesStreamWithoutCreatedEventKeepsOneGroup(String terminalId) {
		String output = """
				[{"type":"reasoning","id":"rs1","encrypted_content":"synthetic-encrypted","summary":[{"type":"summary_text","text":"Plan."}]},
				 {"type":"message","id":"msg1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Checking.","annotations":[]}]},
				 {"type":"function_call","id":"fc1","call_id":"call1","name":"lookup","arguments":"{}","status":"completed"}]
				""";
		JsonNode items = this.mapper.readTree(output);
		List<ChatResponse> responses = responsesStream(
				event("{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"delta\":\"Plan.\"}"),
				itemDone(0, items.get(0)),
				event("{\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"delta\":\"Checking.\"}"),
				itemDone(1, items.get(1)), itemDone(2, items.get(2)), completed(terminalId, output));

		String grouping = responses.get(0).getMetadata().getId();
		assertThat(grouping).isNotBlank();
		assertThat(responses).allSatisfy(response -> assertThat(response.getMetadata().getId()).isEqualTo(grouping));
		AssistantMessage aggregated = aggregate(responses);
		assertThat(aggregated.getParts()).isEqualTo(responsesCall(terminalId, output).getParts());
		assertReplays(aggregated, output);
	}

	@Test
	void responsesSparseRefusalReplaysTheStreamedExplanation() {
		String sparse = """
				[{"type":"message","id":"msg1","role":"assistant","status":"completed","content":[{"type":"refusal"}]}]
				""";
		JsonNode items = this.mapper.readTree(sparse);
		List<ChatResponse> responses = responsesStream(event(CREATED), event(
				"{\"type\":\"response.refusal.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Not allowed.\"}"),
				itemDone(0, items.get(0)), completed("resp_1", sparse));

		AssistantMessage aggregated = aggregate(responses);
		JsonNode replay = responsesRequest(aggregated).at("/input/0");
		assertThat(replay.at("/content/0/type").asString()).isEqualTo("refusal");
		assertThat(replay.at("/content/0/refusal").asString()).isEqualTo("Not allowed.");
	}

	@Test
	void responsesStreamedImageIsAggregatedAndRejectedOnReplayAsInACall() {
		String output = """
				[{"type":"reasoning","id":"rs1","encrypted_content":"synthetic-encrypted","summary":[]},
				 {"type":"image_generation_call","id":"ig1","status":"completed","result":"AQID","output_format":"png"}]
				""";
		JsonNode items = this.mapper.readTree(output);
		List<ChatResponse> responses = responsesStream(event(CREATED), itemDone(0, items.get(0)),
				itemDone(1, items.get(1)), completed("resp_1", output));

		AssistantMessage aggregated = aggregate(responses);
		AssistantMessage blocking = responsesCall("resp_1", output);
		assertThat(aggregated.getParts()).isEqualTo(blocking.getParts());
		assertThat(aggregated.getMedia()).hasSize(1);
		assertThatIllegalArgumentException().isThrownBy(() -> responsesRequest(blocking))
			.withMessageContaining("assistant media history");
		assertThatIllegalArgumentException().isThrownBy(() -> responsesRequest(aggregated))
			.withMessageContaining("assistant media history");
	}

	@Test
	void whitespaceTextPartsBetweenMediaAreSentInBothModes() {
		Media image = Media.builder()
			.mimeType(MimeTypeUtils.IMAGE_PNG)
			.data(URI.create("https://example.com/a.png"))
			.build();
		UserMessage user = UserMessage.builder()
			.parts(List.of(TextPart.of("Before."), TextPart.of("\n"), MediaPart.of(image), TextPart.of(" "),
					TextPart.of("After.")))
			.build();
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().model("synthetic-model").build();

		JsonNode chat = this.mapper
			.valueToTree(new OpenRouterChatRequestMapper(this.mapper).map(List.of(user), options, false, List.of()));
		assertThat(chat.at("/messages/0/content")).extracting(node -> node.get("type").asString())
			.containsExactly("text", "text", "image_url", "text", "text");
		assertThat(chat.at("/messages/0/content/1/text").asString()).isEqualTo("\n");
		JsonNode responses = this.mapper.valueToTree(
				new OpenRouterResponsesRequestMapper(this.mapper).map(List.of(user), options, false, List.of()));
		assertThat(responses.at("/input/0/content")).extracting(node -> node.get("type").asString())
			.containsExactly("input_text", "input_text", "input_image", "input_text", "input_text");
		assertThat(responses.at("/input/0/content/3/text").asString()).isEqualTo(" ");
	}

	private void assertReplays(AssistantMessage message, String output) {
		JsonNode input = responsesRequest(message).get("input");
		JsonNode expected = this.mapper.readTree(output);
		for (int index = 0; index < expected.size(); index++) {
			assertThat(input.get(index)).isEqualTo(expected.get(index));
		}
	}

	private List<ChatResponse> chatStream(ChatCompletionChunk... chunks) {
		return new OpenRouterStreamingResponseMapper()
			.map(Flux.just(chunks), new OpenRouterStreamingToolCallAggregator(), null)
			.collectList()
			.block();
	}

	private List<ChatResponse> responsesStream(ResponsesStreamEvent... events) {
		return new OpenRouterResponsesStreamingResponseMapper().map(Flux.just(events)).collectList().block();
	}

	private static List<MessagePart> toolCallDeltas(List<ChatResponse> responses) {
		return responses.stream()
			.flatMap(response -> response.getResult().getOutput().getParts().stream())
			.filter(part -> part instanceof ToolCallPart && StreamingParts.isPartial(part))
			.toList();
	}

	private static String streamedText(List<ChatResponse> responses) {
		StringBuilder text = new StringBuilder();
		responses.forEach(response -> text.append(response.getResult().getOutput().getText()));
		return text.toString();
	}

	private static String streamedSummary(List<ChatResponse> responses) {
		List<String> summary = new ArrayList<>();
		responses.forEach(response -> response.getResult()
			.getOutput()
			.getReasoning()
			.stream()
			.map(ReasoningPart::summary)
			.filter(value -> value != null)
			.forEach(summary::add));
		return String.join("", summary);
	}

	private AssistantMessage chatCall(String message) {
		return new OpenRouterChatResponseMapper().map(this.mapper
			.readValue("{\"id\":\"gen-1\",\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":"
					+ message + "}]}", ChatCompletionResponse.class))
			.getResult()
			.getOutput();
	}

	private AssistantMessage responsesCall(String id, String output) {
		return new OpenRouterResponsesResponseMapper()
			.map(this.mapper.readValue("{\"id\":\"" + id + "\",\"status\":\"completed\",\"output\":" + output + "}",
					ResponsesResult.class))
			.getResult()
			.getOutput();
	}

	private ResponsesStreamEvent completed(String id, String output) {
		return new ResponsesStreamEvent("response.completed", null, null,
				this.mapper.readValue("{\"id\":\"" + id + "\",\"status\":\"completed\",\"output\":" + output + "}",
						ResponsesResult.class),
				null);
	}

	private ChatCompletionChunk chunk(String id, String delta, String finish) {
		return this.mapper.readValue("{" + id + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ (finish == null ? "null" : "\"" + finish + "\"") + "}]}", ChatCompletionChunk.class);
	}

	private ResponsesStreamEvent itemDone(int index, JsonNode item) {
		return event("{\"type\":\"response.output_item.done\",\"output_index\":" + index + ",\"item\":" + item + "}");
	}

	private ResponsesStreamEvent event(String json) {
		return this.mapper.readValue(json, ResponsesStreamEvent.class);
	}

	private static AssistantMessage aggregate(List<ChatResponse> responses) {
		AtomicReference<ChatResponse> result = new AtomicReference<>();
		new MessageAggregator().aggregate(Flux.fromIterable(responses), result::set).blockLast();
		return result.get().getResult().getOutput();
	}

	private JsonNode chatRequest(AssistantMessage assistant) {
		return this.mapper.valueToTree(new OpenRouterChatRequestMapper(this.mapper).map(conversation(assistant),
				OpenRouterChatOptions.builder().model("synthetic-model").build(), false, List.of()));
	}

	private JsonNode responsesRequest(AssistantMessage assistant) {
		return this.mapper.valueToTree(new OpenRouterResponsesRequestMapper(this.mapper).map(conversation(assistant),
				OpenRouterChatOptions.builder().model("synthetic-model").build(), false, List.of()));
	}

	private static List<Message> conversation(AssistantMessage assistant) {
		List<Message> messages = new ArrayList<>();
		messages.add(assistant);
		if (!assistant.getToolCalls().isEmpty()) {
			messages.add(ToolResponseMessage.builder()
				.responses(List.of(new ToolResponseMessage.ToolResponse("call1", "lookup", "sunny")))
				.build());
		}
		return messages;
	}

}
