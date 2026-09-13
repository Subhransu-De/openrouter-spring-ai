package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;

class GarageModalityBaysTests {

  @Test
  void validatesSvgDocumentsWithoutResolvingExternalEntities() throws Exception {
    GarageModalityBays bays = new GarageModalityBays(
        null, null, null, Path.of("target"), "embedding", "vision", "image", null);
    String valid = "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><rect width='1' height='1'/></svg>";
    assertThat(bays.recordDimensions(new LinkedHashMap<>(),
        valid.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml")).isTrue();
    for (String invalid : List.of("", "arbitrary text", "<svg", "<html/>", "<svg/>",
        "<!DOCTYPE svg [<!ENTITY x SYSTEM 'file:///synthetic-do-not-read'>]><svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>")) {
      assertThat(bays.recordDimensions(new LinkedHashMap<>(),
          invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml")).isFalse();
    }
  }

  @Test
  void rejectsAWebpHeaderWithoutImageData() {
    byte[] webp = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P'};

    assertThat(GarageModalityBays.hasWebpSignature(webp)).isFalse();
    assertThat(
            GarageModalityBays.hasWebpSignature(
                new byte[] {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'N', 'O', 'P', 'E'}))
        .isFalse();
    assertThat(GarageModalityBays.hasWebpSignature(new byte[0])).isFalse();
  }

  @Test
  void decodesWebpPixelsAndRejectsTruncatedContainers() throws Exception {
    byte[] webp = Base64.getDecoder().decode(
        "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
    GarageModalityBays bays = new GarageModalityBays(
        null, null, null, Path.of("target"), "embedding", "vision", "image", null);
    Map<String, Object> probe = new LinkedHashMap<>();
    assertThat(bays.recordDimensions(probe, webp, "image/webp")).isTrue();
    assertThat(probe).containsEntry("width", 1).containsEntry("height", 1);
    assertThat(bays.recordDimensions(new LinkedHashMap<>(),
        java.util.Arrays.copyOf(webp, webp.length - 1), "image/webp")).isFalse();
    webp[4] = 0;
    assertThat(bays.recordDimensions(new LinkedHashMap<>(), webp, "image/webp")).isFalse();
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
            null, null, imageModel, Path.of("target"), "embedding", "vision", "image", null);

    Map<String, Object> probe = bays.runPaintBay("cost accounting");

    assertThat(probe.get("status")).isEqualTo("failed");
    assertThat(GarageCosts.usageMaps(probe)).isEqualTo(0.03);
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
            chatModel, null, null, Path.of("target"), "embedding", "vision", "image", null);

    Map<String, Object> probe = bays.runChatPaintBay("cost accounting");

    assertThat(probe.get("status")).isEqualTo("failed");
    assertThat(GarageCosts.usageMaps(probe)).isEqualTo(0.04);
  }
}
