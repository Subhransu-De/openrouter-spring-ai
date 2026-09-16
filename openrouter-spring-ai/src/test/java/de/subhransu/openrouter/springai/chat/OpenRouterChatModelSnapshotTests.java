package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ProviderPreferences;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class OpenRouterChatModelSnapshotTests {

	@ParameterizedTest
	@EnumSource(OpenRouterRequestMode.class)
	void concurrentCallsAndStreamsRetainTheirOwnRouting(OpenRouterRequestMode mode) {
		var api = mock(OpenRouterApi.class);
		var captured = new ConcurrentLinkedQueue<ProviderPreferences>();
		if (mode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
			when(api.chatCompletion(any())).thenAnswer(invocation -> {
				captured.add(invocation.<ChatCompletionRequest>getArgument(0).provider());
				return new ChatCompletionResponse("synthetic", "chat.completion", 1L, "synthetic-model", null, List
					.of(new Choice(0, new ChatMessage("assistant", "ok", null, null, null), null, "stop", "stop")),
						null);
			});
			when(api.chatCompletionStream(any())).thenAnswer(invocation -> {
				captured.add(invocation.<ChatCompletionRequest>getArgument(0).provider());
				return Flux.empty();
			});
		}
		else {
			when(api.responses(any())).thenAnswer(invocation -> {
				captured.add(invocation.<ResponsesRequest>getArgument(0).provider());
				return new ResponsesResult("synthetic", "response", 1L, "synthetic-model", "completed", List.of(), null,
						null);
			});
			when(api.responsesStream(any())).thenAnswer(invocation -> {
				captured.add(invocation.<ResponsesRequest>getArgument(0).provider());
				return Flux.empty();
			});
		}
		var defaultRoutes = new ArrayList<>(List.of("default-route"));
		var defaults = options(mode, defaultRoutes);
		var model = OpenRouterChatModel.builder().openRouterApi(api).defaultOptions(defaults).build();
		var firstRoutes = new ArrayList<>(List.of("first-route"));
		var secondRoutes = new ArrayList<>(List.of("second-route"));
		var prompts = List.of(new Prompt("synthetic"), new Prompt("synthetic", options(mode, firstRoutes)),
				new Prompt("synthetic", options(mode, secondRoutes)));
		var streams = prompts.stream().map(model::stream).toList();
		defaultRoutes.clear();
		firstRoutes.clear();
		secondRoutes.clear();
		StepVerifier
			.create(Flux.merge(
					Flux.fromIterable(prompts)
						.flatMap(prompt -> Mono.fromCallable(() -> model.call(prompt))
							.subscribeOn(Schedulers.parallel())),
					Flux.fromIterable(streams).flatMap(stream -> stream.subscribeOn(Schedulers.parallel())))
				.then())
			.verifyComplete();
		assertThat(captured).extracting(ProviderPreferences::order)
			.containsExactlyInAnyOrder(List.of("default-route"), List.of("first-route"), List.of("second-route"),
					List.of("default-route"), List.of("first-route"), List.of("second-route"));
	}

	private static OpenRouterChatOptions options(OpenRouterRequestMode mode, List<String> routes) {
		return OpenRouterChatOptions.builder()
			.model("synthetic-model")
			.requestMode(mode)
			.provider(new OpenRouterProviderPreferences(true, null, null, routes, null, null, null))
			.build();
	}

}
