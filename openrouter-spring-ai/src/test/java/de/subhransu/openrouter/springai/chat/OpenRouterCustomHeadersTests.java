package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@Tag("api")
@Tag("api.options")
class OpenRouterCustomHeadersTests {

	private static final Map<String, String> HEADERS = Map.of("X-Request-Tag", "run-7");

	@Test
	void aRequestHeaderReplacesTheDefaultWhateverItsCaseAndOthersAreKept() {
		OpenRouterChatOptions defaults = OpenRouterChatOptions.builder()
			.customHeaders(orderedMap("X-Request-Tag", "default", "X-Tenant", "acme"))
			.build();
		OpenRouterChatOptions runtime = OpenRouterChatOptions.builder()
			.customHeaders(orderedMap("x-request-tag", "run-7", "X-Feature", "beta"))
			.build();

		Map<String, String> merged = defaults.merge(runtime).getCustomHeaders();

		assertThat(merged).hasSize(3)
			.containsEntry("X-Request-Tag", "run-7")
			.containsEntry("X-Tenant", "acme")
			.containsEntry("X-Feature", "beta");
		assertThat(defaults.merge(OpenRouterChatOptions.builder().build()).getCustomHeaders())
			.isEqualTo(defaults.getCustomHeaders());
		assertThat(OpenRouterChatOptions.builder().build().merge(runtime).getCustomHeaders())
			.isEqualTo(runtime.getCustomHeaders());
	}

	@Test
	void builtOptionsKeepTheirOwnReadOnlyCopy() {
		Map<String, String> source = new HashMap<>(HEADERS);
		OpenRouterChatOptions options = OpenRouterChatOptions.builder().customHeaders(source).build();
		source.put("X-Request-Tag", "changed");

		assertThat(options.getCustomHeaders()).containsExactlyEntriesOf(HEADERS);
		assertThat(options.mutate().build().getCustomHeaders()).containsExactlyEntriesOf(HEADERS);
		assertThatThrownBy(() -> options.getCustomHeaders().put("X-Other", "value"))
			.isInstanceOf(UnsupportedOperationException.class);
		assertThat(OpenRouterChatOptions.builder().build().getCustomHeaders()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Authorization", "authorization", "Content-Type", "Accept", "HTTP-Referer",
			"X-OpenRouter-Title", "x-openrouter-categories" })
	void rejectsHeadersThatTheClientSetsItself(String name) {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterChatOptions.builder().customHeaders(Map.of(name, "secret-value")))
			.withMessageContaining(name)
			.withMessageContaining("sets itself")
			.withMessageNotContaining("secret-value");
	}

	@Test
	void rejectsBlankNamesAndNullValuesWithoutEchoingValues() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterChatOptions.builder().customHeaders(Map.of(" ", "secret-value")))
			.withMessage("customHeaders must not contain a blank header name");
		Map<String, String> nullValue = new HashMap<>();
		nullValue.put("X-Request-Tag", null);
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterChatOptions.builder().customHeaders(nullValue))
			.withMessage("customHeaders X-Request-Tag must not have a null value");
	}

	@Tag("api.request")
	@Test
	void chatCompletionsCallsAndStreamsSendTheHeaders() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any(), any())).thenReturn(chatResponse());
		when(api.chatCompletionStream(any(), any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt("hello", OpenRouterChatOptions.builder().customHeaders(HEADERS).build());

		model.call(prompt);
		StepVerifier.create(model.stream(prompt)).verifyComplete();

		verify(api).chatCompletion(any(ChatCompletionRequest.class), eq(HEADERS));
		verify(api).chatCompletionStream(any(ChatCompletionRequest.class), eq(HEADERS));
		verify(api, never()).chatCompletion(any());
		verify(api, never()).chatCompletionStream(any());
	}

	@Tag("api.request")
	@Test
	void responsesCallsAndStreamsSendTheHeaders() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.responses(any(), any())).thenReturn(responsesResult());
		when(api.responsesStream(any(), any())).thenReturn(Flux.empty());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();
		Prompt prompt = new Prompt("hello",
				OpenRouterChatOptions.builder()
					.requestMode(OpenRouterRequestMode.RESPONSES)
					.customHeaders(HEADERS)
					.build());

		model.call(prompt);
		StepVerifier.create(model.stream(prompt)).verifyComplete();

		verify(api).responses(any(ResponsesRequest.class), eq(HEADERS));
		verify(api).responsesStream(any(ResponsesRequest.class), eq(HEADERS));
		verify(api, never()).responses(any());
		verify(api, never()).responsesStream(any());
	}

	@Tag("api.request")
	@Test
	void requestsWithoutHeadersUseTheOneArgumentApiMethods() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletion(any())).thenReturn(chatResponse());
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		model.call(new Prompt("hello", OpenRouterChatOptions.builder().customHeaders(Map.of()).build()));

		verify(api).chatCompletion(any(ChatCompletionRequest.class));
		verify(api, never()).chatCompletion(any(), any());
	}

	@Test
	void anInvalidHeaderFailsBeforeAnyRequest() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder().openRouterApi(api).build();

		assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("hello",
				OpenRouterChatOptions.builder().customHeaders(Map.of("Authorization", "Bearer other")).build())));
		verifyNoInteractions(api);
	}

	private static Map<String, String> orderedMap(String... entries) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < entries.length; i += 2) {
			map.put(entries[i], entries[i + 1]);
		}
		return map;
	}

	private static ChatCompletionResponse chatResponse() {
		return new ChatCompletionResponse("gen-1", "chat.completion", 1L, "synthetic-model", null,
				List.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
				null);
	}

	private static ResponsesResult responsesResult() {
		return new ResponsesResult(
				"resp-1", "response", 1L, "synthetic-model", "completed", List.of(new ResponsesOutputItem("item-1",
						"message", "completed", "assistant", List.of(new ResponsesContent("output_text", "ok")))),
				null, null);
	}

}
