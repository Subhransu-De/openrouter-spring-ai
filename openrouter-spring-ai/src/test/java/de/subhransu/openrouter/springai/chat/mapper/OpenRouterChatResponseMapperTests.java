package de.subhransu.openrouter.springai.chat.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;

class OpenRouterChatResponseMapperTests {

	@Test
	void supportedSynchronousToolCallTerminatorsProduceExecutableCalls() {
		for (String reason : List.of("tool_calls", "function_call")) {
			var tool = new ToolCall("call-1", "function", new FunctionCall("weather", "{}"));
			var response = new ChatCompletionResponse("gen-1", "chat.completion", 123L, "model", "provider", List
				.of(new Choice(0, new ChatMessage("assistant", null, null, null, List.of(tool)), null, reason, null)),
					null);
			ChatResponse mapped = new OpenRouterChatResponseMapper().map(response);
			assertThat(mapped.hasToolCalls()).isTrue();
			assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("TOOL_CALLS");
		}
	}

	@Test
	void unfinishedSynchronousToolCallsAreRejected() {
		for (String reason : Arrays.asList(null, "stop", "length", "content_filter")) {
			var tool = new ToolCall("call-1", "function", new FunctionCall("weather", "{}"));
			var response = new ChatCompletionResponse("gen-1", "chat.completion", 123L, "model", "provider", List
				.of(new Choice(0, new ChatMessage("assistant", null, null, null, List.of(tool)), null, reason, null)),
					null);
			assertThatThrownBy(() -> new OpenRouterChatResponseMapper().map(response))
				.isInstanceOf(OpenRouterTruncatedResponseException.class);
		}
	}

	@Test
	void blankToolCallNameFailsProtocolValidation() {
		for (String name : List.of("", "  ")) {
			var tool = new ToolCall("call-1", "function", new FunctionCall(name, "{\"job\":\"a\"}"));
			var response = new ChatCompletionResponse(
					"gen-1", "chat.completion", 123L, "model", "provider", List.of(new Choice(0,
							new ChatMessage("assistant", null, null, null, List.of(tool)), null, "tool_calls", null)),
					null);
			assertThatThrownBy(() -> new OpenRouterChatResponseMapper().map(response))
				.isInstanceOf(OpenRouterProtocolException.class)
				.hasMessageContaining("nonblank tool call name");
		}
	}

	@Test
	void mapsResponseContentMetadataAndUsage() {
		ChatCompletionResponse response = new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"openai/gpt-5.4-mini", "openai",
				List.of(new Choice(0, new ChatMessage("assistant", "hello", null, null, null), null, "stop", "stop")),
				new Usage(10, 5, 15, 2, 1, 0.001, null, null, null));

		ChatResponse mapped = new OpenRouterChatResponseMapper().map(response);

		assertThat(mapped.getResult().getOutput().getText()).isEqualTo("hello");
		assertThat(mapped.getResult().getMetadata().getFinishReason()).isEqualTo("STOP");
		assertThat(mapped.getMetadata().getId()).isEqualTo("gen-1");
		assertThat(mapped.getMetadata().getModel()).isEqualTo("openai/gpt-5.4-mini");
		assertThat(mapped.getMetadata().getUsage()).isInstanceOf(OpenRouterUsage.class);
		assertThat(((OpenRouterUsage) mapped.getMetadata().getUsage()).getCachedTokens()).isEqualTo(2);
	}

	@Test
	void emptyChoicesListFailsProtocolValidation() {
		ChatCompletionResponse response = new ChatCompletionResponse("gen-empty", "chat.completion", 1L, "m", "p",
				List.of(), null);

		assertThatThrownBy(() -> new OpenRouterChatResponseMapper().map(response))
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	@Test
	void nullChoicesListFailsProtocolValidation() {
		ChatCompletionResponse response = new ChatCompletionResponse("gen-null", "chat.completion", 1L, "m", "p", null,
				null);

		assertThatThrownBy(() -> new OpenRouterChatResponseMapper().map(response))
			.isInstanceOf(OpenRouterProtocolException.class);
	}

	@Test
	void readsCachedAndReasoningTokensFromNestedUsageDetails() {
		Usage usage = new Usage(10, 5, 15, null, null, 0.001, new Usage.PromptTokensDetails(3),
				new Usage.CompletionTokensDetails(4), null);
		ChatCompletionResponse response = new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"openai/gpt-5.4-mini", "openai",
				List.of(new Choice(0, new ChatMessage("assistant", "hello", null, null, null), null, "stop", "stop")),
				usage);

		ChatResponse mapped = new OpenRouterChatResponseMapper().map(response);

		OpenRouterUsage mappedUsage = (OpenRouterUsage) mapped.getMetadata().getUsage();
		assertThat(mappedUsage.getCachedTokens()).isEqualTo(3);
		assertThat(mappedUsage.getReasoningTokens()).isEqualTo(4);
	}

}
