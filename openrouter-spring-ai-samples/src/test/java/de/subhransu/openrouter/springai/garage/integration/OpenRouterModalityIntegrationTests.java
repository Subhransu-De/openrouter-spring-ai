package de.subhransu.openrouter.springai.garage.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterApiAutoConfiguration;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatAutoConfiguration;
import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Library surfaces the Garage scenes never send (PDF, audio, and video input, audio output,
 * and provider errors), exercised through the starter's auto-configured {@link ChatModel}
 * against the same OpenRouter mock as the Garage run.
 */
// Keep media names and wire field names explicit in each case.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class OpenRouterModalityIntegrationTests {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
      .withBean(RetryTemplate.class, () -> new RetryTemplate(RetryPolicy.builder()
          .maxRetries(2)
          .delay(Duration.ofMillis(10))
          .includes(TransientAiException.class, org.springframework.web.client.ResourceAccessException.class)
          .build()));

  @BeforeEach
  void resetMock() {
    OpenRouterMock.reset();
  }

  static Stream<Arguments> inlineInputs() {
    List<Arguments> cases = new ArrayList<>();
    for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
      for (String[] input : new String[][] {
          {"dashboard.png", "image/png"},
          {"job-card.pdf", "application/pdf"},
          {"inspection-note.wav", "audio/wav"},
          {"inspection-note.mp3", "audio/mpeg"},
          {"walkaround.mp4", "video/mp4"},
          {"walkaround.mpeg", "video/mpeg"},
          {"walkaround.mov", "video/mov"},
          {"walkaround.webm", "video/webm"}}) {
        cases.add(Arguments.of(mode, input[0], input[1]));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("inlineInputs")
  void inlineInputsReachTheWireAsTheirContentPart(OpenRouterRequestMode mode, String file, String mime)
      throws IOException {
    byte[] bytes = new ClassPathResource("openrouter-mock/media/" + file).getContentAsByteArray();
    String base64 = Base64.getEncoder().encodeToString(bytes);
    String dataUrl = "data:" + mime + ";base64," + base64;
    Media media = Media.builder().mimeType(MimeTypeUtils.parseMimeType(mime)).name(file).data(bytes).build();

    String reply = call(mode, media);

    assertThat(reply).isEqualTo("Received the attachment for the inspection.");
    boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
    String expected = switch (mime) {
      case "image/png" -> responses
          ? "{\"type\":\"input_image\",\"image_url\":\"" + dataUrl + "\"}"
          : "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUrl + "\"}}";
      case "application/pdf" -> responses
          ? "{\"type\":\"input_file\",\"filename\":\"" + file + "\",\"file_data\":\"" + dataUrl + "\"}"
          : "{\"type\":\"file\",\"file\":{\"filename\":\"" + file + "\",\"file_data\":\"" + dataUrl + "\"}}";
      case "audio/wav", "audio/mpeg" -> "{\"type\":\"input_audio\",\"input_audio\":{\"data\":\"" + base64
          + "\",\"format\":\"" + ("audio/wav".equals(mime) ? "wav" : "mp3") + "\"}}";
      default -> responses
          ? "{\"type\":\"input_video\",\"video_url\":\"" + dataUrl + "\"}"
          : "{\"type\":\"video_url\",\"video_url\":{\"url\":\"" + dataUrl + "\"}}";
    };
    assertThat(attachmentPart(mode)).isEqualTo(JSON.readTree(expected));
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void remoteDocumentsAndVideosStayUrls(OpenRouterRequestMode mode) {
    boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
    String pdf = "https://garage.example/job-card.pdf";
    String video = "https://garage.example/walkaround.webm";

    call(mode, Media.builder().mimeType(MimeTypeUtils.parseMimeType("application/pdf"))
        .name("job-card.pdf").data(java.net.URI.create(pdf)).build());
    JsonNode document = attachmentPart(mode);
    call(mode, Media.builder().mimeType(MimeTypeUtils.parseMimeType("video/webm"))
        .data(java.net.URI.create(video)).build());
    JsonNode clip = attachmentPart(mode);

    if (responses) {
      assertThat(document.path("type").asString()).isEqualTo("input_file");
      assertThat(document.path("file_url").asString()).isEqualTo(pdf);
      assertThat(document.has("file_data")).isFalse();
      assertThat(clip.path("video_url").asString()).isEqualTo(video);
    } else {
      assertThat(document.path("file").path("file_data").asString()).isEqualTo(pdf);
      assertThat(clip.path("video_url").path("url").asString()).isEqualTo(video);
    }
  }

  @Test
  void unsupportedInputsFailBeforeAnyRequestIsSent() {
    int before = OpenRouterMock.server().getAllServeEvents().size();
    for (Media media : List.of(
        Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/wav"))
            .data(java.net.URI.create("https://garage.example/note.wav")).build(),
        Media.builder().mimeType(MimeTypeUtils.parseMimeType("audio/ogg")).data(new byte[] {1, 2}).build(),
        Media.builder().mimeType(MimeTypeUtils.parseMimeType("application/pdf")).name("job-card.pdf")
            .data(java.net.URI.create("ftp://garage.example/job-card.pdf")).build())) {
      assertThatThrownBy(() -> call(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, media))
          .as(media.getMimeType() + " " + media.getData())
          .satisfies(failure -> assertThat(causes(failure))
              .anyMatch(IllegalArgumentException.class::isInstance));
    }
    assertThat(OpenRouterMock.server().getAllServeEvents()).hasSize(before);
  }

  @Test
  void streamedAudioOutputIsAssembledIntoOneMediaAttachment() {
    run(model -> {
      List<ChatResponse> responses = model.stream(new Prompt("Read the service note aloud.",
          OpenRouterChatOptions.builder()
              .model("synthetic/audio")
              .modalities(List.of("text", "audio"))
              .audio(new OpenRouterAudioOptions("alloy", "pcm16"))
              .metadata(Map.of("suite", "audio-output"))
              .build())).collectList().block(Duration.ofSeconds(10));

      assertThat(responses).isNotEmpty();
      // The final usage chunk carries no generation.
      List<Media> media = responses.stream()
          .filter(response -> response.getResult() != null)
          .flatMap(response -> response.getResult().getOutput().getMedia().stream())
          .toList();
      assertThat(media).singleElement().satisfies(audio -> {
        assertThat(audio.getMimeType().getType()).isEqualTo("audio");
        byte[] expected = new byte[48];
        for (int i = 0; i < expected.length; i++) {
          expected[i] = (byte) i;
        }
        assertThat(audio.getDataAsByteArray()).containsExactly(expected);
      });
    });
    JsonNode request = lastRequest("/api/v1/chat/completions");
    assertThat(request.path("modalities").toString()).isEqualTo("[\"text\",\"audio\"]");
    assertThat(request.path("audio").path("format").asString()).isEqualTo("pcm16");
  }

  @Test
  void clientErrorsAreNotRetried() {
    for (int status : new int[] {400, 401}) {
      int before = requestsFor("error-" + status);
      assertThatThrownBy(() -> call(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, null, "error-" + status))
          .as("HTTP " + status)
          .isInstanceOf(OpenRouterNonTransientApiException.class)
          .satisfies(failure -> assertThat(((OpenRouterHttpException) failure).getStatusCode().value())
              .isEqualTo(status));
      assertThat(requestsFor("error-" + status) - before).isEqualTo(1);
    }
  }

  @Test
  void aRateLimitIsRetriedAndThenSucceeds() {
    assertThat(call(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, null, "error-429"))
        .isEqualTo("Received the attachment for the inspection.");
    assertThat(requestsFor("error-429")).isEqualTo(2);
  }

  @Test
  void aPersistentServerErrorFailsAfterTheRetryBudget() {
    assertThatThrownBy(() -> call(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, null, "error-503"))
        .isInstanceOf(OpenRouterTransientApiException.class)
        .satisfies(failure -> assertThat(((OpenRouterHttpException) failure).getStatusCode().value())
            .isEqualTo(503));
    assertThat(requestsFor("error-503")).isEqualTo(3);
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void aFailureAfterStreamedContentSurfacesAsAnError(OpenRouterRequestMode mode) {
    run(model -> {
      List<String> received = new ArrayList<>();
      AtomicReference<Throwable> failure = new AtomicReference<>();
      model.stream(new Prompt("Describe the cooling fault.", options(mode, "mid-stream-error")))
          .doOnNext(response -> received.add(response.getResult() != null
              ? String.valueOf(response.getResult().getOutput().getText()) : ""))
          .onErrorResume(error -> {
            failure.set(error);
            return Flux.empty();
          })
          .blockLast(Duration.ofSeconds(10));
      // Chat Completions and Responses raise different exception types; both keep the provider error.
      OpenRouterErrorDetails details = failure.get() instanceof OpenRouterHttpException http
          ? http.getErrorDetails()
          : ((OpenRouterApiException) failure.get()).getErrorDetails();
      assertThat(details).isNotNull();
      assertThat(details.code()).isEqualTo("server_error");
      assertThat(details.message()).isEqualTo("The upstream provider returned an error");
      assertThat(String.join("", received)).contains("The cooling system ");
    });
  }

  @Test
  void aProviderSlowerThanTheConnectionTimeoutFailsAfterTheRetryBudget() {
    long started = System.nanoTime();
    assertThatThrownBy(() -> this.contextRunner
        .withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
            "spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl(),
            "spring.ai.openrouter.connection.timeout=500ms")
        .run(context -> context.getBean(ChatModel.class).call(new Prompt("Is the pickup ready?",
            options(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS, "slow-provider")))))
        .satisfies(failure -> assertThat(causes(failure))
            .anyMatch(cause -> cause instanceof TransientAiException
                || cause instanceof org.springframework.web.client.ResourceAccessException));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
    assertThat(requestsFor("slow-provider")).isEqualTo(3);
  }

  private String call(OpenRouterRequestMode mode, Media media) {
    return call(mode, media, "modality-matrix");
  }

  private String call(OpenRouterRequestMode mode, Media media, String suite) {
    List<String> reply = new ArrayList<>();
    run(model -> {
      UserMessage.Builder message = UserMessage.builder().text("Inspect the attachment.");
      if (media != null) {
        message.media(List.of(media));
      }
      ChatResponse response = model.call(new Prompt(List.of(message.build()), options(mode, suite)));
      reply.add(response.getResult().getOutput().getText());
    });
    return reply.get(0);
  }

  private void run(java.util.function.Consumer<ChatModel> test) {
    this.contextRunner
        .withPropertyValues("spring.ai.openrouter.api-key=garage-mock-key",
            "spring.ai.openrouter.base-url=" + OpenRouterMock.baseUrl())
        .run(context -> {
          if (context.getStartupFailure() != null) {
            throw context.getStartupFailure();
          }
          test.accept(context.getBean(ChatModel.class));
        });
  }

  private static OpenRouterChatOptions options(OpenRouterRequestMode mode, String suite) {
    return OpenRouterChatOptions.builder()
        .model("synthetic/multimodal")
        .requestMode(mode)
        .metadata(Map.of("suite", suite))
        .build();
  }

  private JsonNode attachmentPart(OpenRouterRequestMode mode) {
    boolean responses = mode == OpenRouterRequestMode.OPENAI_RESPONSES;
    JsonNode request = lastRequest(responses ? "/api/v1/responses" : "/api/v1/chat/completions");
    JsonNode messages = request.path(responses ? "input" : "messages");
    JsonNode parts = messages.get(messages.size() - 1).path("content");
    assertThat(parts.get(0).path("type").asString()).isEqualTo(responses ? "input_text" : "text");
    return parts.get(1);
  }

  private JsonNode lastRequest(String path) {
    List<LoggedRequest> requests = OpenRouterMock.server().findAll(postRequestedFor(urlEqualTo(path)));
    assertThat(requests).isNotEmpty();
    return JSON.readTree(requests.get(requests.size() - 1).getBodyAsString());
  }

  private static List<Throwable> causes(Throwable failure) {
    List<Throwable> chain = new ArrayList<>();
    for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
      chain.add(current);
    }
    return chain;
  }

  private int requestsFor(String suite) {
    return (int) OpenRouterMock.server().findAll(postRequestedFor(urlEqualTo("/api/v1/chat/completions"))).stream()
        .filter(request -> JSON.readTree(request.getBodyAsString()).path("metadata").path("suite")
            .asString("").equals(suite))
        .count();
  }
}
