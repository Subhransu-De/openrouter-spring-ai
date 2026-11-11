package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import de.subhransu.openrouter.springai.chat.mapper.ResponseMappers;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.http.HttpStatus;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.ObjectMapper;

class OpenRouterAudioOutputTests {

	private static final String VOICE = "provider-specific-voice";

	private static final String TRANSPORT_FAILURE = "synthetic transport failure";

	private static final String AUDIO_FRAGMENT = "{\"audio\":{\"data\":\"AA==\"}}";

	private static final String EXPIRES_MARKER = "{\"audio\":{\"expires_at\":123}}";

	private static final String REPLAY_MESSAGE = "Assistant audio replay is unsupported; create a text-only message explicitly to continue with the transcript";

	private static final String BOTH_REQUIRED = "Audio output requires both audio options and the audio modality";

	private static final String STREAMING_ONLY = "Audio output requires streaming Chat Completions";

	private static final String UNFINISHED = "Stream ended with unfinished audio choices";

	private static final String LIMIT = "Retained audio and transcript exceed the 16 MiB stream limit";

	private static final String NO_STOP = "Audio choice ended without complete audio and a stop finish reason";

	private final ObjectMapper json = new ObjectMapper();

	private final OpenRouterAudioOptions audio = new OpenRouterAudioOptions("alloy", "pcm16");

	private final OpenRouterChatOptions options = OpenRouterChatOptions.builder()
		.model("synthetic/audio")
		.modalities(List.of("text", "audio"))
		.audio(this.audio)
		.build();

	@ParameterizedTest(name = "fragments split after byte {0}")
	@ValueSource(ints = { 1, 2, 3, 4, 5, 6, 7, 8, 9 })
	void assemblesIndependentlyEncodedByteFragmentsIntoOneAudioMessage(int split) {
		byte[] expected = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, -1, -2 };
		ChatCompletionChunk first = chunk(0, "first", encoded(Arrays.copyOfRange(expected, 0, split)), "Hello ", null);
		ChatCompletionChunk last = chunk(0, "last", encoded(Arrays.copyOfRange(expected, split, expected.length)),
				"world", "stop");

		List<ChatResponse> responses = map(Flux.just(first, last)).collectList().block();

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).getResult().getOutput().getMedia()).isEmpty();
		assertThat(responses.get(0).getResult().getOutput().getText()).isEqualTo("first");
		AssistantMessage output = responses.get(1).getResult().getOutput();
		assertThat(output.getText()).isEqualTo("last");
		assertThat(output.getMedia()).hasSize(1);
		assertThat(output.getMedia().get(0).getDataAsByteArray()).containsExactly(expected);
		assertThat(output.getMetadata().get("openrouter.audio")).isEqualTo(
				Map.of("id", "synthetic-audio", "expires_at", 123L, "format", "pcm16", "transcript", "Hello world"));
	}

	@Test
	void repeatedFinalChunkEmitsNoDuplicateTextOrMedia() {
		ChatCompletionChunk first = chunk(0, "first", "AAE=", "Hello ", null);
		ChatCompletionChunk last = chunk(0, "last", "AgM=", "world", "stop");

		List<ChatResponse> responses = map(Flux.just(first, last, last)).collectList().block();

		assertThat(responses).hasSize(3);
		assertThat(responses.get(1).getResult().getOutput().getMedia()).hasSize(1);
		assertThat(responses.get(2).getResults()).isEmpty();
	}

	@Test
	void isolatesChoicesAndRepeatSubscriptionsAndRetainsUsageOnlyChunks() {
		Flux<ChatResponse> responses = map(Flux.just(chunk(0, null, "AA==", "a", null),
				chunk(1, null, "AQ==", "b", "stop"), chunk(0, null, "Ag==", "c", "stop"),
				this.json.readValue(
						"{\"choices\":[],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}}",
						ChatCompletionChunk.class)));

		assertChoiceIsolation(responses.collectList().block());
		assertChoiceIsolation(responses.collectList().block());
	}

	private void assertChoiceIsolation(List<ChatResponse> result) {
		assertThat(result.get(1).getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(1);
		assertThat(result.get(2).getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(0, 2);
		assertThat(result.get(3).getMetadata().getUsage().getTotalTokens()).isEqualTo(5);
	}

	@Test
	void streamWithoutTerminationFailsAfterEmittingNoMedia() {
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, null))))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia()).isEmpty())
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessage(UNFINISHED))
			.verify();
	}

	@Test
	void invalidEncodingFailsWithTheDecodingCauseAndEmitsNoMedia() {
		StepVerifier.create(map(Flux.just(chunk(0, null, "invalid!", null, "stop"))))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Audio output fragment is not valid base64")
				.hasCauseInstanceOf(IllegalArgumentException.class))
			.verify();
	}

	@Test
	void providerErrorChunkAfterAudioFailsTheStreamWithoutEmittingMedia() {
		ChatCompletionChunk failure = this.json
			.readValue("{\"error\":{\"code\":503,\"message\":\"synthetic failure\"}}", ChatCompletionChunk.class);

		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, null), failure)))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia()).isEmpty())
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOfSatisfying(OpenRouterApiException.class, failed -> {
						assertThat(failed.getStatusCode().value()).isEqualTo(503);
						assertThat(failed.getErrorDetails().message()).isEqualTo("synthetic failure");
					}))
			.verify();
	}

	@Test
	void transportFailureAfterAudioFailsTheStreamWithoutEmittingMedia() {
		StepVerifier
			.create(map(Flux.concat(Flux.just(chunk(0, null, "AA==", null, null)),
					Flux.error(new IllegalStateException(TRANSPORT_FAILURE)))))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia()).isEmpty())
			.expectErrorMessage(TRANSPORT_FAILURE)
			.verify();
	}

	@ParameterizedTest(name = "finish reason {0}")
	@ValueSource(strings = { "length", "content_filter", "tool_calls" })
	void rejectsIncompleteFinishReasons(String reason) {
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, reason))))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessage(NO_STOP))
			.verify();
	}

	@Test
	void cancellationDoesNotLeakIntoALaterSubscription() {
		Flux<ChatResponse> repeated = map(
				Flux.just(chunk(0, null, "AA==", null, null), chunk(0, null, "AQ==", null, "stop")));
		StepVerifier.create(repeated).expectNextCount(1).thenCancel().verify();
		StepVerifier.create(repeated)
			.expectNextCount(1)
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia().get(0).getDataAsByteArray())
				.containsExactly(0, 1))
			.verifyComplete();
	}

	@Test
	void retainedAudioIsLimitedTo16MiBAndTheLimitDoesNotLeakIntoALaterSubscription() {
		String megabyte = encoded(new byte[1024 * 1024]);
		// Every fragment repeats the audio id, which counts toward the 16 MiB budget (two
		// bytes per character). Fifteen 1 MiB fragments plus that overhead leave less
		// than
		// 1 MiB, so the sixteenth fragment is refused before it is decoded.
		int fragmentsThatFit = 15;
		Flux<ChatResponse> overLimit = map(
				Flux.range(0, fragmentsThatFit + 1).map(i -> chunk(0, null, megabyte, null, null)));

		StepVerifier.create(overLimit).expectNextCount(fragmentsThatFit).expectErrorMessage(LIMIT).verify();
		StepVerifier.create(overLimit).expectNextCount(fragmentsThatFit).expectErrorMessage(LIMIT).verify();
	}

	static Stream<Arguments> audioFormats() {
		return Stream.of(Arguments.of("wav", MimeType.valueOf("audio/wav")),
				Arguments.of("mp3", MimeType.valueOf("audio/mpeg")),
				Arguments.of("flac", MimeType.valueOf("audio/flac")),
				Arguments.of("opus", MimeType.valueOf("audio/ogg;codecs=opus")),
				Arguments.of("pcm16", MimeType.valueOf("audio/pcm")));
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = { "wav", "mp3", "flac", "opus", "pcm16" })
	void serializesTheTypedAudioOptionsOntoTheRequest(String format) {
		OpenRouterChatOptions configured = this.options.mutate()
			.audio(new OpenRouterAudioOptions(VOICE, format))
			.build();

		ChatCompletionRequest request = new OpenRouterChatRequestMapper(this.json)
			.map(List.of(new UserMessage("synthetic")), configured, true, List.of());

		var wire = this.json.valueToTree(request);
		assertThat(wire.path("audio").path("format").asString()).isEqualTo(format);
		assertThat(wire.path("audio").path("voice").asString()).isEqualTo(VOICE);
		assertThat(wire.path("modalities")).isEqualTo(this.json.readTree("[\"text\",\"audio\"]"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("audioFormats")
	void mapsEachConfiguredFormatToItsMimeType(String format, MimeType expected) {
		OpenRouterAudioOptions config = new OpenRouterAudioOptions(VOICE, format);

		ChatResponse response = ResponseMappers.chatStreaming()
			.map(Flux.just(chunk(0, null, "AA==", null, "stop")), config)
			.blockLast();

		assertThat(response.getResult().getOutput().getMedia()).singleElement().satisfies(media -> {
			assertThat(media.getMimeType()).isEqualTo(expected);
			assertThat(media.getDataAsByteArray()).containsExactly(0);
		});
	}

	@ParameterizedTest(name = "{2}")
	@CsvSource(delimiter = '|',
			value = { "alloy|aac|Audio format must be wav, mp3, flac, opus, or pcm16|unsupported format",
					"alloy|WAV|Audio format must be wav, mp3, flac, opus, or pcm16|format is case sensitive",
					" |wav|Audio voice must not be blank|blank voice",
					"alloy||Audio format must be wav, mp3, flac, opus, or pcm16|null format" })
	void rejectsInvalidAudioOptions(String voice, String format, String message, String scenario) {
		assertThatThrownBy(() -> new OpenRouterAudioOptions(voice, format)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(message);
	}

	@Test
	void blockingCallsRejectAudioOutputBeforeCallingTheApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();

		assertThatThrownBy(() -> model.call(new Prompt("synthetic"))).isInstanceOf(IllegalArgumentException.class)
			.hasMessage(STREAMING_ONLY);
		verifyNoInteractions(api);
	}

	@Test
	void responsesModeRejectsAudioOutputBeforeCallingTheApi() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();
		Prompt prompt = new Prompt("synthetic",
				this.options.mutate().requestMode(OpenRouterRequestMode.OPENAI_RESPONSES).build());

		StepVerifier.create(model.stream(prompt)).expectErrorMessage(STREAMING_ONLY).verify();
		verifyNoInteractions(api);
	}

	static Stream<Arguments> incompleteAudioRequests() {
		return Stream.of(
				Arguments.of(Named.of("audio modality without audio options", (OptionsEdit) b -> b.audio(null))),
				Arguments.of(Named.of("audio options without audio modality", (OptionsEdit) b -> b.modalities(null))));
	}

	@ParameterizedTest
	@MethodSource("incompleteAudioRequests")
	void streamsRejectHalfConfiguredAudioOutputBeforeCallingTheApi(OptionsEdit edit) {
		OpenRouterApi api = mock(OpenRouterApi.class);
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();
		Prompt prompt = new Prompt("synthetic", edit.apply(this.options.mutate()).build());

		StepVerifier.create(model.stream(prompt)).expectErrorMessage(BOTH_REQUIRED).verify();
		verifyNoInteractions(api);
	}

	static Stream<Arguments> assistantMessagesCarryingAudio() {
		Media wav = Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/wav")).data(new byte[] { 1 }).build();
		Media png = Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(new byte[] { 1 }).build();
		return Stream.of(
				Arguments.of(Named.of("audio metadata",
						AssistantMessage.builder()
							.content("synthetic")
							.properties(Map.of("openrouter.audio", Map.of("id", "synthetic")))
							.build())),
				Arguments.of(Named.of("audio media",
						AssistantMessage.builder().content("synthetic").media(List.of(wav)).build())),
				Arguments.of(Named.of("audio mixed with image media",
						AssistantMessage.builder().content("synthetic mixed media").media(List.of(png, wav)).build())));
	}

	@ParameterizedTest
	@MethodSource("assistantMessagesCarryingAudio")
	void rejectsReplayOfAssistantAudioInBothRequestModes(AssistantMessage assistant) {
		OpenRouterChatOptions plain = OpenRouterChatOptions.builder().build();

		assertThatThrownBy(
				() -> new OpenRouterChatRequestMapper(this.json).map(List.of(assistant), plain, true, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(REPLAY_MESSAGE);
		assertThatThrownBy(
				() -> new OpenRouterResponsesRequestMapper(this.json).map(List.of(assistant), plain, true, List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage(REPLAY_MESSAGE);
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
		assertThat(requests.getAllValues().get(0).audio().voice()).isEqualTo("alloy");
		assertThat(requests.getAllValues().get(1).audio().format()).isEqualTo("wav");
		assertThat(requests.getAllValues().get(1).audio().voice()).isEqualTo("echo");
	}

	@ParameterizedTest(name = "{2}")
	@CsvSource(delimiter = '|',
			value = { "{\"id\":\"different\"}|Conflicting audio identifiers in one choice|conflicting audio id",
					"{\"format\":\"mp3\"}|Conflicting audio output format|conflicting audio format" })
	void rejectsConflictingAudioMetadata(String audioDelta, String message, String scenario) {
		ChatCompletionChunk conflicting = this.json.readValue(
				"{\"choices\":[{\"index\":0,\"delta\":{\"audio\":" + audioDelta + "}}]}", ChatCompletionChunk.class);

		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, null), conflicting)))
			.expectNextCount(1)
			.expectErrorSatisfies(
					error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class).hasMessage(message))
			.verify();
	}

	@Test
	void rejectsMessageAudioSnapshots() {
		StepVerifier.create(map(Flux.just(snapshot())))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Audio output requires delta.audio fragments, not message.audio snapshots"))
			.verify();
	}

	@Test
	void repeatedFinalMessageSnapshotAfterDeltaCompletionAddsNoMedia() {
		// The snapshot is only rejected while its choice is still assembling audio.
		StepVerifier.create(map(Flux.just(chunk(0, null, "AA==", null, "stop"), snapshot())))
			.expectNextCount(1)
			.assertNext(response -> assertThat(response.getResults()).isEmpty())
			.verifyComplete();
	}

	private ChatCompletionChunk snapshot() {
		return this.json.readValue(
				"{\"choices\":[{\"index\":0,\"message\":{\"audio\":{\"data\":\"AA==\"}},\"finish_reason\":\"stop\"}]}",
				ChatCompletionChunk.class);
	}

	static Stream<Arguments> audioMixedWithToolCalls() {
		String audioDone = "{\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"AA==\"}},\"finish_reason\":\"stop\"}]}";
		String audioOpen = "{\"choices\":[{\"index\":0,\"delta\":{\"audio\":{\"data\":\"AA==\"}}}]}";
		String tool = "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"synthetic-tool\",\"type\":\"function\",\"function\":{\"name\":\"synthetic\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
		String toolStart = "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"synthetic\"}}]}}]}";
		return Stream.of(Arguments.of(Named.of("tool call after audio", List.of(audioOpen, tool))),
				Arguments.of(Named.of("audio after tool call", List.of(toolStart, audioDone))));
	}

	@ParameterizedTest
	@MethodSource("audioMixedWithToolCalls")
	void audioCannotBeLostInsideToolAggregation(List<String> sequence) {
		List<ChatCompletionChunk> chunks = sequence.stream()
			.map(item -> this.json.readValue(item, ChatCompletionChunk.class))
			.toList();
		OpenRouterApi api = mock(OpenRouterApi.class);
		when(api.chatCompletionStream(any())).thenReturn(Flux.fromIterable(chunks));
		OpenRouterChatModel model = OpenRouterChatModel.builder()
			.openRouterApi(api)
			.defaultOptions(this.options)
			.build();

		StepVerifier.create(model.stream(new Prompt("synthetic")))
			.thenConsumeWhile(response -> response.getResult().getOutput().getMedia().isEmpty())
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterProtocolException.class)
				.hasMessage("Audio and tool calls in the same choice are unsupported"))
			.verify();
	}

	@Test
	void realSseCompletesAudioOnceDoneArrivesAfterTheAudioChoiceFinishes() {
		OpenRouterChatModel model = sseModel(finishedAudioSse() + "data: [DONE]\n\n");

		StepVerifier.create(model.stream(new Prompt("synthetic")))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia().get(0).getDataAsByteArray())
				.containsExactly(0))
			.verifyComplete();
	}

	@Test
	void realSseFailsWithoutDoneEvenAfterTheAudioChoiceFinishes() {
		OpenRouterChatModel model = sseModel(finishedAudioSse());

		StepVerifier.create(model.stream(new Prompt("synthetic")))
			.assertNext(response -> assertThat(response.getResult().getOutput().getMedia().get(0).getDataAsByteArray())
				.containsExactly(0))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@Test
	void realSseCompletesMarkerTerminatedAudioAfterDone() {
		// Synthetic stream in the shape used by providers that end audio with an
		// expires_at-only delta and never send a finish reason.
		OpenRouterChatModel model = sseModel(sse(markerTerminatedStream()) + "data: [DONE]\n\n");

		List<ChatResponse> responses = model.stream(new Prompt("synthetic")).collectList().block();

		List<Generation> withAudio = responses.stream()
			.flatMap(response -> response.getResults().stream())
			.filter(generation -> !generation.getOutput().getMedia().isEmpty())
			.toList();
		assertThat(withAudio).as("audio is emitted exactly once, after [DONE]").hasSize(1);
		// One response per chunk; the usage chunk is the seventh, and the audio response
		// emitted at completion carries no usage of its own.
		assertThat(responses).extracting(response -> response.getMetadata().getUsage().getTotalTokens())
			.containsExactly(0, 0, 0, 0, 0, 0, 5, 0);
		ChatResponse last = responses.get(responses.size() - 1);
		AssistantMessage output = last.getResult().getOutput();
		assertThat(output.getText()).isEmpty();
		assertThat(output.getMedia().get(0).getDataAsByteArray()).containsExactly(0, 1, 2);
		assertThat(output.getMetadata().get("openrouter.audio")).isEqualTo(
				Map.of("id", "synthetic-audio", "expires_at", 123L, "format", "pcm16", "transcript", "Hello world"));
		assertThat(last.getResult().getMetadata().getFinishReason()).isNull();
	}

	@Test
	void realSseWithoutDoneNeverEmitsMarkerTerminatedAudio() {
		OpenRouterChatModel model = sseModel(sse(markerTerminatedStream()));

		StepVerifier.create(model.stream(new Prompt("synthetic")))
			.thenConsumeWhile(response -> response.getResults()
				.stream()
				.allMatch(generation -> generation.getOutput().getMedia().isEmpty()))
			.expectError(OpenRouterTruncatedResponseException.class)
			.verify();
	}

	@Test
	void completesMarkerTerminatedAudioOnceAfterNormalCompletion() {
		List<ChatResponse> responses = map(Flux.fromIterable(markerTerminatedStream())).collectList().block();
		List<ChatResponse> withMedia = responses.stream()
			.filter(response -> response.getResults()
				.stream()
				.anyMatch(generation -> !generation.getOutput().getMedia().isEmpty()))
			.toList();
		assertThat(withMedia).containsExactly(responses.get(responses.size() - 1));
		ChatResponse last = withMedia.get(0);
		assertThat(last.getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(0, 1, 2);
		assertThat(last.getResult().getMetadata().<Integer>get("openrouter.choice_index")).isZero();
		assertThat(last.getMetadata().getId()).isEqualTo("synthetic");
		assertThat(last.getMetadata().getUsage().getTotalTokens()).isZero();
	}

	static Stream<Arguments> unmarkedOrContinuedAudio() {
		String empty = "{\"role\":\"assistant\",\"content\":\"\"}";
		return Stream.of(Arguments.of(Named.of("no marker and no finish reason", List.of(AUDIO_FRAGMENT, empty))),
				Arguments.of(Named.of("more audio after the marker",
						List.of(AUDIO_FRAGMENT, EXPIRES_MARKER, "{\"audio\":{\"data\":\"AQ==\"}}"))),
				Arguments.of(Named.of("text after the marker",
						List.of(AUDIO_FRAGMENT, EXPIRES_MARKER, "{\"content\":\"more\"}"))),
				Arguments.of(Named.of("marker without audio bytes",
						List.of("{\"audio\":{\"transcript\":\"Hello\"}}", EXPIRES_MARKER))));
	}

	@ParameterizedTest
	@MethodSource("unmarkedOrContinuedAudio")
	void unmarkedEmptyOrContinuedAudioStillFailsAtCompletion(List<String> deltas) {
		List<ChatCompletionChunk> sequence = deltas.stream().map(delta -> raw(delta, null)).toList();

		StepVerifier.create(map(Flux.fromIterable(sequence)))
			.thenConsumeWhile(response -> response.getResults()
				.stream()
				.allMatch(generation -> generation.getOutput().getMedia().isEmpty()))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(OpenRouterTruncatedResponseException.class)
				.hasMessage(UNFINISHED))
			.verify();
	}

	@Test
	void transportFailureAfterTheCompletionMarkerStillFailsTheStream() {
		StepVerifier
			.create(map(Flux.concat(Flux.just(raw(AUDIO_FRAGMENT, null), raw(EXPIRES_MARKER, null)),
					Flux.error(new IllegalStateException(TRANSPORT_FAILURE)))))
			.expectNextCount(2)
			.expectErrorMessage(TRANSPORT_FAILURE)
			.verify();
	}

	@Test
	void completesMarkerTerminatedChoicesAlongsideStoppedChoices() {
		ChatCompletionChunk stopped = chunk(0, null, "AA==", null, "stop");
		ChatCompletionChunk markerData = this.json.readValue(
				"{\"id\":\"synthetic\",\"choices\":[{\"index\":1,\"delta\":{\"audio\":{\"data\":\"AQ==\"}}}]}",
				ChatCompletionChunk.class);
		ChatCompletionChunk marker = this.json.readValue(
				"{\"id\":\"synthetic\",\"choices\":[{\"index\":1,\"delta\":{\"audio\":{\"expires_at\":123}}}]}",
				ChatCompletionChunk.class);
		List<ChatResponse> responses = map(Flux.just(markerData, stopped, marker)).collectList().block();
		assertThat(responses).hasSize(4);
		assertThat(responses.get(1).getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(0);
		ChatResponse completion = responses.get(3);
		assertThat(completion.getResults()).hasSize(1);
		assertThat(completion.getResult().getMetadata().<Integer>get("openrouter.choice_index")).isEqualTo(1);
		assertThat(completion.getResult().getOutput().getMedia().get(0).getDataAsByteArray()).containsExactly(1);
	}

	private String finishedAudioSse() {
		return "data: " + this.json.writeValueAsString(chunk(0, "text", "AA==", "spoken", "stop")) + "\n\n";
	}

	private OpenRouterChatModel sseModel(String body) {
		OpenRouterApi api = OpenRouterApi.builder()
			.apiKey("synthetic-key")
			.webClientBuilder(WebClient.builder()
				.exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
					.header("Content-Type", "text/event-stream")
					.body(body)
					.build())))
			.build();
		return OpenRouterChatModel.builder().openRouterApi(api).defaultOptions(this.options).build();
	}

	private List<ChatCompletionChunk> markerTerminatedStream() {
		return List.of(raw(
				"{\"role\":\"assistant\",\"content\":\"\",\"audio\":{\"id\":\"synthetic-audio\",\"transcript\":\"Hello \"}}",
				null), raw("{\"content\":\"\",\"audio\":{\"transcript\":\"world\"}}", null),
				raw("{\"content\":\"\",\"audio\":{\"id\":\"synthetic-audio\",\"data\":\"AAE=\"}}", null),
				raw("{\"content\":\"\",\"audio\":{\"data\":\"Ag==\"}}", null),
				raw("{\"content\":\"\",\"audio\":{\"expires_at\":123}}", null),
				raw("{\"role\":\"assistant\",\"content\":\"\"}", null), raw("{\"role\":\"assistant\",\"content\":\"\"}",
						"{\"prompt_tokens\":2,\"completion_tokens\":3,\"total_tokens\":5}"));
	}

	private ChatCompletionChunk raw(String delta, String usage) {
		return this.json.readValue(
				"{\"id\":\"synthetic\",\"model\":\"synthetic/audio\",\"choices\":[{\"index\":0,\"delta\":" + delta
						+ ",\"finish_reason\":null}]" + (usage != null ? ",\"usage\":" + usage : "") + "}",
				ChatCompletionChunk.class);
	}

	private String sse(List<ChatCompletionChunk> chunks) {
		StringBuilder body = new StringBuilder();
		for (ChatCompletionChunk chunk : chunks) {
			body.append("data: ").append(this.json.writeValueAsString(chunk)).append("\n\n");
		}
		return body.toString();
	}

	private Flux<ChatResponse> map(Flux<ChatCompletionChunk> chunks) {
		return ResponseMappers.chatStreaming().map(chunks, this.audio);
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

	@FunctionalInterface
	interface OptionsEdit {

		OpenRouterChatOptions.Builder apply(OpenRouterChatOptions.Builder builder);

	}

}
