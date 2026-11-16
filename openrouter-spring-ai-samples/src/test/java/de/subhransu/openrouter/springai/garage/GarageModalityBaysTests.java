package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.image.ImageResponseMetadata;

@Tag("samples")
@Tag("samples.scenes")
class GarageModalityBaysTests {

  @Test
  void acceptsOnlyWellFormedSvgRoots() throws Exception {
    GarageModalityBays bays = new GarageModalityBays(
        null, null, null, Path.of("target"), "embedding", "vision", "image", null, null);
    String valid = "<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'><rect width='1' height='1'/></svg>";
    assertThat(bays.recordDimensions(new LinkedHashMap<>(),
        valid.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml")).isTrue();
    for (String invalid : List.of("", "arbitrary text", "<svg", "<html/>", "<svg/>")) {
      assertThat(bays.recordDimensions(new LinkedHashMap<>(),
          invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8), "image/svg+xml")).isFalse();
    }
  }

  @Test
  void rejectsSvgThatReferencesAReadableExternalEntity(@TempDir Path directory) throws Exception {
    Path entity = Files.writeString(directory.resolve("entity.txt"), "synthetic-entity-text");
    byte[] svg = ("<!DOCTYPE svg [<!ENTITY x SYSTEM '" + entity.toUri() + "'>]>"
        + "<svg xmlns='http://www.w3.org/2000/svg'>&x;</svg>").getBytes(StandardCharsets.UTF_8);

    // Control: a parser allowed to read external entities expands this fixture, so the
    // rejection below comes from the hardening and not from an unreadable target.
    DocumentBuilderFactory permissive = DocumentBuilderFactory.newInstance();
    permissive.setNamespaceAware(true);
    permissive.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "all");
    assertThat(permissive.newDocumentBuilder().parse(new ByteArrayInputStream(svg))
        .getDocumentElement().getTextContent()).isEqualTo("synthetic-entity-text");

    GarageModalityBays bays = new GarageModalityBays(
        null, null, null, Path.of("target"), "embedding", "vision", "image", null, null);
    assertThat(bays.recordDimensions(new LinkedHashMap<>(), svg, "image/svg+xml")).isFalse();
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
        null, null, null, Path.of("target"), "embedding", "vision", "image", null, null);
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
        "openrouter.image.usage", new OpenRouterUsage(1, 1, 2, 0, 0, 0.03, Map.of()));
    when(imageModel.call(any(ImagePrompt.class)))
        .thenReturn(new ImageResponse(List.of(), metadata));
    GarageModalityBays bays =
        new GarageModalityBays(
            null, null, imageModel, Path.of("target"), "embedding", "vision", "image", null, null);

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
            chatModel, null, null, Path.of("target"), "embedding", "vision", "image", null, null);

    Map<String, Object> probe = bays.runChatPaintBay("cost accounting");

    assertThat(probe.get("status")).isEqualTo("failed");
    assertThat(GarageCosts.usageMaps(probe)).isEqualTo(0.04);
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
