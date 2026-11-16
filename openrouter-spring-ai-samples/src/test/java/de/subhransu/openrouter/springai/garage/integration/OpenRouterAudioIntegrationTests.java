package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterTimestampGranularity;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionFormat;
import de.subhransu.openrouter.springai.audio.OpenRouterTranscriptionMetadata;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterAudioSpeechAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterAudioTranscriptionAutoConfiguration;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.audio.tts.TextToSpeechResponse;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import tools.jackson.databind.JsonNode;

/**
 * The auto-configured text-to-speech and transcription models against the OpenRouter
 * mock: audio bytes and transcripts come back through the real HTTP clients, chunked
 * speech arrives as more than one element, and provider errors keep their status.
 */
@Tag("samples")
@Tag("samples.integration")
class OpenRouterAudioIntegrationTests {

  private static final String SPEECH = "/audio/speech";

  private static final String TRANSCRIPTIONS = "/audio/transcriptions";

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
    .withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
        OpenRouterAudioSpeechAutoConfiguration.class, OpenRouterAudioTranscriptionAutoConfiguration.class))
    .withBean(RetryTemplate.class, () -> new RetryTemplate(RetryPolicy.builder()
      .maxRetries(2)
      .delay(Duration.ofMillis(10))
      .includes(TransientAiException.class)
      .build()))
    .withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
        "spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(),
        "spring.ai.model.audio.speech=openrouter", "spring.ai.model.audio.transcription=openrouter",
        "spring.ai.openrouter.audio.speech.model=synthetic/speech",
        "spring.ai.openrouter.audio.speech.voice=garage-voice",
        "spring.ai.openrouter.audio.transcription.model=synthetic/transcription");

  @BeforeEach
  void resetMock() {
    OpenRouterMock.reset();
  }

  @Test
  void speechCallReturnsTheCompleteMp3() {
    run((speech, transcription) -> {
      TextToSpeechResponse response = speech.call(new TextToSpeechPrompt("Your car is ready for pickup.",
          OpenRouterAudioSpeechOptions.builder().format("mp3").instructions("Friendly.").build()));

      assertThat(response.getResult().getOutput()).containsExactly(fixture("audio/speech.mp3"));
    });
    JsonNode request = single(SPEECH);
    assertThat(request.path("model").asString()).isEqualTo("synthetic/speech");
    assertThat(request.path("input").asString()).isEqualTo("Your car is ready for pickup.");
    assertThat(request.path("voice").asString()).isEqualTo("garage-voice");
    assertThat(request.path("response_format").asString()).isEqualTo("mp3");
    assertThat(request.path("instructions").asString()).isEqualTo("Friendly.");
  }

  @Test
  void chunkedPcmSpeechStreamsInMoreThanOnePiece() {
    run((speech, transcription) -> {
      List<byte[]> chunks = speech.stream(new TextToSpeechPrompt("Your car is ready for pickup."))
        .map(response -> response.getResult().getOutput())
        .collectList()
        .block(TIMEOUT);

      assertThat(chunks).hasSizeGreaterThan(1);
      ByteArrayOutputStream audio = new ByteArrayOutputStream();
      chunks.forEach(audio::writeBytes);
      assertThat(audio.toByteArray()).containsExactly(fixture("audio/speech.pcm"));
    });
    assertThat(single(SPEECH).has("response_format")).isFalse();
  }

  @Test
  void aSpeechProviderErrorIsClassifiedAndNotRetried() {
    run((speech, transcription) -> {
      TextToSpeechPrompt prompt = new TextToSpeechPrompt("Hello.",
          OpenRouterAudioSpeechOptions.builder().voice("missing-voice").build());
      assertThatThrownBy(() -> speech.call(prompt)).isInstanceOf(OpenRouterNonTransientApiException.class)
        .satisfies(failure -> assertThat(((OpenRouterHttpFailure) failure).getStatusCode().value())
          .isEqualTo(400));
      assertThatThrownBy(() -> speech.stream(prompt).blockLast(TIMEOUT))
        .isInstanceOf(OpenRouterNonTransientApiException.class);
    });
    assertThat(OpenRouterMock.requests(SPEECH)).hasSize(2);
  }

  @Test
  void inlineAudioIsTranscribedWithUsage() throws IOException {
    run((speech, transcription) -> {
      AudioTranscriptionResponse response = transcription
        .call(new AudioTranscriptionPrompt(new ClassPathResource("openrouter-mock/media/inspection-note.wav")));

      assertThat(response.getResult().getOutput())
        .isEqualTo("Front brake pads are at three millimetres. Recommend replacement before pickup.");
      OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
      assertThat(metadata.usage())
        .isEqualTo(new OpenRouterTranscriptionMetadata.Usage(4.6, 0.000312, 64, 18, 82));
      assertThat(metadata.segments()).isEmpty();
    });
    JsonNode input = single(TRANSCRIPTIONS).path("input_audio");
    assertThat(input.path("format").asString()).isEqualTo("wav");
    assertThat(Base64.getDecoder().decode(input.path("data").asString()))
      .containsExactly(new ClassPathResource("openrouter-mock/media/inspection-note.wav").getContentAsByteArray());
  }

  @Test
  void verboseJsonReturnsSegmentsWordsAndSpeakers() {
    run((speech, transcription) -> {
      AudioTranscriptionResponse response = transcription.call(new AudioTranscriptionPrompt(
          new ClassPathResource("openrouter-mock/media/inspection-note.mp3"),
          OpenRouterAudioTranscriptionOptions.builder()
            .responseFormat(OpenRouterTranscriptionFormat.VERBOSE_JSON)
            .timestampGranularities(List.of(OpenRouterTimestampGranularity.WORD))
            .diarize(true)
            .build()));

      OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
      assertThat(metadata.task()).isEqualTo("transcribe");
      assertThat(metadata.duration()).isEqualTo(4.6);
      assertThat(metadata.segments()).extracting(OpenRouterTranscriptionMetadata.Segment::speaker)
        .containsExactly(0, 1);
      assertThat(metadata.words()).extracting(OpenRouterTranscriptionMetadata.Word::speakerLabel)
        .containsExactly("technician", "technician", "advisor");
      assertThat(metadata.usage().totalTokens()).isNull();
    });
    JsonNode request = single(TRANSCRIPTIONS);
    assertThat(request.path("input_audio").path("format").asString()).isEqualTo("mp3");
    assertThat(request.path("response_format").asString()).isEqualTo("verbose_json");
    assertThat(request.path("timestamp_granularities").toString()).isEqualTo("[\"word\"]");
    assertThat(request.path("diarize").asBoolean()).isTrue();
  }

  @Test
  void aRemoteRecordingIsSentAsAUrlWhenAskedTo() throws MalformedURLException {
    UrlResource recording = new UrlResource("https://garage.example/recordings/bay-3.webm");
    run((speech, transcription) -> transcription.call(new AudioTranscriptionPrompt(recording,
        OpenRouterAudioTranscriptionOptions.builder().sendAudioUrl(true).build())));

    JsonNode input = single(TRANSCRIPTIONS).path("input_audio");
    assertThat(input.path("url").asString()).isEqualTo("https://garage.example/recordings/bay-3.webm");
    assertThat(input.path("format").asString()).isEqualTo("webm");
    assertThat(input.has("data")).isFalse();
  }

  @Test
  void aTooLargeUploadIsClassifiedAndNotRetried() {
    run((speech, transcription) -> assertThatThrownBy(() -> transcription.call(new AudioTranscriptionPrompt(
        new ClassPathResource("openrouter-mock/media/inspection-note.wav"),
        OpenRouterAudioTranscriptionOptions.builder().model("synthetic/too-large").build())))
      .isInstanceOf(OpenRouterNonTransientApiException.class)
      .satisfies(failure -> assertThat(((OpenRouterHttpFailure) failure).getStatusCode().value())
        .isEqualTo(413)));
    assertThat(OpenRouterMock.requests(TRANSCRIPTIONS)).hasSize(1);
  }

  private void run(BiConsumer<TextToSpeechModel, TranscriptionModel> test) {
    this.contextRunner.run(context -> {
      if (context.getStartupFailure() != null) {
        throw context.getStartupFailure();
      }
      test.accept(context.getBean(TextToSpeechModel.class), context.getBean(TranscriptionModel.class));
    });
    assertThat(OpenRouterMock.unmatched()).isEmpty();
  }

  private static JsonNode single(String path) {
    List<JsonNode> requests = OpenRouterMock.requests(path);
    assertThat(requests).hasSize(1);
    return requests.get(0);
  }

  private static byte[] fixture(String name) {
    try {
      return new ClassPathResource("openrouter-mock/__files/" + name).getContentAsByteArray();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
