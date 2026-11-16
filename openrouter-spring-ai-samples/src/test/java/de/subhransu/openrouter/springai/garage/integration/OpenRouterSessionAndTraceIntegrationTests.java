package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioSpeechOptions;
import de.subhransu.openrouter.springai.audio.OpenRouterAudioTranscriptionOptions;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterAudioSpeechAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterAudioTranscriptionAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterEmbeddingAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterImageAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterTrace;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.audio.tts.TextToSpeechPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code session_id} and {@code trace} on the wire of every endpoint, sent by the
 * auto-configured models to the OpenRouter mock. Boot defaults reach each endpoint, and a
 * request value replaces them in both chat request modes, in calls and streams.
 */
@Tag("samples")
@Tag("samples.integration")
class OpenRouterSessionAndTraceIntegrationTests {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String DEFAULT_TRACE = "{\"trace_name\":\"garage\",\"bay\":\"3\"}";

  // The attribution stubs answer both request modes, in calls and streams.
  private static final Map<String, Object> ATTRIBUTION = Map.of("sceneId", "attribution-check-in");

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(OpenRouterApiAutoConfiguration.class,
          OpenRouterChatAutoConfiguration.class, OpenRouterEmbeddingAutoConfiguration.class,
          OpenRouterImageAutoConfiguration.class, OpenRouterAudioSpeechAutoConfiguration.class,
          OpenRouterAudioTranscriptionAutoConfiguration.class))
      .withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
          "spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(),
          "spring.ai.model.audio.speech=openrouter", "spring.ai.model.audio.transcription=openrouter",
          "spring.ai.openrouter.chat.model=synthetic/chat",
          "spring.ai.openrouter.embedding.model=synthetic/embedding",
          "spring.ai.openrouter.image.model=synthetic/image",
          "spring.ai.openrouter.audio.speech.model=synthetic/speech",
          "spring.ai.openrouter.audio.transcription.model=synthetic/transcription")
      .withPropertyValues(defaults("chat"))
      .withPropertyValues(defaults("embedding"))
      .withPropertyValues(defaults("image"))
      .withPropertyValues(defaults("audio.speech"))
      .withPropertyValues(defaults("audio.transcription"));

  @BeforeEach
  void resetMock() {
    OpenRouterMock.reset();
  }

  @Test
  void bootDefaultsReachEveryEndpoint() {
    run(context -> {
      // ChatClient merges request options onto the model defaults; a raw ChatModel call
      // would replace them.
      ChatClient chat = ChatClient.create(context.getBean(ChatModel.class));
      for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
        chat.prompt("Is the car ready?")
            .options(OpenRouterChatOptions.builder().requestMode(mode).metadata(ATTRIBUTION)).call().content();
      }
      context.getBean(EmbeddingModel.class)
          .embed(List.of("brakes", "tyres", "oil", "battery", "wipers"));
      OpenRouterImageModel images = context.getBean(OpenRouterImageModel.class);
      images.call(new ImagePrompt("A clean service bay."));
      images.stream(new ImagePrompt("A clean service bay.")).blockLast(Duration.ofSeconds(10));
      context.getBean(TextToSpeechModel.class).call(new TextToSpeechPrompt("Ready for pickup."));
      context.getBean(TranscriptionModel.class).call(new AudioTranscriptionPrompt(
          new ClassPathResource("openrouter-mock/media/inspection-note.wav")));
    });

    // The image path is called and streamed, so it has two requests.
    for (String path : List.of("/chat/completions", "/responses", "/embeddings", "/images", "/audio/speech",
        "/audio/transcriptions")) {
      List<JsonNode> requests = OpenRouterMock.requests(path);
      assertThat(requests).as(path).hasSize("/images".equals(path) ? 2 : 1).allSatisfy(request -> {
        assertThat(request.path("session_id").asString()).isEqualTo("garage-default");
        assertThat(request.path("trace")).isEqualTo(JSON.readTree(DEFAULT_TRACE));
      });
    }
    assertThat(OpenRouterMock.requests("/images").get(1).path("stream").asBoolean()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void aChatRequestValueReplacesTheDefaultsInCallsAndStreams(OpenRouterRequestMode mode) {
    OpenRouterChatOptions options = OpenRouterChatOptions.builder()
        .requestMode(mode)
        .metadata(ATTRIBUTION)
        .sessionId("conversation-7")
        .trace(OpenRouterTrace.builder().traceId("run-7").generationName("answer").build())
        .build();
    run(context -> {
      ChatModel chat = context.getBean(ChatModel.class);
      chat.call(new Prompt("Is the car ready?", options));
      chat.stream(new Prompt("Is the car ready?", options)).blockLast(Duration.ofSeconds(10));
    });

    List<JsonNode> requests = OpenRouterMock.requests(
        mode == OpenRouterRequestMode.RESPONSES ? "/responses" : "/chat/completions");
    assertThat(requests).hasSize(2);
    assertThat(requests.get(1).path("stream").asBoolean()).isTrue();
    assertThat(requests).allSatisfy(request -> {
      assertThat(request.path("session_id").asString()).isEqualTo("conversation-7");
      // A request trace replaces the default trace as a whole.
      assertThat(request.path("trace"))
          .isEqualTo(JSON.readTree("{\"trace_id\":\"run-7\",\"generation_name\":\"answer\"}"));
    });
  }

  @Test
  void requestValuesReplaceTheDefaultsForEmbeddingsImagesAndAudio() {
    OpenRouterTrace trace = OpenRouterTrace.builder().traceId("run-8").build();
    run(context -> {
      context.getBean(EmbeddingModel.class).call(new EmbeddingRequest(
          List.of("brakes", "tyres", "oil", "battery", "wipers"),
          OpenRouterEmbeddingOptions.builder().sessionId("triage-8").trace(trace).build()));
      context.getBean(ImageModel.class).call(new ImagePrompt("A clean service bay.",
          OpenRouterImageOptions.builder().sessionId("triage-8").trace(trace).build()));
      context.getBean(TextToSpeechModel.class).stream(new TextToSpeechPrompt("Ready for pickup.",
          OpenRouterAudioSpeechOptions.builder().sessionId("triage-8").trace(trace).build()))
          .blockLast(Duration.ofSeconds(10));
      context.getBean(TranscriptionModel.class).call(new AudioTranscriptionPrompt(
          new ClassPathResource("openrouter-mock/media/inspection-note.wav"),
          OpenRouterAudioTranscriptionOptions.builder().sessionId("triage-8").trace(trace).build()));
    });

    for (String path : List.of("/embeddings", "/images", "/audio/speech", "/audio/transcriptions")) {
      assertThat(OpenRouterMock.requests(path)).as(path).singleElement().satisfies(request -> {
        assertThat(request.path("session_id").asString()).isEqualTo("triage-8");
        assertThat(request.path("trace")).isEqualTo(JSON.readTree("{\"trace_id\":\"run-8\"}"));
      });
    }
  }

  private void run(Consumer<ApplicationContext> test) {
    this.contextRunner.run(context -> {
      if (context.getStartupFailure() != null) {
        throw context.getStartupFailure();
      }
      test.accept(context);
    });
    assertThat(OpenRouterMock.unmatched()).isEmpty();
  }

  private static String[] defaults(String model) {
    String prefix = "spring.ai.openrouter." + model + ".";
    return new String[] {prefix + "session-id=garage-default", prefix + "trace.trace-name=garage",
        prefix + "trace.metadata.bay=3"};
  }
}
