package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.image.OpenRouterImageGenerationMetadata;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;

class GarageModalityBaysTests {

  private static final String VALID_SVG =
      "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><rect width='1' height='1'/></svg>";

  // A one-pixel lossy WebP.
  private static final byte[] WEBP = Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");

  @TempDir Path output;

  @Test
  void aValidSvgDocumentPassesAsAVectorImage() {
    Map<String, Object> probe = paintBay(VALID_SVG.getBytes(StandardCharsets.UTF_8), "image/svg+xml");

    assertThat(probe).containsEntry("status", "passed").containsEntry("dimensions", "vector");
  }

  @ParameterizedTest
  @ValueSource(strings = {"arbitrary text", "<svg", "<html/>", "<svg/>",
      "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///synthetic-do-not-read'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>"})
  void invalidSvgFailsWithoutResolvingExternalEntities(String invalid) {
    Map<String, Object> probe = paintBay(invalid.getBytes(StandardCharsets.UTF_8), "image/svg+xml");

    assertThat(probe).containsEntry("status", "failed")
        .containsEntry("error", "generated image was empty or could not be decoded");
  }

  @Test
  void aWebpImageIsDecodedToItsPixelDimensions() {
    Map<String, Object> probe = paintBay(WEBP, "image/webp");

    assertThat(probe).containsEntry("status", "passed").containsEntry("width", 1).containsEntry("height", 1);
  }

  static Stream<Arguments> undecodableWebpContainers() {
    byte[] corrupt = WEBP.clone();
    corrupt[4] = 0;
    return Stream.of(
        Arguments.of(Named.of("truncated", java.util.Arrays.copyOf(WEBP, WEBP.length - 1))),
        Arguments.of(Named.of("corrupt length", corrupt)),
        Arguments.of(Named.of("header only", new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'})),
        Arguments.of(Named.of("not WebP", new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'N', 'O', 'P', 'E'})));
  }

  @ParameterizedTest
  @MethodSource("undecodableWebpContainers")
  void anUndecodableWebpContainerFailsTheBay(byte[] container) {
    Map<String, Object> probe = paintBay(container, "image/webp");

    assertThat(probe).containsEntry("status", "failed")
        .containsEntry("error", "generated image was empty or could not be decoded")
        .doesNotContainKeys("width", "height");
  }

  @Test
  void anEmptyPayloadFailsAsMissingImageData() {
    Map<String, Object> probe = paintBay(new byte[0], "image/webp");

    assertThat(probe).containsEntry("status", "failed")
        .containsEntry("error", "image response carried no base64 image data");
  }

  /** Runs the image API bay against a model that returns the given image. */
  private Map<String, Object> paintBay(byte[] image, String mediaType) {
    ImageModel imageModel = mock(ImageModel.class);
    ImageGeneration generation = new ImageGeneration(new Image(null, Base64.getEncoder().encodeToString(image)),
        new OpenRouterImageGenerationMetadata(mediaType, null));
    when(imageModel.call(any(ImagePrompt.class)))
        .thenReturn(new ImageResponse(List.of(generation), new ImageResponseMetadata()));
    GarageModalityBays bays = new GarageModalityBays(null, null, imageModel, this.output, "embedding", "vision",
        "image", null, null);
    return bays.runPaintBay("webp decoding");
  }

  @Test
  void retainsImageApiCostWhenThePayloadIsMissing() {
    ImageModel imageModel = mock(ImageModel.class);
    ImageResponseMetadata metadata = new ImageResponseMetadata();
    metadata.put(
        "openrouter.usage", new OpenRouterUsage(1, 1, 2, 0, 0, 0.03, Map.of()));
    when(imageModel.call(any(ImagePrompt.class)))
        .thenReturn(new ImageResponse(List.of(), metadata));
    GarageModalityBays bays =
        new GarageModalityBays(
            null, null, imageModel, Path.of("target"), "embedding", "vision", "image", null, null);

    Map<String, Object> probe = bays.runPaintBay("cost accounting");

    assertThat(probe.get("status")).isEqualTo("failed");
    assertThat(GarageCosts.usageMaps(probe)).isCloseTo(0.03, within(1e-12));
  }

  @Test
  void retainsChatImageCostWhenThePayloadIsMissing() {
    ChatModel chatModel = mock(ChatModel.class);
    OpenRouterUsage usage = new OpenRouterUsage(1, 1, 2, 0, 0, 0.04, Map.of());
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(
                List.of(), ChatResponseMetadata.builder().usage(usage).build()));
    GarageModalityBays bays =
        new GarageModalityBays(
            chatModel, null, null, Path.of("target"), "embedding", "vision", "image", null, null);

    Map<String, Object> probe = bays.runChatPaintBay("cost accounting");

    assertThat(probe.get("status")).isEqualTo("failed");
    assertThat(GarageCosts.usageMaps(probe)).isCloseTo(0.04, within(1e-12));
  }

  @Test
  void chatBaysRouteWithTheSharedProviderPreferences() {
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(), ChatResponseMetadata.builder().build()));
    GarageProperties properties = new GarageProperties();
    properties.setProviderRequireParameters(true);
    properties.setProviderSort("price");
    GarageModalityBays bays =
        new GarageModalityBays(
            chatModel, null, null, Path.of("target"), "embedding", "vision", "image", null,
            GarageOptionsFactory.serviceProviderPreferences(properties));

    bays.runChatPaintBay("cost accounting");

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    OpenRouterChatOptions options = (OpenRouterChatOptions) prompt.getValue().getOptions();
    assertThat(options.getProvider()).isNotNull();
    assertThat(options.getProvider().requireParameters()).isTrue();
    assertThat(options.getProvider().sort()).isEqualTo("price");
  }
}
