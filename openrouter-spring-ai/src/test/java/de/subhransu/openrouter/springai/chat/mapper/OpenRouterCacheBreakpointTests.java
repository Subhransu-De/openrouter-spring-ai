package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint.METADATA_KEY;

import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint;
import de.subhransu.openrouter.springai.chat.OpenRouterCacheBreakpoint.Ttl;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
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
		assertThat(original.at("/messages/0/content/0/cache_control/ttl").asText()).isEqualTo("1h");
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
		assertThat(replay.at("/messages/2/tool_calls/0/id").asText()).isEqualTo("call-1");
		assertThat(replay.at("/messages/3/tool_call_id").asText()).isEqualTo("call-1");
		assertThat(replay.at("/messages/3/content").asText()).isEqualTo("result");
		assertThat(replay.at("/messages/4/content").asText()).isEqualTo("continue");
		assertThat(replay.has("cache_control")).isFalse();
		assertThat(user.getText()).isEqualTo("prefixmiddle tail");
		assertThat(boundaries).containsExactly(new OpenRouterCacheBreakpoint(6, Ttl.ONE_HOUR),
				new OpenRouterCacheBreakpoint(12));
	}

	@Test
	void rejectsMalformedMetadataOffsetsAndUnsupportedRoles() {
		for (Object invalid : List.of("ephemeral", List.of("bad"), List.of(new OpenRouterCacheBreakpoint(20)),
				List.of(new OpenRouterCacheBreakpoint(2), new OpenRouterCacheBreakpoint(2)),
				List.of(new OpenRouterCacheBreakpoint(3), new OpenRouterCacheBreakpoint(1)))) {
			assertThatThrownBy(() -> wire(List.of(user("text", invalid)), false))
				.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(
				() -> wire(List.of(user("a\uD83D\uDE00b", List.of(new OpenRouterCacheBreakpoint(2)))), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate");
		assertThatThrownBy(() -> wire(List.of(user("", List.of(new OpenRouterCacheBreakpoint(1)))), false))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenRouterCacheBreakpoint(0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenRouterCacheBreakpoint(1, null)).isInstanceOf(NullPointerException.class);
		List<Message> unsupported = List.of(
				AssistantMessage.builder()
					.content("answer")
					.properties(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(1))))
					.build(),
				ToolResponseMessage.builder()
					.responses(List.of(new ToolResponseMessage.ToolResponse("id", "lookup", "result")))
					.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(1))))
					.build());
		for (Message message : unsupported) {
			assertThatThrownBy(() -> wire(List.of(message), false)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("system and user");
		}
	}

	@Test
	void enforcesRequestWideLimitAndTtlOrder() {
		Message cached = user("text", List.of(new OpenRouterCacheBreakpoint(4)));
		assertThat(wire(List.of(cached, cached, cached, cached), false).get("messages").size()).isEqualTo(4);
		assertThatThrownBy(() -> wire(List.of(cached, cached, cached, cached, cached), false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("four");
		assertThatThrownBy(
				() -> wire(List.of(cached, user("text", List.of(new OpenRouterCacheBreakpoint(4, Ttl.ONE_HOUR)))),
						false))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("precede");
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void responsesRejectsBreakpointsIncludingSystemInstructions(boolean stream) {
		OpenRouterResponsesRequestMapper responses = new OpenRouterResponsesRequestMapper(this.json);
		for (Message message : List.of(user("text", List.of(new OpenRouterCacheBreakpoint(4))),
				SystemMessage.builder()
					.text("rules")
					.metadata(Map.of(METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(5))))
					.build())) {
			assertThatThrownBy(() -> responses.map(List.of(message), this.options, stream, List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("OPENAI_RESPONSES", "cache_control");
		}
	}

	@Test
	void unmarkedContentRetainsWireCompatibilityAndUnknownFieldsAreTolerated() {
		assertThat(wire(List.of(new UserMessage("text")), false).at("/messages/0/content").asText()).isEqualTo("text");
		assertThat(this.json.writeValueAsString(new ContentPart("text", "text", null)))
			.isEqualTo("{\"type\":\"text\",\"text\":\"text\"}");
		ContentPart part = this.json.readValue("""
				{"type":"text","text":"text","cache_control":{"type":"ephemeral","ttl":"1h","future":true}}
				""", ContentPart.class);
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
