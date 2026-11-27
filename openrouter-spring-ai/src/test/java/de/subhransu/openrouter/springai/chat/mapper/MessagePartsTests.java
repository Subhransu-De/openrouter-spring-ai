package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterMetadata;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.MediaPart;
import org.springframework.ai.chat.messages.part.MessagePart;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.StreamingParts;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.springframework.ai.chat.messages.part.UnknownPart;
import org.springframework.ai.chat.metadata.FinishReasonCategory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.content.Media;
import org.springframework.ai.session.SessionEvent;
import org.springframework.ai.session.support.SessionEventCodec;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("api")
@Tag("api.reasoning")
class MessagePartsTests {

	private static final String DETAILS = """
			[{"type":"reasoning.text","text":"Think.","signature":"synthetic-signature","format":"anthropic-claude-v1","index":0},
			 {"type":"reasoning.encrypted","data":"synthetic-ciphertext","id":"call1","format":"google-gemini-v1","index":1}]
			""";

	private static final String TOOL_CALL = """
			{"id":"call1","type":"function","function":{"name":"lookup","arguments":"{\\"city\\":\\"Oslo\\"}"}}
			""";

	private static final String RESPONSES_OUTPUT = """
			[{"type":"reasoning","id":"rs1","encrypted_content":"synthetic-encrypted","summary":[{"type":"summary_text","text":"Plan."}],
			  "content":[{"type":"reasoning_text","text":"Think."}]},
			 {"type":"web_search_call","id":"ws1","status":"completed","action":{"query":"oslo"}},
			 {"type":"message","id":"msg1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Checking.","annotations":[]}]},
			 {"type":"function_call","id":"fc1","call_id":"call1","name":"lookup","arguments":"{\\"city\\":\\"Oslo\\"}","status":"completed"}]
			""";

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void chatCompletionsReplyIsOrderedPartsWithReasoningDetailsAsPayload() {
		AssistantMessage message = chatCall().getResult().getOutput();

		assertThat(message.getParts()).hasSize(3);
		ReasoningPart reasoning = (ReasoningPart) message.getParts().get(0);
		assertThat(reasoning.text()).isEqualTo("Think.");
		assertThat(reasoning.payload().provider()).isEqualTo(OpenRouterMetadata.CHAT_COMPLETIONS_PROVIDER);
		assertThat(reasoning.payload().kind()).isEqualTo(OpenRouterMetadata.REASONING_DETAILS_KIND);
		assertThat(this.mapper.readTree(reasoning.payload().data())).isEqualTo(this.mapper.readTree(DETAILS));
		assertThat(message.getParts().get(1)).isEqualTo(TextPart.of("Checking."));
		assertThat(((ToolCallPart) message.getParts().get(2)).toolCall().name()).isEqualTo("lookup");
		assertThat(message.getReasoning()).containsExactly(reasoning);
		assertThat(message.getText()).isEqualTo("Checking.");
	}

	@Test
	void chatCompletionsReplaysReasoningFromParts() {
		JsonNode replay = chatRequest(chatCall().getResult().getOutput());

		assertThat(replay.at("/messages/0/reasoning").asString()).isEqualTo("Think.");
		assertThat(replay.at("/messages/0/reasoning_details")).isEqualTo(this.mapper.readTree(DETAILS));
		assertThat(replay.at("/messages/0/tool_calls/0/id").asString()).isEqualTo("call1");
	}

	@Test
	void chatCompletionsDoesNotReplayReasoningAnotherProviderSigned() {
		AssistantMessage foreign = AssistantMessage.builder()
			.reasoning(new ReasoningPart("Think.", null, new OpaquePayload("anthropic", "signature", "sig"), Map.of()))
			.reasoning(ReasoningPart.of(" More."))
			.content("Answer.")
			.build();
		AssistantMessage unsigned = AssistantMessage.builder()
			.reasoning(ReasoningPart.of("Plain."))
			.content("Answer.")
			.build();

		JsonNode signed = chatRequest(foreign).at("/messages/0");
		assertThat(signed.has("reasoning")).isFalse();
		assertThat(signed.has("reasoning_details")).isFalse();
		assertThat(signed.get("content").asString()).isEqualTo("Answer.");
		JsonNode plain = chatRequest(unsigned).at("/messages/0");
		assertThat(plain.get("reasoning").asString()).isEqualTo("Plain.");
		assertThat(plain.has("reasoning_details")).isFalse();
	}

	@Test
	void chatCompletionsReplaysLegacyMetadataAfterAJsonRoundTrip() {
		AssistantMessage current = chatCall().getResult().getOutput();
		// A 0.3.0 message: legacy parts and the metadata keys, stored as JSON.
		SessionEventCodec codec = new SessionEventCodec();
		Map<String, Object> stored = codec.fromJsonMap(codec.toJson(current.getMetadata()));
		AssistantMessage legacy = AssistantMessage.builder()
			.content(current.getText())
			.toolCalls(current.getToolCalls())
			.properties(stored)
			.build();
		assertThat(stored.get("openrouter.reasoning_details")).isInstanceOf(List.class);

		JsonNode replay = chatRequest(legacy);
		assertThat(replay.at("/messages/0/reasoning").asString()).isEqualTo("Think.");
		assertThat(replay.at("/messages/0/reasoning_details")).isEqualTo(this.mapper.readTree(DETAILS));
	}

	@Test
	void chatCompletionsStreamAggregatesToTheBlockingParts() {
		String id = "\"id\":\"gen-1\",\"model\":\"synthetic-model\",";
		Flux<ChatCompletionChunk> chunks = Flux.just(chunk(id,
				"{\"role\":\"assistant\",\"reasoning\":\"Th\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"Th\",\"format\":\"anthropic-claude-v1\",\"index\":0}]}",
				null),
				chunk(id,
						"{\"reasoning\":\"ink.\",\"reasoning_details\":[{\"type\":\"reasoning.text\",\"text\":\"ink.\",\"signature\":\"synthetic-signature\",\"index\":0}]}",
						null),
				chunk(id,
						"{\"reasoning_details\":[{\"type\":\"reasoning.encrypted\",\"data\":\"synthetic-ciphertext\",\"id\":\"call1\",\"format\":\"google-gemini-v1\",\"index\":1}]}",
						null),
				chunk(id, "{\"content\":\"Check\"}", null), chunk(id, "{\"content\":\"ing.\"}", null),
				chunk(id,
						"{\"tool_calls\":[{\"index\":0,\"id\":\"call1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"city\\\":\"}}]}",
						null),
				chunk(id, "{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Oslo\\\"}\"}}]}", null),
				chunk(id, "{}", "tool_calls"));
		List<ChatResponse> responses = new OpenRouterStreamingResponseMapper()
			.map(chunks, new OpenRouterStreamingToolCallAggregator(), null)
			.collectList()
			.block();

		List<MessagePart> deltas = responses.stream()
			.flatMap(response -> response.getResult().getOutput().getParts().stream())
			.filter(part -> part instanceof ToolCallPart && StreamingParts.isPartial(part))
			.toList();
		assertThat(deltas).extracting(part -> ((ToolCallPart) part).toolCall().arguments())
			.containsExactly("{\"city\":", "\"Oslo\"}");
		assertThat(responses).allSatisfy(response -> assertThat(response.getMetadata().getId()).isEqualTo("gen-1"));
		ToolCallPart complete = responses.get(responses.size() - 1)
			.getResult()
			.getOutput()
			.getParts()
			.stream()
			.filter(ToolCallPart.class::isInstance)
			.map(ToolCallPart.class::cast)
			.findFirst()
			.orElseThrow();
		assertThat(StreamingParts.isPartial(complete)).isFalse();
		assertThat(StreamingParts.partIndex(complete)).isEqualTo(StreamingParts.partIndex(deltas.get(0)));
		assertThat(
				responses.stream().mapToLong(response -> response.getResult().getOutput().getToolCalls().size()).sum())
			.isEqualTo(1);

		AssistantMessage aggregated = aggregate(Flux.fromIterable(responses));
		AssistantMessage blocking = chatCall().getResult().getOutput();
		assertThat(aggregated.getParts()).hasSize(3);
		ReasoningPart reasoning = aggregated.getReasoning().get(0);
		assertThat(reasoning.text()).isEqualTo("Think.");
		assertThat(this.mapper.readTree(reasoning.payload().data()))
			.isEqualTo(this.mapper.readTree(blocking.getReasoning().get(0).payload().data()));
		assertThat(aggregated.getParts().subList(1, 3)).isEqualTo(blocking.getParts().subList(1, 3));
		assertThat(chatRequest(aggregated).at("/messages/0")).isEqualTo(chatRequest(blocking).at("/messages/0"));
	}

	@Test
	void chatCompletionsRefusalIsFiltered() {
		ChatCompletionResponse wire = this.mapper.readValue("""
				{"id":"gen-1","choices":[{"index":0,"finish_reason":"stop",
				 "message":{"role":"assistant","content":"","refusal":"Not allowed."}}]}
				""", ChatCompletionResponse.class);
		ChatResponse response = new OpenRouterChatResponseMapper().map(wire);
		assertThat(response.getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.FILTERED);
		assertThat(chatCall().getResult().getMetadata().getFinishReasonCategory())
			.isEqualTo(FinishReasonCategory.TOOL_CALL);

		ChatCompletionChunk refusal = chunk("", "{\"refusal\":\"Not allowed.\"}", "stop");
		assertThat(new OpenRouterStreamingResponseMapper().map(refusal)
			.getResult()
			.getMetadata()
			.getFinishReasonCategory()).isEqualTo(FinishReasonCategory.FILTERED);
	}

	@Test
	void responsesReplyIsOnePartPerOutputItemAndReplaysVerbatim() {
		AssistantMessage message = responsesCall().getResult().getOutput();

		assertThat(message.getParts()).hasSize(4);
		ReasoningPart reasoning = (ReasoningPart) message.getParts().get(0);
		assertThat(reasoning.text()).isEqualTo("Think.");
		assertThat(reasoning.summary()).isEqualTo("Plan.");
		assertThat(reasoning.payload().provider()).isEqualTo(OpenRouterMetadata.RESPONSES_PROVIDER);
		assertThat(reasoning.payload().kind()).isEqualTo(OpenRouterMetadata.RESPONSES_OUTPUT_ITEM_KIND);
		UnknownPart hosted = (UnknownPart) message.getParts().get(1);
		assertThat(hosted.kind()).isEqualTo("web_search_call");
		assertThat(((TextPart) message.getParts().get(2)).text()).isEqualTo("Checking.");
		assertThat(message.getToolCalls()).extracting(AssistantMessage.ToolCall::id).containsExactly("call1");

		JsonNode input = responsesRequest(message).get("input");
		JsonNode output = this.mapper.readTree(RESPONSES_OUTPUT);
		for (int index = 0; index < output.size(); index++) {
			assertThat(input.get(index)).isEqualTo(output.get(index));
		}
	}

	@Test
	void responsesRebuildsEditedPartsAndSkipsForeignReasoning() {
		AssistantMessage edited = responsesCall().getResult().getOutput().mutate().content("Edited.").build();
		JsonNode input = responsesRequest(edited).get("input");
		assertThat(input.get(2).at("/content/0/text").asString()).isEqualTo("Edited.");
		assertThat(input.get(2).has("id")).isFalse();
		assertThat(input.get(3).get("id").asString()).isEqualTo("fc1");

		// Chat Completions reasoning has no Responses payload, so it is not sent.
		JsonNode crossMode = responsesRequest(chatCall().getResult().getOutput()).get("input");
		assertThat(crossMode.get(0).get("type").asString()).isEqualTo("message");
		assertThat(crossMode.get(1).get("type").asString()).isEqualTo("function_call");
		// Responses reasoning is not Chat Completions reasoning either.
		JsonNode chat = chatRequest(responsesCall().getResult().getOutput()).at("/messages/0");
		assertThat(chat.has("reasoning")).isFalse();
		assertThat(chat.has("reasoning_details")).isFalse();
	}

	@Test
	void responsesStreamAggregatesToTheBlockingParts() {
		ResponsesResult terminal = terminal();
		Flux<ResponsesStreamEvent> events = Flux.just(
				event("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"status\":\"in_progress\"}}"),
				event("{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"Th\"}"),
				event("{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"ink.\"}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":"
						+ this.mapper.readTree(RESPONSES_OUTPUT).get(0) + "}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":"
						+ this.mapper.readTree(RESPONSES_OUTPUT).get(1) + "}"),
				event("{\"type\":\"response.output_text.delta\",\"output_index\":2,\"content_index\":0,\"delta\":\"Checking.\"}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":2,\"item\":"
						+ this.mapper.readTree(RESPONSES_OUTPUT).get(2) + "}"),
				event("{\"type\":\"response.output_item.added\",\"output_index\":3,\"item\":{\"type\":\"function_call\",\"id\":\"fc1\",\"call_id\":\"call1\",\"name\":\"lookup\",\"arguments\":\"\"}}"),
				event("{\"type\":\"response.function_call_arguments.delta\",\"output_index\":3,\"delta\":\"{\\\"city\\\":\\\"Oslo\\\"}\"}"),
				event("{\"type\":\"response.output_item.done\",\"output_index\":3,\"item\":"
						+ this.mapper.readTree(RESPONSES_OUTPUT).get(3) + "}"),
				new ResponsesStreamEvent("response.completed", null, null, terminal, null));
		List<ChatResponse> responses = new OpenRouterResponsesStreamingResponseMapper().map(events)
			.collectList()
			.block();

		assertThat(responses).allSatisfy(response -> assertThat(response.getMetadata().getId()).isEqualTo("resp_1"));
		assertThat(responses.get(8).getResult().getOutput().getParts()).singleElement()
			.satisfies(part -> assertThat(StreamingParts.isPartial(part)).isTrue())
			.satisfies(part -> assertThat(StreamingParts.partIndex(part)).isEqualTo(3));
		assertThat(
				responses.stream().mapToLong(response -> response.getResult().getOutput().getToolCalls().size()).sum())
			.isEqualTo(1);
		AssistantMessage aggregated = aggregate(Flux.fromIterable(responses));
		AssistantMessage blocking = responsesCall().getResult().getOutput();
		assertThat(aggregated.getParts()).isEqualTo(blocking.getParts());
		assertThat(responsesRequest(aggregated)).isEqualTo(responsesRequest(blocking));
	}

	@Test
	void userTextAndMediaKeepTheirOrderInBothModes() {
		Media image = Media.builder()
			.mimeType(MimeTypeUtils.IMAGE_PNG)
			.data(URI.create("https://example.com/a.png"))
			.build();
		UserMessage user = UserMessage.builder()
			.parts(List.of(TextPart.of("Before."), MediaPart.of(image), TextPart.of("After.")))
			.build();
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().model("synthetic-model").build();

		JsonNode chat = this.mapper
			.valueToTree(new OpenRouterChatRequestMapper(this.mapper).map(List.of(user), options, false, List.of()));
		assertThat(chat.at("/messages/0/content")).extracting(node -> node.get("type").asString())
			.containsExactly("text", "image_url", "text");
		assertThat(chat.at("/messages/0/content/2/text").asString()).isEqualTo("After.");
		JsonNode responses = this.mapper.valueToTree(
				new OpenRouterResponsesRequestMapper(this.mapper).map(List.of(user), options, false, List.of()));
		assertThat(responses.at("/input/0/content")).extracting(node -> node.get("type").asString())
			.containsExactly("input_text", "input_image", "input_text");
	}

	@Test
	void reasoningAndToolCallSurviveSessionEventSerialization() {
		SessionEventCodec codec = new SessionEventCodec();
		for (AssistantMessage original : List.of(chatCall().getResult().getOutput(),
				responsesCall().getResult().getOutput())) {
			SessionEvent event = SessionEvent.builder().sessionId("synthetic-session").message(original).build();
			AssistantMessage restored = (AssistantMessage) codec.decode(codec.encode(event))
				.sessionId("synthetic-session")
				.build()
				.getMessage();

			assertThat(restored.getParts()).isEqualTo(original.getParts());
			assertThat(restored.getReasoning()).isNotEmpty();
			assertThat(chatRequest(restored)).isEqualTo(chatRequest(original));
			assertThat(responsesRequest(restored)).isEqualTo(responsesRequest(original));
		}
	}

	private ChatResponse chatCall() {
		return new OpenRouterChatResponseMapper().map(this.mapper.readValue("""
				{"id":"gen-1","model":"synthetic-model","choices":[{"index":0,"finish_reason":"tool_calls",
				 "message":{"role":"assistant","content":"Checking.","reasoning":"Think.","reasoning_details":%s,
				 "tool_calls":[%s]}}]}
				""".formatted(DETAILS, TOOL_CALL), ChatCompletionResponse.class));
	}

	private ResponsesResult terminal() {
		return this.mapper.readValue("{\"id\":\"resp_1\",\"status\":\"completed\",\"output\":" + RESPONSES_OUTPUT + "}",
				ResponsesResult.class);
	}

	private ChatResponse responsesCall() {
		return new OpenRouterResponsesResponseMapper().map(terminal());
	}

	private ChatCompletionChunk chunk(String id, String delta, String finish) {
		return this.mapper.readValue("{" + id + "\"choices\":[{\"index\":0,\"delta\":" + delta + ",\"finish_reason\":"
				+ (finish == null ? "null" : "\"" + finish + "\"") + "}]}", ChatCompletionChunk.class);
	}

	private ResponsesStreamEvent event(String json) {
		return this.mapper.readValue(json, ResponsesStreamEvent.class);
	}

	private static AssistantMessage aggregate(Flux<ChatResponse> responses) {
		AtomicReference<ChatResponse> result = new AtomicReference<>();
		new MessageAggregator().aggregate(responses, result::set).blockLast();
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
		return List.of(assistant,
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("call1", "lookup", "sunny")))
					.build());
	}

}
