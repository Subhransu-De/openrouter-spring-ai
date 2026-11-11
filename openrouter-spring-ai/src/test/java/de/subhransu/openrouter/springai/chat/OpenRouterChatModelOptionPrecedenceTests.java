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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

class OpenRouterChatModelOptionPrecedenceTests {

	private static final String CLAUDE_MODEL = "anthropic/claude-sonnet-4";

	private static final String OPENAI_MODEL = "openai/gpt-5.4";

	private static final Duration WAIT = Duration.ofSeconds(5);

	@ParameterizedTest
	@EnumSource(Delivery.class)
	void nullRuntimeOptionsUseDetachedStartupDefaults(Delivery delivery) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		delivery.stubResponses(api, "startup response");
		List<String> startupModels = new ArrayList<>(List.of(OPENAI_MODEL));
		Map<String, Object> startupMetadata = new LinkedHashMap<>(Map.of("source", "startup"));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model(OPENAI_MODEL)
				.models(startupModels)
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.metadata(startupMetadata)
				.build())
			.build();
		startupModels.add(CLAUDE_MODEL);
		startupMetadata.put("mutated", true);

		delivery.send(model, new Prompt(List.of(new UserMessage("hello"))));

		ResponsesRequest request = delivery.sentResponses(api);
		assertThat(request.model()).isEqualTo(OPENAI_MODEL);
		assertThat(request.models()).containsExactly(OPENAI_MODEL);
		assertThat(request.metadata()).containsExactlyEntriesOf(Map.of("source", "startup"));
	}

	@ParameterizedTest
	@EnumSource(Delivery.class)
	void suppliedOpenRouterOptionsReplaceStartupProviderModelAndMode(Delivery delivery) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		delivery.stubChat(api);
		OpenRouterChatModel model = modelWithConflictingStartupDefaults(api);

		delivery.send(model, new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model(CLAUDE_MODEL).temperature(0.3).build()));

		ChatCompletionRequest request = delivery.sentChat(api);
		assertThat(request.model()).isEqualTo(CLAUDE_MODEL);
		assertThat(request.temperature()).isEqualTo(0.3);
		assertThat(request.provider()).isNull();
		assertThat(request.metadata()).isNull();
	}

	@ParameterizedTest
	@EnumSource(Delivery.class)
	void genericRuntimeOptionsReplaceStartupDefaultsAndPreservePortableFields(Delivery delivery) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		delivery.stubChat(api);
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

		delivery.send(model, new Prompt(List.of(new UserMessage("hello")), runtime));

		ChatCompletionRequest request = delivery.sentChat(api);
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

	@ParameterizedTest
	@EnumSource(Delivery.class)
	void mappedRequestIsDetachedFromTheCallersCollections(Delivery delivery) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		delivery.stubChat(api);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		List<String> fallbacks = new ArrayList<>(List.of("anthropic/claude-3.7-sonnet"));
		Map<String, Object> metadata = new LinkedHashMap<>(Map.of("request", "caller"));
		Prompt prompt = new Prompt(List.of(new UserMessage("hello")),
				OpenRouterChatOptions.builder().model(CLAUDE_MODEL).models(fallbacks).metadata(metadata).build());

		delivery.send(model, prompt);
		fallbacks.add(OPENAI_MODEL);
		metadata.put("later", true);

		ChatCompletionRequest request = delivery.sentChat(api);
		assertThat(request.models()).containsExactly("anthropic/claude-3.7-sonnet");
		assertThat(request.metadata()).containsExactlyEntriesOf(Map.of("request", "caller"));
		assertThatThrownBy(() -> request.models().add(OPENAI_MODEL)).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> request.metadata().put("mapped", true))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@EnumSource(Delivery.class)
	void chatClientComposesItsDeltaIntoACompleteRuntimeOptionSet(Delivery delivery) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		delivery.stubResponses(api, "client response");
		OpenRouterChatModel model = modelWithConflictingStartupDefaults(api);
		ChatClient client = ChatClient.builder(model)
			.defaultOptions(OpenRouterChatOptions.builder().temperature(0.5))
			.build();
		Prompt prompt = new Prompt(List.of(new UserMessage("hello")), ChatOptions.builder().topP(0.7).build());

		delivery.send(client, prompt);

		ResponsesRequest request = delivery.sentResponses(api);
		assertThat(request.model()).isEqualTo(OPENAI_MODEL);
		assertThat(request.temperature()).isEqualTo(0.5);
		assertThat(request.topP()).isEqualTo(0.7);
		assertThat(request.provider().order()).containsExactly("openai");
		assertThat(request.metadata()).containsExactlyEntriesOf(Map.of("source", "startup"));
	}

	private OpenRouterChatModel modelWithConflictingStartupDefaults(OpenRouterApi api) {
		return OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(OpenRouterChatOptions.builder()
				.model(OPENAI_MODEL)
				.requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
				.provider(new OpenRouterProviderPreferences(true, null, null, List.of("openai"), null, null, null))
				.metadata(Map.of("source", "startup"))
				.build())
			.build();
	}

	private static ChatCompletionResponse chatCompletionResponse() {
		return new ChatCompletionResponse("gen-1", "chat.completion", 123L, CLAUDE_MODEL, "anthropic",
				List.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
				null);
	}

	private static ResponsesResult responsesResult(String content) {
		return new ResponsesResult(
				"resp-1", "response", 123L, OPENAI_MODEL, "completed", List.of(new ResponsesOutputItem("item-1",
						"message", "completed", "assistant", List.of(new ResponsesContent("output_text", content)))),
				null, null);
	}

	/**
	 * How a prompt reaches the model. Both deliveries must resolve options the same way,
	 * so every precedence test runs once per delivery.
	 */
	enum Delivery {

		BLOCKING, STREAMING;

		void stubChat(OpenRouterApi api) {
			if (this == BLOCKING) {
				when(api.chatCompletion(any())).thenReturn(chatCompletionResponse());
			}
			else {
				when(api.chatCompletionStream(any())).thenReturn(Flux.empty());
			}
		}

		void stubResponses(OpenRouterApi api, String content) {
			if (this == BLOCKING) {
				when(api.responses(any())).thenReturn(responsesResult(content));
			}
			else {
				when(api.responsesStream(any())).thenReturn(Flux.empty());
			}
		}

		ChatCompletionRequest sentChat(OpenRouterApi api) {
			ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
			if (this == BLOCKING) {
				verify(api).chatCompletion(captor.capture());
			}
			else {
				verify(api).chatCompletionStream(captor.capture());
			}
			return captor.getValue();
		}

		ResponsesRequest sentResponses(OpenRouterApi api) {
			ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
			if (this == BLOCKING) {
				verify(api).responses(captor.capture());
			}
			else {
				verify(api).responsesStream(captor.capture());
			}
			return captor.getValue();
		}

		void send(OpenRouterChatModel model, Prompt prompt) {
			if (this == BLOCKING) {
				model.call(prompt);
			}
			else {
				model.stream(prompt).collectList().block(WAIT);
			}
		}

		void send(ChatClient client, Prompt prompt) {
			if (this == BLOCKING) {
				client.prompt(prompt).call().chatResponse();
			}
			else {
				client.prompt(prompt).stream().chatResponse().collectList().block(WAIT);
			}
		}

	}

}
