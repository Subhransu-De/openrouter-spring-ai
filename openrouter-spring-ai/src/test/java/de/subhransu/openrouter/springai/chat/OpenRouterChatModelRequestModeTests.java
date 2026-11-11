package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * Request-mode dispatch of {@link OpenRouterChatModel}: for each combination of
 * configured default mode and per-request options, blocking calls and streams must reach
 * the endpoint of the resolved mode, set the stream flag accordingly and leave the other
 * endpoints untouched.
 */
class OpenRouterChatModelRequestModeTests {

	private static final String DEFAULT_MODEL = "default/model";

	private static final String RUNTIME_MODEL = "runtime/model";

	private static final String CHAT_TEXT = "from chat completions";

	private static final String RESPONSES_TEXT = "from responses";

	static Stream<Arguments> dispatchCases() {
		var chatDefault = options(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, DEFAULT_MODEL);
		var responsesDefault = options(OpenRouterRequestMode.OPENAI_RESPONSES, DEFAULT_MODEL);
		var chatRuntime = options(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, RUNTIME_MODEL);
		var responsesRuntime = options(OpenRouterRequestMode.OPENAI_RESPONSES, RUNTIME_MODEL);
		var unsetRuntime = options(null, RUNTIME_MODEL);
		return Stream.of(
				new DispatchCase("no defaults, runtime without a mode", null, unsetRuntime, Endpoint.CHAT,
						RUNTIME_MODEL),
				new DispatchCase("no defaults, runtime chat", null, chatRuntime, Endpoint.CHAT, RUNTIME_MODEL),
				new DispatchCase("no defaults, runtime responses", null, responsesRuntime, Endpoint.RESPONSES,
						RUNTIME_MODEL),
				new DispatchCase("chat default, no runtime options", chatDefault, null, Endpoint.CHAT, DEFAULT_MODEL),
				new DispatchCase("responses default, no runtime options", responsesDefault, null, Endpoint.RESPONSES,
						DEFAULT_MODEL),
				new DispatchCase("responses default, runtime without a mode replaces it", responsesDefault,
						unsetRuntime, Endpoint.CHAT, RUNTIME_MODEL),
				new DispatchCase("responses default, runtime chat overrides it", responsesDefault, chatRuntime,
						Endpoint.CHAT, RUNTIME_MODEL),
				new DispatchCase("chat default, runtime responses overrides it", chatDefault, responsesRuntime,
						Endpoint.RESPONSES, RUNTIME_MODEL))
			.flatMap(dispatch -> Stream.of(false, true)
				.map(streaming -> Arguments
					.of(Named.of(dispatch.name() + (streaming ? ", streaming" : ", blocking"), dispatch), streaming)));
	}

	@ParameterizedTest
	@MethodSource("dispatchCases")
	void dispatchesToTheEndpointOfTheResolvedRequestMode(DispatchCase dispatch, boolean streaming) {
		OpenRouterApi api = stubbedApi();
		var builder = OpenRouterChatModel.builder().openRouterApi(api);
		var model = (dispatch.defaults() != null ? builder.defaultOptions(dispatch.defaults()) : builder).build();
		Prompt prompt = dispatch.runtime() != null ? new Prompt(List.of(new UserMessage("hello")), dispatch.runtime())
				: new Prompt(List.of(new UserMessage("hello")));

		String expectedText = dispatch.endpoint() == Endpoint.CHAT ? CHAT_TEXT : RESPONSES_TEXT;
		if (streaming) {
			StepVerifier.create(model.stream(prompt).map(response -> response.getResult().getOutput().getText()))
				.expectNext(expectedText)
				.verifyComplete();
		}
		else {
			assertThat(model.call(prompt).getResult().getOutput().getText()).isEqualTo(expectedText);
		}

		dispatch.endpoint().verifyOnlyRequest(api, streaming, dispatch.expectedModel());
	}

	private static OpenRouterApi stubbedApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(new ChatCompletionResponse("gen-1", "chat.completion", 123L,
				"synthetic-model", "synthetic",
				List.of(new Choice(0, new ChatMessage("assistant", CHAT_TEXT, null, null, null), null, "stop", "stop")),
				null));
		when(api.chatCompletionStream(any())).thenReturn(Flux
			.just(new ChatCompletionChunk("gen-1", "chat.completion.chunk", 123L, "synthetic-model", "synthetic",
					List.of(new Choice(0, null, new Delta("assistant", CHAT_TEXT, null, null), "stop", "stop")), null,
					null)));
		when(api.responses(any())).thenReturn(new ResponsesResult("resp-1", "response", 123L, "synthetic-model",
				"completed", List.of(new ResponsesOutputItem("item-1", "message", "completed", "assistant",
						List.of(new ResponsesContent("output_text", RESPONSES_TEXT)))),
				null, null));
		when(api.responsesStream(any())).thenReturn(
				Flux.just(new ResponsesStreamEvent("response.output_text.delta", RESPONSES_TEXT, null, null, null)));
		return api;
	}

	private static OpenRouterChatOptions options(OpenRouterRequestMode mode, String model) {
		return OpenRouterChatOptions.builder().model(model).requestMode(mode).build();
	}

	enum Endpoint {

		CHAT {
			@Override
			void verifyOnlyRequest(OpenRouterApi api, boolean streaming, String model) {
				ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
				if (streaming) {
					verify(api).chatCompletionStream(captor.capture());
				}
				else {
					verify(api).chatCompletion(captor.capture());
				}
				assertThat(captor.getValue().model()).isEqualTo(model);
				assertThat(captor.getValue().stream()).isEqualTo(streaming);
				verifyNoMoreInteractions(api);
			}
		},
		RESPONSES {
			@Override
			void verifyOnlyRequest(OpenRouterApi api, boolean streaming, String model) {
				ArgumentCaptor<ResponsesRequest> captor = ArgumentCaptor.forClass(ResponsesRequest.class);
				if (streaming) {
					verify(api).responsesStream(captor.capture());
				}
				else {
					verify(api).responses(captor.capture());
				}
				assertThat(captor.getValue().model()).isEqualTo(model);
				assertThat(captor.getValue().stream()).isEqualTo(streaming);
				verifyNoMoreInteractions(api);
			}
		};

		abstract void verifyOnlyRequest(OpenRouterApi api, boolean streaming, String model);

	}

	record DispatchCase(String name, OpenRouterChatOptions defaults, OpenRouterChatOptions runtime, Endpoint endpoint,
			String expectedModel) {
	}

}
