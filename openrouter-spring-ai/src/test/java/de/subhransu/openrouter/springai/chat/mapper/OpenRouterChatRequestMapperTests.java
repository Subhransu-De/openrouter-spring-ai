package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

@Tag("api")
@Tag("api.request")
class OpenRouterChatRequestMapperTests {

	private final OpenRouterChatRequestMapper mapper = new OpenRouterChatRequestMapper(new ObjectMapper());

	@Test
	void mapsSpringAiMessagesToOpenRouterChatMessages() {
		AssistantMessage assistantMessage = AssistantMessage.builder()
			.content("calling tool")
			.toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "weather", "{\"city\":\"Pune\"}")))
			.build();
		ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
			.responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "weather", "28C")))
			.build();

		ChatCompletionRequest request = this.mapper.map(
				List.of(new SystemMessage("system"), new UserMessage("hello"), assistantMessage, toolResponseMessage),
				OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini").build(), false, List.of());

		assertThat(request.model()).isEqualTo("openai/gpt-5.4-mini");
		assertThat(request.messages()).extracting("role").containsExactly("system", "user", "assistant", "tool");
		assertThat(request.messages().get(2).toolCalls()).hasSize(1);
		assertThat(request.messages().get(3).toolCallId()).isEqualTo("call-1");
		assertThat(request.stream()).isFalse();
	}

}
