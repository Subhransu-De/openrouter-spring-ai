package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenRouterResponsePreservationTests {

	private static final String REFUSAL = "openrouter.refusal";

	private final ObjectMapper json = new ObjectMapper();

	@ParameterizedTest(name = "{0}: {1} {2}")
	@CsvSource({ "sync,completed,,STOP,completed", "sync,incomplete,max_output_tokens,LENGTH,max_output_tokens",
			"sync,incomplete,content_filter,CONTENT_FILTER,content_filter",
			"sync,incomplete,future_reason,future_reason,future_reason", "sync,incomplete,,incomplete,incomplete",
			"stream,completed,,STOP,completed", "stream,incomplete,max_output_tokens,LENGTH,max_output_tokens",
			"stream,incomplete,content_filter,CONTENT_FILTER,content_filter",
			"stream,incomplete,future_reason,future_reason,future_reason", "stream,incomplete,,incomplete,incomplete" })
	void terminalReasonsAgreeAcrossResponsesPaths(String path, String status, String reason, String expected,
			String expectedNative) {
		ResponsesResult result = this.json.readValue("""
				{"status":"%s","incomplete_details":{"reason":%s},"output":[]}
				""".formatted(status, this.json.writeValueAsString(reason)), ResponsesResult.class);
		ChatResponse response = path.equals("sync") ? ResponseMappers.responses().map(result) : ResponseMappers
			.responsesEvent(new ResponsesStreamEvent("response." + status, null, null, result, null));

		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(expected);
		assertThat(response.getResult().getMetadata().<String>get("openrouter.native_finish_reason"))
			.isEqualTo(expectedNative);
		assertThat(response.getResult().getMetadata().<String>get("openrouter.responses.status")).isEqualTo(status);
		assertThat(response.getResult().getMetadata().<Object>get("openrouter.responses.incomplete_details"))
			.isEqualTo(result.incompleteDetails());
	}

	@Test
	void preservesWhitespaceAndNullTextAcrossPartsAndMessagesWithoutRepeatingTerminalText() {
		ResponsesResult result = this.json.readValue("""
				{"status":"completed","output":[
				 {"type":"message","content":[{"type":"output_text","text":"hello"},
				   {"type":"output_text","text":" "},{"type":"output_text","text":null},
				   {"type":"text","text":"world"}]},
				 {"type":"message","content":[{"type":"output_text","text":"\\n\\t"}]},
				 {"type":"message","content":[{"type":"output_text","text":"end"}]}]}
				""", ResponsesResult.class);
		String expected = "hello world\n\tend";
		assertThat(ResponseMappers.responses().map(result).getResult().getOutput().getText()).isEqualTo(expected);
		Flux<ResponsesStreamEvent> events = Flux.range(0, result.output().size())
			.concatMap(output -> Flux.range(0, result.output().get(output).content().size())
				.map(content -> new ResponsesStreamEvent("response.output_text.delta",
						result.output().get(output).content().get(content).text(), null, null, null, null, null, null,
						null, null, output, content)))
			.concatWithValues(new ResponsesStreamEvent("response.completed", null, null, result, null));
		StepVerifier
			.create(ResponseMappers.responsesStreaming()
				.map(events)
				.map(response -> response.getResult().getOutput().getText())
				.reduce("", String::concat))
			.expectNext(expected)
			.verifyComplete();
	}

	@ParameterizedTest(name = "finish_reason {0} -> {1}")
	@CsvSource({ "stop, STOP", "content_filter, CONTENT_FILTER" })
	void chatRefusalRemainsSeparateFromContentAndPreservesActualFinishReason(String finish, String expectedFinish) {
		ChatCompletionResponse wire = this.json.readValue("""
				{"choices":[{"index":0,"message":{"role":"assistant","content":"explanation",
				 "refusal":"Cannot comply.","future_field":true},"finish_reason":"%s"}]}
				""".formatted(finish), ChatCompletionResponse.class);
		ChatResponse response = ResponseMappers.chat().map(wire);
		assertRefusalBesideText(response, "Cannot comply.", "explanation");
		assertThat(response.getResult().getOutput().getText()).isEqualTo("explanation");
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo(expectedFinish);
	}

	@Test
	void chatRefusalDeltasAccumulatePerChoiceAndSubscription() {
		Flux<ChatCompletionChunk> chunks = Flux.just("""
				{"choices":[{"index":0,"delta":{"refusal":"Cannot "}},
				 {"index":1,"delta":{"refusal":"Other"}}]}
				""", """
				{"choices":[{"index":0,"delta":{"refusal":"comply."},"finish_reason":"stop"},
				 {"index":1,"delta":{},"finish_reason":"content_filter"}]}
				""").map(value -> this.json.readValue(value, ChatCompletionChunk.class));
		Flux<ChatResponse> mapped = ResponseMappers.chatStream(chunks);

		verifyChatRefusalDeltas(mapped);
		verifyChatRefusalDeltas(mapped);
	}

	private void verifyChatRefusalDeltas(Flux<ChatResponse> mapped) {
		StepVerifier.create(mapped).assertNext(response -> {
			assertRefusal(response, "Cannot ");
		}).assertNext(response -> {
			assertRefusal(response, "Cannot comply.");
			assertThat(response.getResults().get(1).getOutput().getMetadata()).containsEntry(REFUSAL, "Other");
		}).verifyComplete();
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void responsesRefusalsSurviveDeltasDoneAndTerminalSnapshots(boolean deltas) {
		ResponsesResult result = this.json.readValue("""
				{"status":"completed","output":[{"type":"message","content":[
				 {"type":"refusal","refusal":"Cannot comply.","future_field":true}]}]}
				""", ResponsesResult.class);
		ChatResponse sync = ResponseMappers.responses().map(result);
		assertRefusal(sync, "Cannot comply.");
		assertThat(sync.getResult().getOutput().getText()).isEmpty();
		assertThat(sync.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		Flux<ResponsesStreamEvent> deltaEvents = deltas
				? Flux.just("Cannot ", "comply.")
					.map(text -> new ResponsesStreamEvent("response.refusal.delta", text, null, null, null))
				: Flux.empty();
		Flux<ResponsesStreamEvent> events = deltaEvents.concatWith(Flux.just(this.json.readValue("""
				{"type":"response.refusal.done","refusal":"Cannot comply."}
				""", ResponsesStreamEvent.class),
				new ResponsesStreamEvent("response.output_item.done", null, result.output().get(0), null, null),
				new ResponsesStreamEvent("response.completed", null, null, result, null)));
		Flux<ChatResponse> mapped = ResponseMappers.responsesStreaming().map(events);

		verifyAggregatedResponsesRefusal(mapped);
		verifyAggregatedResponsesRefusal(mapped);
	}

	private void verifyAggregatedResponsesRefusal(Flux<ChatResponse> mapped) {
		AtomicReference<ChatResponse> aggregate = new AtomicReference<>();
		StepVerifier.create(new MessageAggregator().aggregate(mapped, aggregate::set)).thenConsumeWhile(response -> {
			assertThat(response.getResult().getOutput().getText()).isEmpty();
			return true;
		}).verifyComplete();
		assertRefusal(aggregate.get(), "Cannot comply.");
	}

	@Test
	void toolCallBufferingRetainsRefusalFragments() {
		Flux<ChatCompletionChunk> chunks = Flux.just("""
				{"choices":[{"index":0,"delta":{"refusal":"Cannot ","tool_calls":[
				 {"index":0,"id":"call-1","type":"function","function":{"name":"example","arguments":"{}"}}]}}]}
				""", """
				{"choices":[{"index":0,"delta":{"refusal":"comply."},"finish_reason":"tool_calls"}]}
				""").map(value -> this.json.readValue(value, ChatCompletionChunk.class));
		StepVerifier.create(ResponseMappers.chatStream(ResponseMappers.aggregator().aggregate(chunks)))
			.assertNext(response -> {
				assertRefusal(response, "Cannot comply.");
				assertThat(response.hasToolCalls()).isTrue();
			})
			.verifyComplete();
	}

	@Test
	void indexedRefusalDoneEventsReplaceOnlyTheirOwnPart() {
		Flux<ResponsesStreamEvent> events = Flux.just("""
				{"type":"response.refusal.delta","output_index":1,"content_index":0,"delta":"third"}
				""", """
				{"type":"response.refusal.delta","output_index":0,"content_index":1,"delta":" second "}
				""", """
				{"type":"response.refusal.done","output_index":0,"content_index":0,"refusal":"first"}
				""", """
				{"type":"response.refusal.done","output_index":0,"content_index":1,"refusal":" second "}
				""", """
				{"type":"response.output_item.done","output_index":0,"item":{"type":"message","content":[
				 {"type":"refusal","refusal":"first"},{"type":"refusal","refusal":" second "}]}}
				""", """
				{"type":"response.refusal.done","output_index":0,"content_index":1,"refusal":" second "}
				""", """
				{"type":"response.completed","response":{"status":"completed","output":[
				 {"type":"message","content":[{"type":"refusal"},{"type":"refusal"}]}]}}
				""").map(value -> this.json.readValue(value, ResponsesStreamEvent.class));
		StepVerifier.create(ResponseMappers.responsesStreaming().map(events).last())
			.assertNext(response -> assertRefusal(response, "first second third"))
			.verifyComplete();
	}

	static Stream<Arguments> ordinaryEmptyResponses() {
		ObjectMapper mapper = new ObjectMapper();
		ResponsesResult empty = mapper.readValue("{\"status\":\"completed\",\"output\":[]}", ResponsesResult.class);
		ChatCompletionResponse chat = mapper.readValue(
				"{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}",
				ChatCompletionResponse.class);
		return Stream.of(
				Arguments.of(Named.<Supplier<ChatResponse>>of("responses result",
						() -> ResponseMappers.responses().map(empty))),
				Arguments.of(Named.<Supplier<ChatResponse>>of("responses completed event",
						() -> ResponseMappers
							.responsesEvent(new ResponsesStreamEvent("response.completed", null, null, empty, null)))),
				Arguments
					.of(Named.<Supplier<ChatResponse>>of("chat completion", () -> ResponseMappers.chat().map(chat))));
	}

	@ParameterizedTest
	@MethodSource("ordinaryEmptyResponses")
	void responsesWithoutARefusalCarryNoRefusalMetadataAndReplayNone(Supplier<ChatResponse> mapped) {
		ChatResponse response = mapped.get();
		assertThat(response.getResult().getOutput().getMetadata()).doesNotContainKey(REFUSAL);
		assertThat(response.getResult().getMetadata().<String>get(REFUSAL)).isNull();
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().model("synthetic-model").build();
		JsonNode replay = this.json.valueToTree(new OpenRouterChatRequestMapper(this.json)
			.map(List.of(response.getResult().getOutput(), new UserMessage("Continue")), options, false, List.of()));
		assertThat(replay.at("/messages/0").has("refusal")).isFalse();
	}

	private void assertRefusal(ChatResponse response, String expected) {
		JsonNode content = assertRefusalMetadataAndWire(response, expected);
		assertThat(partTypes(content)).containsExactly("refusal");
	}

	private void assertRefusalBesideText(ChatResponse response, String expected, String text) {
		JsonNode content = assertRefusalMetadataAndWire(response, expected);
		assertThat(partTypes(content)).containsExactly("output_text", "refusal");
		assertThat(content.get(0).get("text").asString()).isEqualTo(text);
	}

	private static List<String> partTypes(JsonNode content) {
		return content.valueStream().map(part -> part.get("type").asString()).toList();
	}

	private JsonNode assertRefusalMetadataAndWire(ChatResponse response, String expected) {
		assertThat(response.getResult().getOutput().getMetadata()).containsEntry(REFUSAL, expected);
		assertThat(response.getResult().getMetadata().<String>get(REFUSAL)).isEqualTo(expected);
		AssistantMessage assistant = response.getResult().getOutput();
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().model("synthetic-model").build();
		JsonNode chat = this.json.valueToTree(new OpenRouterChatRequestMapper(this.json)
			.map(List.of(assistant, new UserMessage("Continue")), options, false, List.of()));
		assertThat(chat.at("/messages/0/refusal").asString()).isEqualTo(expected);
		assertThat(chat.at("/messages/0/content").asString()).isEqualTo(assistant.getText());
		JsonNode responses = this.json.valueToTree(new OpenRouterResponsesRequestMapper(this.json)
			.map(List.of(assistant, new UserMessage("Continue")), options, false, List.of()));
		JsonNode content = responses.at("/input/0/content");
		assertThat(content.get(content.size() - 1).get("refusal").asString()).isEqualTo(expected);
		return content;
	}

}
