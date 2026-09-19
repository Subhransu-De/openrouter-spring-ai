package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import java.util.List;
import java.util.Map;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class OpenRouterChatModelRequestModeTests {

	@Test
	void cacheBreakpointsReachCallsAndStreamsAndFailBeforeResponsesDispatch() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any()))
			.thenReturn(new ChatCompletionResponse("synthetic", "chat.completion", 1L, "synthetic-model", null,
					List.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
					null));
		when(api.chatCompletionStream(any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		UserMessage message = UserMessage.builder()
			.text("reference question")
			.metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY, List.of(new OpenRouterCacheBreakpoint(9))))
			.build();
		Prompt prompt = new Prompt(message);
		model.call(prompt);
		StepVerifier.create(model.stream(prompt)).verifyComplete();
		ArgumentCaptor<ChatCompletionRequest> calls = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		ArgumentCaptor<ChatCompletionRequest> streams = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(calls.capture());
		verify(api).chatCompletionStream(streams.capture());
		assertThat(calls.getValue().messages()).isEqualTo(streams.getValue().messages());
		assertThat((List<?>) calls.getValue().messages().get(0).content()).hasSize(2);
		ContentPart prefix = (ContentPart) ((List<?>) calls.getValue().messages().get(0).content()).get(0);
		assertThat(prefix.cacheControl().type()).isEqualTo("ephemeral");

		OpenRouterApi responsesApi = mock(OpenRouterApi.class);
		OpenRouterChatModel responsesModel = OpenRouterChatModel.builder()
			.openRouterApi(responsesApi)
			.defaultOptions(OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES).build())
			.build();
		assertThatThrownBy(() -> responsesModel.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cache_control");
		StepVerifier.create(responsesModel.stream(prompt)).expectError(IllegalArgumentException.class).verify();
		verifyNoInteractions(responsesApi);
	}

	@Test
	void callDispatchesToChatCompletionsByDefault() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any()))
			.thenReturn(new ChatCompletionResponse(
					"gen-1", "chat.completion", 123L, "openai/gpt-5.4-mini", "openai", List.of(new Choice(0,
							new ChatMessage("assistant", "chat response", null, null, null), null, "stop", "stop")),
					null));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		ChatResponse response = model.call(new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model("openai/gpt-5.4-mini").build()));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("chat response");
		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo("openai/gpt-5.4-mini");
		assertThat(captor.getValue().stream()).isFalse();
		// Default mode must not touch the responses endpoint.
		verify(api, never()).responses(any());
	}

	@Test
	void callDispatchesToOpenAiResponsesMode() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(
				any()))
			.thenReturn(new ResponsesResult("resp-1", "response", 123L, "openai/gpt-5.4", "completed",
					List.of(new ResponsesOutputItem("item-1", "message", "completed", "assistant",
							List.of(new ResponsesContent("output_text", "responses response")))),
					null, null));
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		ChatResponse response = model.call(new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder()
					.model("openai/gpt-5.4")
					.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
					.build()));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("responses response");
		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api).responses(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo("openai/gpt-5.4");
		assertThat(captor.getValue().stream()).isFalse();
		// Responses mode must not touch the chat-completions endpoint.
		verify(api, never()).chatCompletion(any());
	}

	@Test
	void runtimeOptionsWithoutModeReplaceConfiguredDefaultMode() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"anthropic/claude-sonnet-4", "anthropic", List.of(new Choice(0,
						new ChatMessage("assistant", "from chat completions", null, null, null), null, "stop", "stop")),
				null));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model("openai/gpt-5.4")
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.build())
			.build();

		ChatResponse response = model.call(new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model("anthropic/claude-sonnet-4").temperature(0.1).build()));

		assertThat(response.getResult().getOutput().getText()).isEqualTo("from chat completions");
		// A supplied option set replaces model defaults. Its absent request mode resolves
		// independently to chat completions instead of inheriting Responses mode.
		verify(api).chatCompletion(any(ChatCompletionRequest.class));
		verify(api, never()).responses(any());
	}

}
