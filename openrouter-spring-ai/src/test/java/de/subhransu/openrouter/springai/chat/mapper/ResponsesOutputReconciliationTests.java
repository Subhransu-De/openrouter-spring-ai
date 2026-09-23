package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class ResponsesOutputReconciliationTests {

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterResponsesStreamingResponseMapper mapper = new OpenRouterResponsesStreamingResponseMapper();

	@ParameterizedTest
	@ValueSource(strings = { "completed", "incomplete" })
	void recoversSuffixesAndTerminalPartsInOutputOrder(String status) {
		var terminal = terminal(status, """
				{"id":"m0","type":"message","content":[
				{"type":"output_text","text":"hello world"},
				{"type":"output_text","text":" \\n"}]},
				{"id":"m1","type":"message","content":[{"type":"output_text","text":"second!"}]},
				{"id":"m2","type":"message","content":[{"type":"output_text","text":" last"}]}
				""");
		var responses = this.mapper
			.map(Flux.just(delta(0, 0, "hello"), delta(0, 1, " "), delta(1, 0, "second"), terminal))
			.collectList()
			.block();
		assertThat(responses).extracting(response -> response.getResult().getOutput().getText())
			.containsExactly("hello", "", "", " world \nsecond! last");
		assertThat(
				aggregate(Flux.just(delta(0, 0, "hello"), delta(0, 1, " "), delta(1, 0, "second"), terminal)).getText())
			.isEqualTo(
					new OpenRouterResponsesResponseMapper().map(terminal.response()).getResult().getOutput().getText());
	}

	@Test
	void doneSnapshotsEmitOnlyMissingSuffixAndPreserveWhitespace() {
		var done = event("""
				{"type":"response.output_text.done","output_index":0,"content_index":0,"text":"hi "}
				""");
		var terminal = terminal("completed", """
				{"type":"message","content":[{"type":"output_text","text":"hi "},
				{"type":"output_text","text":"\\n"}]}
				""");
		assertThat(this.mapper.map(Flux.just(delta(0, 0, "hi"), done, done, delta(0, 1, "\n"), terminal))
			.collectList()
			.block()).extracting(response -> response.getResult().getOutput().getText())
			.containsExactly("hi", " ", "", "\n", "");
		assertThat(aggregate(Flux.just(terminal)).getText()).isEqualTo("hi \n");
	}

	@ParameterizedTest
	@ValueSource(strings = { "response.output_text.done", "response.completed", "response.output_item.done" })
	void rejectsContradictorySnapshotsWithoutLeakingText(String type) {
		var snapshot = switch (type) {
			case "response.output_text.done" -> event("""
					{"type":"response.output_text.done","text":"different-private-text"}
					""");
			case "response.output_item.done" -> event("""
					{"type":"response.output_item.done","item":{"type":"message","content":[
					{"type":"output_text","text":"different-private-text"}]}}
					""");
			default -> terminal("completed", """
					{"type":"message","content":[{"type":"output_text","text":"different-private-text"}]}
					""");
		};
		StepVerifier.create(this.mapper.map(Flux.just(delta(0, 0, "synthetic-private-text"), snapshot)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("prefix")
				.hasMessageNotContaining("private-text"))
			.verify();
	}

	@Test
	void itemSnapshotsReleaseBufferedLaterMessagesAndRejectLateRewrites() {
		var first = event("""
				{"type":"response.output_item.done","output_index":0,"item":{"type":"message",
				"content":[{"type":"output_text","text":"first complete"}]}}
				""");
		var terminal = terminal("completed", """
				{"type":"message","content":[{"type":"output_text","text":"first complete"}]},
				{"type":"message","content":[{"type":"output_text","text":" second"}]}
				""");
		assertThat(this.mapper.map(Flux.just(delta(0, 0, "first"), delta(1, 0, " second"), first, terminal))
			.collectList()
			.block()).extracting(response -> response.getResult().getOutput().getText())
			.containsExactly("first", "", " complete second", "");
		var changed = terminal("completed", """
				{"type":"message","content":[{"type":"output_text","text":"first complete extra"}]},
				{"type":"message","content":[{"type":"output_text","text":" second"}]}
				""");
		StepVerifier.create(this.mapper.map(Flux.just(first, delta(1, 0, " second"), changed)))
			.expectNextCount(2)
			.expectError(OpenRouterProtocolException.class)
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "{\"type\":\"message\",\"content\":[]}" })
	void rejectsSnapshotsThatRemoveReceivedText(String output) {
		StepVerifier.create(this.mapper.map(Flux.just(delta(0, 0, "hello"), terminal("completed", output))))
			.expectNextCount(1)
			.expectError(OpenRouterProtocolException.class)
			.verify();
	}

	@Test
	void unchangedAggregatedReasoningHistoryReplaysButUserEditsFail() {
		var terminal = terminal("completed", """
				{"type":"reasoning","id":"r0","encrypted_content":"synthetic"},
				{"type":"message","id":"m0","role":"assistant","content":[
				{"type":"output_text","text":"hello world"}]},
				{"type":"message","id":"m1","role":"assistant","content":[
				{"type":"output_text","text":" again"}]}
				""");
		AssistantMessage message = aggregate(Flux.just(delta(1, 0, "hello"), terminal));
		assertThat(message.getText()).isEqualTo("hello world again");
		var options = OpenRouterChatOptions.builder()
			.model("synthetic")
			.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
			.build();
		var requests = new OpenRouterResponsesRequestMapper(this.json);
		assertThat(this.json.valueToTree(requests.map(List.of(message), options, false, List.of())).get("input"))
			.isEqualTo(this.json.valueToTree(terminal.response().output()));
		var edited = AssistantMessage.builder()
			.content(message.getText() + " edited")
			.properties(message.getMetadata())
			.build();
		assertThatThrownBy(() -> requests.map(List.of(edited), options, false, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("stale assistant output snapshot");
	}

	@ParameterizedTest
	@ValueSource(strings = { "png", "webp", "jpeg" })
	void repeatedAndTerminalOnlyImagesMatchSynchronousMedia(String format) {
		var terminal = terminal("completed",
				"""
						{"type":"image_generation_call","id":"i0","result":"AQID","output_format":"%s"},
						{"type":"image_generation_call","id":"i1","result":"AQID","output_format":"%s"},
						{"type":"image_generation_call","id":"i2","result":"https://example.invalid/synthetic","output_format":"%s"}
						"""
					.formatted(format, format, format));
		var item = new ResponsesStreamEvent("response.output_item.done", null, terminal.response().output().get(0),
				null, null, null, null, null, null, null, 0, null);
		var expected = new OpenRouterResponsesResponseMapper().map(terminal.response())
			.getResult()
			.getOutput()
			.getMedia();
		assertMedia(media(Flux.just(terminal)), expected);
		assertMedia(media(Flux.just(item, item, terminal)), expected);
		assertThat(expected).hasSize(3);
		assertThat(expected.get(0).getData()).isEqualTo("data:image/" + format + ";base64,AQID");
		assertThat(expected.get(2).getData()).isEqualTo("https://example.invalid/synthetic");
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any())).thenReturn(terminal.response());
		when(api.responsesStream(any())).thenReturn(Flux.just(item, item, terminal));
		var model = OpenRouterChatModel.builder().openRouterApi(api).build();
		var prompt = new Prompt("synthetic",
				OpenRouterChatOptions.builder()
					.model("synthetic")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build());
		assertMedia(model.stream(prompt)
			.flatMapIterable(response -> response.getResult().getOutput().getMedia())
			.collectList()
			.block(), model.call(prompt).getResult().getOutput().getMedia());
	}

	@Test
	void imageIdentityUsesIdsOrIndexesAndNeverCollapsesDistinctEqualBytes() {
		var terminal = terminal("completed", """
				{"type":"image_generation_call","result":"AQID"},
				{"type":"image_generation_call","result":"AQID"},
				{"type":"image_generation_call","id":"i2","result":"AQID"}
				""");
		var indexed = event(
				"""
						{"type":"response.output_item.done","output_index":0,"item":{"type":"image_generation_call","result":"AQID"}}
						""");
		var identified = event("""
				{"type":"response.output_item.done","item":{"type":"image_generation_call","id":"i2","result":"AQID"}}
				""");
		assertThat(media(Flux.just(indexed, indexed, identified, identified, terminal))).hasSize(3);
		var ambiguous = event("""
				{"type":"response.output_item.done","item":{"type":"image_generation_call","result":"AQID"}}
				""");
		StepVerifier.create(this.mapper.map(Flux.just(ambiguous)))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("ID or output index"))
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "\"result\":\"BAUG\"", "\"result\":\"AQID\",\"output_format\":\"webp\"" })
	void rejectsConflictingImageSnapshots(String fields) {
		var first = event(
				"""
						{"type":"response.output_item.done","output_index":0,"item":{"type":"image_generation_call","id":"i0","result":"AQID"}}
						""");
		var terminal = terminal("completed", "{\"type\":\"image_generation_call\",\"id\":\"i0\"," + fields + "}");
		StepVerifier.create(this.mapper.map(Flux.just(first, terminal)))
			.expectNextCount(1)
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("image snapshot")
				.hasMessageNotContaining("AQID"))
			.verify();
	}

	@Test
	void cancellationAndOverlappingSubscriptionsKeepIndependentState() {
		var terminal = terminal("completed", """
				{"type":"message","content":[{"type":"output_text","text":"hello world"}]},
				{"type":"image_generation_call","id":"i1","result":"AQID"}
				""");
		var image = new ResponsesStreamEvent("response.output_item.done", null, terminal.response().output().get(1),
				null, null, null, null, null, null, null, 1, null);
		AtomicBoolean cancelled = new AtomicBoolean();
		var stream = this.mapper
			.map(Flux.just(delta(0, 0, "hello"), image, terminal).doOnCancel(() -> cancelled.set(true)));
		StepVerifier.create(stream).expectNextCount(2).thenCancel().verify();
		assertThat(cancelled).isTrue();
		StepVerifier.create(Flux.zip(stream, stream)).assertNext(pair -> {
			assertThat(pair.getT1().getResult().getOutput().getText()).isEqualTo("hello");
			assertThat(pair.getT2().getResult().getOutput().getText()).isEqualTo("hello");
		}).assertNext(pair -> {
			assertThat(pair.getT1().getResult().getOutput().getMedia()).hasSize(1);
			assertThat(pair.getT2().getResult().getOutput().getMedia()).hasSize(1);
		}).assertNext(pair -> {
			assertThat(pair.getT1().getResult().getOutput().getText()).isEqualTo(" world");
			assertThat(pair.getT2().getResult().getOutput().getMedia()).isEmpty();
		}).verifyComplete();
	}

	private ResponsesStreamEvent delta(int output, int content, String text) {
		return new ResponsesStreamEvent("response.output_text.delta", text, null, null, null, null, null, null, null,
				null, output, content);
	}

	private ResponsesStreamEvent terminal(String status, String output) {
		return event("{\"type\":\"response." + status + "\",\"response\":{\"status\":\"" + status + "\",\"output\":["
				+ output + "]}}");
	}

	private ResponsesStreamEvent event(String value) {
		return this.json.readValue(value, ResponsesStreamEvent.class);
	}

	private void assertMedia(List<Media> actual, List<Media> expected) {
		assertThat(actual).extracting(Media::getData, Media::getMimeType)
			.containsExactlyElementsOf(
					expected.stream().map(media -> tuple(media.getData(), media.getMimeType())).toList());
	}

	private List<Media> media(Flux<ResponsesStreamEvent> events) {
		return this.mapper.map(events)
			.flatMapIterable(response -> response.getResult().getOutput().getMedia())
			.collectList()
			.block();
	}

	private AssistantMessage aggregate(Flux<ResponsesStreamEvent> events) {
		AtomicReference<ChatResponse> result = new AtomicReference<>();
		new MessageAggregator().aggregate(this.mapper.map(events), result::set).blockLast();
		return result.get().getResult().getOutput();
	}

}
