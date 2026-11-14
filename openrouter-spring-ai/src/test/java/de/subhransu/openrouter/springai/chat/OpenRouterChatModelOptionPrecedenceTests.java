package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

@Tag("api")
@Tag("api.options")
class OpenRouterChatModelOptionPrecedenceTests {

	private static final String CLAUDE_MODEL = "anthropic/claude-sonnet-4";

	private static final String OPENAI_MODEL = "openai/gpt-5.4";

	@Test
	void nullRuntimeOptionsUseDetachedStartupDefaults() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any())).thenReturn(responsesResult("startup response"));
		List<String> startupModels = new ArrayList<>(List.of(OPENAI_MODEL));
		Map<String, Object> startupMetadata = new LinkedHashMap<>(Map.of("source", "startup"));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.options(OpenRouterChatOptions.builder()
				.model(OPENAI_MODEL)
				.models(startupModels)
				.requestMode(OpenRouterRequestMode.RESPONSES)
				.metadata(startupMetadata)
				.build())
			.build();
		startupModels.add(CLAUDE_MODEL);
		startupMetadata.put("mutated", true);

		model.call(new Prompt(List.of(new UserMessage("hello"))));

		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api).responses(captor.capture());
		assertThat(captor.getValue().model()).isEqualTo(OPENAI_MODEL);
		assertThat(captor.getValue().models()).containsExactly(OPENAI_MODEL);
		assertThat(captor.getValue().metadata()).containsExactlyEntriesOf(Map.of("source", "startup"));
	}

	@Test
	void suppliedOpenRouterOptionsReplaceStartupProviderModelAndMode() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(chatCompletionResponse());
		OpenRouterChatModel model = modelWithConflictingStartupDefaults(api);

		model.call(new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model(CLAUDE_MODEL).temperature(0.3).build()));

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		ChatCompletionRequest request = captor.getValue();
		assertThat(request.model()).isEqualTo(CLAUDE_MODEL);
		assertThat(request.temperature()).isEqualTo(0.3);
		assertThat(request.provider()).isNull();
		assertThat(request.metadata()).isNull();
	}

	@Test
	void genericRuntimeOptionsReplaceStartupDefaultsAndPreservePortableFields() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(chatCompletionResponse());
		OpenRouterChatModel model = modelWithConflictingStartupDefaults(api);
		List<String> stopSequences = new ArrayList<>(List.of("DONE"));
		ChatOptions runtime = ChatOptions.builder()
			.model(CLAUDE_MODEL)
			.temperature(0.4)
			.topP(0.8)
			.topK(20)
			.maxTokens(256)
			.frequencyPenalty(0.1)
			.presencePenalty(0.2)
			.stopSequences(stopSequences)
			.build();
		stopSequences.add("MUTATED");

		model.call(new Prompt(List.of(new UserMessage("hello")), runtime));

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		ChatCompletionRequest request = captor.getValue();
		assertThat(request.model()).isEqualTo(CLAUDE_MODEL);
		assertThat(request.temperature()).isEqualTo(0.4);
		assertThat(request.topP()).isEqualTo(0.8);
		assertThat(request.topK()).isEqualTo(20);
		assertThat(request.maxTokens()).isEqualTo(256);
		assertThat(request.frequencyPenalty()).isEqualTo(0.1);
		assertThat(request.presencePenalty()).isEqualTo(0.2);
		assertThat(request.stop()).containsExactly("DONE");
		assertThat(request.provider()).isNull();
		assertThat(request.metadata()).isNull();
	}

	@Test
	void runtimeCollectionsRemainReadOnlyAfterMapping() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(chatCompletionResponse());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder()
			.model(CLAUDE_MODEL)
			.models(List.of("anthropic/claude-3.7-sonnet"))
			.metadata(Map.of("request", "caller"))
			.build();

		model.call(new Prompt(List.of(new UserMessage("hello")), runtime));

		ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api).chatCompletion(captor.capture());
		assertThatThrownBy(() -> captor.getValue().models().add(OPENAI_MODEL))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> captor.getValue().metadata().put("mapped", true))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(runtime.getModels()).containsExactly("anthropic/claude-3.7-sonnet");
		assertThat(runtime.getMetadata()).containsExactlyEntriesOf(Map.of("request", "caller"));
	}

	@Test
	void chatClientComposesItsDeltaIntoACompleteRuntimeOptionSet() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any())).thenReturn(responsesResult("client response"));
		OpenRouterChatModel model = modelWithConflictingStartupDefaults(api);
		ChatClient client = ChatClient.builder(model)
			.defaultOptions(OpenRouterChatOptions.builder().temperature(0.5))
			.build();
		Prompt prompt = new Prompt(List.of(new UserMessage("hello")), ChatOptions.builder().topP(0.7).build());

		client.prompt(prompt).call().chatResponse();

		ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
		verify(api).responses(captor.capture());
		ResponsesRequest request = captor.getValue();
		assertThat(request.model()).isEqualTo(OPENAI_MODEL);
		assertThat(request.temperature()).isEqualTo(0.5);
		assertThat(request.topP()).isEqualTo(0.7);
		assertThat(request.provider().order()).containsExactly("openai");
		assertThat(request.metadata()).containsExactlyEntriesOf(Map.of("source", "startup"));
	}

	private OpenRouterChatModel modelWithConflictingStartupDefaults(OpenRouterApi api) {
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.options(OpenRouterChatOptions.builder()
				.model(OPENAI_MODEL)
				.requestMode(OpenRouterRequestMode.RESPONSES)
				.provider(new OpenRouterProviderPreferences(true, null, null, List.of("openai"), null, null, null))
				.metadata(Map.of("source", "startup"))
				.build())
			.build();
	}

	private ChatCompletionResponse chatCompletionResponse() {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, CLAUDE_MODEL, "anthropic",
				List.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
				null);
	}

	private ResponsesResult responsesResult(String content) {
		return new ResponsesResult(
				"resp-1", "response", 123L, OPENAI_MODEL, "completed", List.of(new ResponsesOutputItem("item-1",
						"message", "completed", "assistant", List.of(new ResponsesContent("output_text", content)))),
				null, null);
	}

}
