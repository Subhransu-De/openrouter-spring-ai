package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint.METADATA_KEY;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint.Ttl;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.api.Named;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenRouterCacheBreakpointTests {

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterChatRequestMapper mapper = new OpenRouterChatRequestMapper(this.json);

	private final OpenRouterChatOptions options = OpenRouterChatOptions.builder()
		.model("anthropic/claude-sonnet-4")
		.build();

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void serializesTextBoundariesAndPreservesMediaAndToolContinuation(boolean stream) {
		List<OpenRouterCacheBreakpoint> boundaries = new ArrayList<>(
				List.of(new OpenRouterCacheBreakpoint(6, Ttl.ONE_HOUR), new OpenRouterCacheBreakpoint(12)));
		UserMessage user = UserMessage.builder()
			.text("prefixmiddle tail")
			.metadata(Map.of(METADATA_KEY, boundaries))
			.media(Media.builder()
				.mimeType(MimeTypeUtils.IMAGE_PNG)
				.data(URI.create("https://example.test/image.png"))
				.build())
			.build();
		SystemMessage system = SystemMessage.builder()
			.text("rules")
			.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(5, Ttl.ONE_HOUR))))
			.build();
		List<Message> history = new ArrayList<>(List.of(system, user));
		JsonNode original = wire(history, stream);
		assertThat(original.at("/messages/0/content/0/cache_control/ttl").asString()).isEqualTo("1h");
		assertThat(original.at("/messages/1/content")).isEqualTo(this.json.readTree("""
				[{"type":"text","text":"prefix","cache_control":{"type":"ephemeral","ttl":"1h"}},
				 {"type":"text","text":"middle","cache_control":{"type":"ephemeral"}},
				 {"type":"text","text":" tail"},
				 {"type":"image_url","image_url":{"url":"https://example.test/image.png"}}]
				"""));
		history.add(AssistantMessage.builder()
			.content("")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}")))
			.build());
		history.add(ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "lookup", "result")))
			.build());
		history.add(new UserMessage("continue"));
		JsonNode replay = wire(history, stream);
		assertThat(replay.at("/messages/0")).isEqualTo(original.at("/messages/0"));
		assertThat(replay.at("/messages/1")).isEqualTo(original.at("/messages/1"));
		assertThat(replay.at("/messages/2/tool_calls/0/id").asString()).isEqualTo("call-1");
		assertThat(replay.at("/messages/3/tool_call_id").asString()).isEqualTo("call-1");
		assertThat(replay.at("/messages/3/content").asString()).isEqualTo("result");
		assertThat(replay.at("/messages/4/content").asString()).isEqualTo("continue");
		assertThat(replay.has("cache_control")).isFalse();
		assertThat(user.getText()).isEqualTo("prefixmiddle tail");
		assertThat(boundaries).containsExactly(new OpenRouterCacheBreakpoint(6, Ttl.ONE_HOUR),
				new OpenRouterCacheBreakpoint(12));
	}

	@Test
	void chatModelSendsTheSameCachedContentForCallsAndStreams() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any()))
			.thenReturn(new ChatCompletionResponse("synthetic", "chat.completion", 1L, "synthetic-model", null,
					List.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
					null));
		when(api.chatCompletionStream(any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt(user("reference question", List.of(new OpenRouterCacheBreakpoint(9))));

		model.call(prompt);
		StepVerifier.create(model.stream(prompt)).verifyComplete();

		ArgumentCaptor<ChatCompletionRequest> calls = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		ArgumentCaptor<ChatCompletionRequest> streams = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(calls.capture());
		verify(api).chatCompletionStream(streams.capture());
		assertThat(streams.getValue().messages()).isEqualTo(calls.getValue().messages());
		JsonNode sentContent = this.json.valueToTree(calls.getValue().messages().get(0).content());
		assertThat(sentContent).isEqualTo(this.json.readTree("""
				[{"type":"text","text":"reference","cache_control":{"type":"ephemeral"}},
				 {"type":"text","text":" question"}]
				"""));
	}

	@Test
	void responsesModelRejectsBreakpointsBeforeAnyRequestIsDispatched() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES).build())
			.build();
		Prompt prompt = new Prompt(user("reference question", List.of(new OpenRouterCacheBreakpoint(9))));

		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cache_control");
		StepVerifier.create(model.stream(prompt))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cache_control"))
			.verify();
		verifyNoInteractions(api);
	}

	static Stream<Arguments> malformedBreakpointMetadata() {
		String notBreakpoints = "Cache breakpoints must be a List<OpenRouterCacheBreakpoint>";
		String badOffsets = "Cache breakpoint offsets must increase within message text without splitting surrogate pairs";
		return Stream.of(Arguments.of(Named.of("not a list", "ephemeral"), notBreakpoints),
				Arguments.of(Named.of("list of non-breakpoints", List.of("bad")), notBreakpoints),
				Arguments.of(Named.of("offset beyond the text", List.of(new OpenRouterCacheBreakpoint(20))),
						badOffsets),
				Arguments.of(
						Named.of("duplicate offsets",
								List.of(new OpenRouterCacheBreakpoint(2), new OpenRouterCacheBreakpoint(2))),
						badOffsets),
				Arguments.of(
						Named.of("descending offsets",
								List.of(new OpenRouterCacheBreakpoint(3), new OpenRouterCacheBreakpoint(1))),
						badOffsets));
	}

	@ParameterizedTest
	@MethodSource("malformedBreakpointMetadata")
	void rejectsMalformedBreakpointMetadata(Object invalid, String message) {
		assertThatThrownBy(() -> wire(List.of(user("text", invalid)), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
	}

	@Test
	void rejectsOffsetsInsideASurrogatePair() {
		assertThatThrownBy(() -> wire(List.of(user("a😀b", List.of(new OpenRouterCacheBreakpoint(2)))), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate");
	}

	@Test
	void rejectsBreakpointsInEmptyText() {
		assertThatThrownBy(() -> wire(List.of(user("", List.of(new OpenRouterCacheBreakpoint(1)))), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("offsets must increase within message text");
	}

	@Test
	void breakpointValuesRejectNonPositiveOffsets() {
		assertThatThrownBy(() -> new OpenRouterCacheBreakpoint(0)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Cache breakpoint endIndex must be positive");
	}

	@Test
	void breakpointValuesRejectMissingTtl() {
		assertThatThrownBy(() -> new OpenRouterCacheBreakpoint(1, null)).isInstanceOf(NullPointerException.class)
			.hasMessage("ttl must not be null");
	}

	static Stream<Arguments> unsupportedRoles() {
		return Stream.of(
				Arguments.of(Named.of("assistant message",
						AssistantMessage.builder()
							.content("answer")
							.properties(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(1))))
							.build())),
				Arguments.of(Named.of("tool response message",
						ToolResponseMessage.builder()
							.responses(List.of(new ToolResponseMessage.ToolResponse("id", "lookup", "result")))
							.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(1))))
							.build())));
	}

	@ParameterizedTest
	@MethodSource("unsupportedRoles")
	void rejectsBreakpointsOnRolesOtherThanSystemAndUser(Message message) {
		assertThatThrownBy(() -> wire(List.of(message), false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("system and user");
	}

	@Test
	void acceptsExactlyFourBreakpointsInOneRequest() {
		Message cached = user("text", List.of(new OpenRouterCacheBreakpoint(4)));

		assertThat(wire(List.of(cached, cached, cached, cached), false).get("messages").size()).isEqualTo(4);
	}

	@Test
	void rejectsAFifthBreakpointInOneRequest() {
		Message cached = user("text", List.of(new OpenRouterCacheBreakpoint(4)));

		assertThatThrownBy(() -> wire(List.of(cached, cached, cached, cached, cached), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("four");
	}

	@Test
	void rejectsALongerTtlAfterAShorterOne() {
		Message cached = user("text", List.of(new OpenRouterCacheBreakpoint(4)));
		Message longer = user("text", List.of(new OpenRouterCacheBreakpoint(4, Ttl.ONE_HOUR)));

		assertThatThrownBy(() -> wire(List.of(cached, longer), false)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("precede");
	}

	static Stream<Arguments> responsesRejectedMessages() {
		var system = SystemMessage.builder()
			.text("rules")
			.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(5))))
			.build();
		var user = UserMessage.builder()
			.text("text")
			.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(4))))
			.build();
		return Stream.of(Named.of("user message", (Message) user), Named.of("system instructions", (Message) system))
			.flatMap(message -> Stream.of(Arguments.of(message, Named.of("blocking", false)),
					Arguments.of(message, Named.of("streaming", true))));
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("responsesRejectedMessages")
	void responsesRejectsBreakpointsIncludingSystemInstructions(Message message, boolean stream) {
		OpenRouterResponsesRequestMapper responses = new OpenRouterResponsesRequestMapper(this.json);
		assertThatThrownBy(() -> responses.map(List.of(message), this.options, stream, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("OPENAI_RESPONSES")
			.hasMessageContaining("cache_control");
	}

	@Test
	void unmarkedUserContentStaysAPlainString() {
		assertThat(wire(List.of(new UserMessage("text")), false).at("/messages/0/content").asString())
			.isEqualTo("text");
	}

	@Test
	void contentPartWithoutCacheControlOmitsTheField() {
		assertThat(this.json.writeValueAsString(new ContentPart("text", "text", null)))
			.isEqualTo("{\"type\":\"text\",\"text\":\"text\"}");
	}

	@Test
	void cacheControlDecodingToleratesUnknownFields() {
		ContentPart part = this.json.readValue("""
				{"type":"text","text":"text","cache_control":{"type":"ephemeral","ttl":"1h","future":true}}
				""", ContentPart.class);
		assertThat(part.cacheControl().type()).isEqualTo("ephemeral");
		assertThat(part.cacheControl().ttl()).isEqualTo("1h");
	}

	private UserMessage user(String text, Object breakpoints) {
		return UserMessage.builder().text(text).metadata(Map.of(METADATA_KEY, breakpoints)).build();
	}

	private JsonNode wire(List<Message> messages, boolean stream) {
		return this.json
			.readTree(this.json.writeValueAsString(this.mapper.map(messages, this.options, stream, List.of())));
	}

}
