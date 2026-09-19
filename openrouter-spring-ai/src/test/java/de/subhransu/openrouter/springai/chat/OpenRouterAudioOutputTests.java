package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterStreamingResponseMapper;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OpenRouterAudioOutputTests {

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterAudioOptions audio = new OpenRouterAudioOptions("alloy", "pcm16");

	private final OpenRouterChatOptions options = OpenRouterChatOptions.builder()
		.model("synthetic/audio")
		.modalities(List.of("text", "audio"))
		.audio(this.audio)
		.build();

	@Test
	void assemblesIndependentlyEncodedByteFragmentsAtEveryBoundaryWithoutDuplicatingTextOrFinalMedia() {
		byte[] expected = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, -1, -2 };
		for (int split = 1; split < expected.length; split++) {
			ChatCompletionChunk first = chunk(0, "first", encoded(Arrays.copyOfRange(expected, 0, split)), "Hello ",
					null);
			ChatCompletionChunk last = chunk(0, "last", encoded(Arrays.copyOfRange(expected, split, expected.length)),
					"world", "stop");
			List<ChatResponse> responses = map(Flux.just(first, last, last)).collectList().block();
			assertThat(responses).hasSize(3);
			assertThat(responses.get(0).getResult().getOutput().getMedia()).isEmpty();
			assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("first");
			AssistantMessage output = responses.get(1).getResult().getOutput();
			assertThat(output.getText()).isEqualTo("last");
			assertThat(output.getMedia()).hasSize(1);
			assertThat(output.getMedia().get(0).getDataAsByteArray()).containsExactly(expected);
			assertThat(output.getMetadata().get("openrouter.audio")).isEqualTo(Map.of("id", "synthetic-audio",
					"expires_at", 123L, "format", "pcm16", "transcript", "Hello world"));
			assertThat(responses.get(2).getResults()).isEmpty();
		}
	}

	@Test
	void isolatesChoicesAndRepeatSubscriptionsAndRetainsUsageOnlyChunks() {
		Flux<ChatResponse> responses = map(Flux.just(chunk(0, null, "AA==", "a", null),
				chunk(1, null, "AQ==", "b", "stop"), chunk(0, null, "Ag==", "c", "stop"),
				this.json.readValue(
						"{\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}",
						ChatCompletionChunk.class)));
		for (int i = 0; i < 2; i++) {
			List<ChatResponse> result = responses.collectList().block();
			assertThat(result.get(1).getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(1);
			assertThat(result.get(2).getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(0,
					2);
			assertThat(result.get(3).getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
		}
	}

	@Test
	void missingTerminationInvalidEncodingAndProviderFailureNeverEmitPartialMedia() {
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, null))))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia()).isEmpty())
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
		StepVerifier.create(map(Flux.just(chunk(0, null, "invalid!", null, "stop"))))
			.expectError(IllegalArgumentException.class)
			.verify();
		StepVerifier
			.create(map(Flux.just(chunk(0, null, "AA==", null, null), this.json
				.readValue("{\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}", ChatCompletionChunk.class))))
			.expectNextCount(1)
			.expectError()
			.verify();
		StepVerifier
			.create(map(Flux.concat(Flux.just(chunk(0, null, "AA==", null, null)),
					Flux.error(new IllegalStateException("synthetic transport failure")))))
			.expectNextCount(1)
			.expectErrorMessage("synthetic transport failure")
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "length", "content_filter", "tool_calls" })
	void rejectsIncompleteFinishReasons(String reason) {
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, reason))))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@Test
	void cancellationAndLimitsDoNotLeakIntoLaterSubscriptions() {
		Flux<ChatResponse> repeated = map(
				Flux.just(chunk(0, null, "AA==", null, null), chunk(0, null, "AQ==", null, "stop")));
		StepVerifier.create(repeated).expectNextCount(1).thenCancel().verify();
		StepVerifier.create(repeated)
			.expectNextCount(1)
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia().get(0).getDataAsByteArray())
				.containsExactly(0, 1))
			.verifyComplete();
		String megabyte = encoded(new byte[1024 * 1024]);
		StepVerifier.create(map(Flux.range(0, 17).map(i -> chunk(0, null, megabyte, null, null))))
			.expectNextCount(15)
			.expectErrorMessage("Retained audio and transcript exceed the 16 MiB stream limit")
			.verify();
	}

	@ParameterizedTest
	@ValueSource(strings = { "wav", "mp3", "flac", "opus", "pcm16" })
	void serializesTypedOptionsAndMapsFormats(String format) {
		OpenRouterAudioOptions config = new OpenRouterAudioOptions("provider-specific-voice", format);
		OpenRouterChatOptions configured = this.options.mutate().audio(config).build();
		assertThat(configured.<OpenRouterChatOptions>copy().getAudio()).isEqualTo(config);
		assertThat(this.options.merge(configured).getAudio()).isEqualTo(config);
		ChatCompletionRequest request = new OpenRouterChatRequestMapper(this.json)
			.map(List.of(new UserMessage("synthetic")), configured, true, List.of());
		assertThat(this.json.valueToTree(request).path("audio").path("format").asString()).isEqualTo(format);
		assertThat(this.json.valueToTree(request).path("audio").path("voice").asString())
			.isEqualTo("provider-specific-voice");
		ChatResponse response = new OpenRouterStreamingResponseMapper()
			.map(Flux.just(chunk(0, null, "AA==", null, "stop")), config)
			.blockLast();
		assertThat(response.getResult().getOutput().getMedia().get(0).getMimeType().getType()).isEqualTo("audio");
	}

	@Test
	void rejectsInvalidOptionsProtocolsAndReplayBeforeCallingApi() {
		assertThatThrownBy(() -> new OpenRouterAudioOptions("alloy", "aac"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OpenRouterAudioOptions(" ", "wav")).isInstanceOf(IllegalArgumentException.class);
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();
		assertThatThrownBy(() -> model.call(new Prompt("synthetic")))
			.hasMessageContaining("streaming Chat Completions");
		StepVerifier
			.create(model.stream(new Prompt("synthetic",
					this.options.mutate().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES).build())))
			.expectErrorMessage("Audio output requires streaming Chat Completions")
			.verify();
		StepVerifier.create(model.stream(new Prompt("synthetic", this.options.mutate().audio(null).build())))
			.expectErrorMessage("Audio output requires both audio options and the audio modality")
			.verify();
		StepVerifier.create(model.stream(new Prompt("synthetic", this.options.mutate().modalities(null).build())))
			.expectErrorMessage("Audio output requires both audio options and the audio modality")
			.verify();
		for (AssistantMessage assistant : List.of(
				AssistantMessage.builder()
					.content("synthetic")
					.properties(Map.of("openrouter.audio", Map.of("id", "synthetic")))
					.build(),
				AssistantMessage.builder()
					.content("synthetic")
					.media(List.of(Media.builder()
						.mimeType(MimeTypeUtils.parseMimeType("audio/wav"))
						.data(new byte[] { 1 })
						.build()))
					.build())) {
			assertThatThrownBy(() -> new OpenRouterChatRequestMapper(this.json).map(List.of(assistant),
					OpenRouterChatOptions.builder().build(), true, List.of()))
				.hasMessageContaining("audio replay is unsupported");
			assertThatThrownBy(() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(assistant),
					OpenRouterChatOptions.builder().build(), true, List.of()))
				.hasMessageContaining("audio replay is unsupported");
		}
		verifyNoInteractions(api);
	}

	@Test
	void modelStreamsAudioUsingDefaultsAndCompleteRuntimeOverrides() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.just(chunk(0, "text", "AA==", "spoken", "stop")));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();
		assertThat(model.stream(new Prompt("synthetic")).blockLast().getResult().getOutput().getMedia()).hasSize(1);
		model
			.stream(new Prompt("synthetic",
					this.options.mutate().audio(new OpenRouterAudioOptions("echo", "wav")).build()))
			.blockLast();
		ArgumentCaptor<ChatCompletionRequest> requests = ArgumentCaptor.forClass(ChatCompletionRequest.class);
		verify(api, times(2)).chatCompletionStream(requests.capture());
		assertThat(requests.getAllValues().get(0).audio().format()).isEqualTo("pcm16");
		assertThat(requests.getAllValues().get(1).audio().format()).isEqualTo("wav");
	}

	@Test
	void rejectsConflictingMetadataAndUnsupportedSnapshots() {
		for (String audio : List.of("{\"id\":\"different\"}", "{\"format\":\"mp3\"}")) {
			ChatCompletionChunk conflicting = this.json.readValue(
					"{\"choices\":[{\"index\":0,\"delta\":{\"audio\":" + audio + "}}]}", ChatCompletionChunk.class);
			StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, null), conflicting)))
				.expectNextCount(1)
				.expectError(IllegalStateException.class)
				.verify();
		}
		ChatCompletionChunk snapshot = this.json.readValue(
				"{\"choices\":[{\"index\":0,\"message\":{\"audio\":{\"data\":\"AA==\"}},\"finish_reason\":\"stop\"}]}",
				ChatCompletionChunk.class);
		StepVerifier.create(map(Flux.just(snapshot))).expectError(IllegalArgumentException.class).verify();
		// A repeated final message snapshot after the delta-based completion adds no
		// media.
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, "stop"), snapshot)))
			.expectNextCount(1)
			.assertNext(response -> assertThat(response.getResults()).isEmpty())
			.verifyComplete();
	}

	@Test
	void audioCannotBeLostInsideToolAggregation() {
		ChatCompletionChunk tool = this.json.readValue(
				"{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"synthetic-tool\",\"type\":\"function\",\"function\":{\"name\":\"synthetic\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}",
				ChatCompletionChunk.class);
		for (List<ChatCompletionChunk> sequence : List
			.of(List.of(chunk(0, null, "AA==", null, null), tool), List.of(this.json.readValue(
					"{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"synthetic\"}}]}}]}",
					ChatCompletionChunk.class), chunk(0, null, "AA==", null, "stop")))) {
			OpenRouterApi api = mock(OpenRouterApi.class);
			when(api.chatCompletionStream(any())).thenReturn(Flux.fromIterable(sequence));
			OpenRouterChatModel model = OpenRouterChatModel.builder()
				.openRouterApi(api)
				.defaultOptions(this.options)
				.build();
			StepVerifier.create(model.stream(new Prompt("synthetic")))
				.thenConsumeWhile(response -> response.getResult().getOutput().getMedia().isEmpty())
				.expectErrorMessage("Audio and tool calls in the same choice are unsupported")
				.verify();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void realSseRequiresDoneEvenAfterAudioChoiceFinishes(boolean done) {
		String body = "data: " + this.json.writeValueAsString(chunk(0, "text", "AA==", "spoken", "stop")) + "\n\n"
				+ (done ? "data: [DONE]\n\n" : "");
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", "text/event-stream")
					.body(body)
					.build())))
			.build();
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();
		var verifier = StepVerifier.create(model.stream(new Prompt("synthetic")))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia().get(0).getDataAsByteArray())
				.containsExactly(0));
		if (done) {
			verifier.verifyComplete();
		}
		else {
			verifier.expectError(OpenRouterTruncatedResponseException.class).verify();
		}
	}

	private Flux<ChatResponse> map(Flux<ChatCompletionChunk> chunks) {
		return new OpenRouterStreamingResponseMapper().map(chunks, this.audio);
	}

	private String encoded(byte[] bytes) {
		return Base64.getEncoder().encodeToString(bytes);
	}

	private ChatCompletionChunk chunk(int index, String text, String data, String transcript, String finish) {
		Map<String, Object> audio = new HashMap<>();
		audio.put("id", "synthetic-audio");
		audio.put("expires_at", 123);
		audio.put("unknown_future_field", true);
		if (data != null) {
			audio.put("data", data);
		}
		if (transcript != null) {
			audio.put("transcript", transcript);
		}
		Map<String, Object> delta = new HashMap<>();
		delta.put("audio", audio);
		if (text != null) {
			delta.put("content", text);
		}
		Map<String, Object> choice = new HashMap<>();
		choice.put("index", index);
		choice.put("delta", delta);
		if (finish != null) {
			choice.put("finish_reason", finish);
		}
		return this.json.convertValue(Map.of("id", "synthetic", "model", "synthetic/audio", "choices", List.of(choice)),
				ChatCompletionChunk.class);
	}

}
