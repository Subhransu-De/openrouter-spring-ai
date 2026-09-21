package de.subhransu.openrouter.springai.garage;

import org.jspecify.annotations.Nullable;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.ERROR;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.FAILED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.PASSED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.STATUS;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.USAGE;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.image.OpenRouterImageGenerationMetadata;
import de.subhransu.openrouter.springai.image.OpenRouterImageModel;
import de.subhransu.openrouter.springai.image.OpenRouterImageOptions;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.Assert;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;

/**
 * The Garage's newer bays: an embeddings-backed triage matcher, a digital inspection bay
 * that reads a real photo (image input), and a paint bay that generates images through
 * both library surfaces (the unified Image API and chat-completions modalities). Each bay
 * returns a structured evidence map; failures are recorded and fail the run after reporting.
 */
public final class GarageModalityBays {

  private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(3);
  private static final String DASHBOARD_PHOTO = "garage/dashboard-warning.png";

  private static final List<String> TRIAGE_CATALOGUE =
      List.of(
          "cooling system fault: engine overheating, coolant loss, radiator or thermostat issues",
          "ignition and fuel delivery fault: hard starts, rough idle, stalling, misfires",
          "braking system fault: vibration under braking, pulling to one side, soft pedal",
          "electrical fault: dead battery, dim lights, intermittent accessories");

  private final ChatModel chatModel;
  private final EmbeddingModel embeddingModel;
  private final ImageModel imageModel;
  private final Path outputDirectory;
  private final String embeddingModelId;
  private final String visionModelId;
  private final String imageModelId;
  private final @Nullable String imageQuality;
  private final @Nullable OpenRouterProviderPreferences provider;

  public GarageModalityBays(
      ChatModel chatModel,
      EmbeddingModel embeddingModel,
      ImageModel imageModel,
      Path outputDirectory,
      String embeddingModelId,
      String visionModelId,
      String imageModelId,
      @Nullable String imageQuality,
      @Nullable OpenRouterProviderPreferences provider) {
    this.chatModel = chatModel;
    this.embeddingModel = embeddingModel;
    this.imageModel = imageModel;
    this.outputDirectory = outputDirectory;
    this.embeddingModelId = embeddingModelId;
    this.visionModelId = visionModelId;
    this.imageModelId = imageModelId;
    this.imageQuality = imageQuality;
    this.provider = provider;
  }

  /** Embeds the customer topic against the symptom catalogue and picks the closest match. */
  public Map<String, Object> runTriageMatcher(String topic) {
    Map<String, Object> probe = probe("triage_matcher/embeddings", this.embeddingModelId);
    try {
      List<String> texts = new ArrayList<>();
      texts.add(topic);
      texts.addAll(TRIAGE_CATALOGUE);
      EmbeddingResponse response =
          this.embeddingModel.call(
              new EmbeddingRequest(
                  texts,
                  OpenRouterEmbeddingOptions.builder()
                      .model(this.embeddingModelId)
                      .provider(this.provider)
                      .build()));
      probe.put(USAGE, GarageResponses.usage(response.getMetadata().getUsage()));

      List<Embedding> results = response.getResults();
      float[] topicVector = results.get(0).getOutput();
      String bestMatch = null;
      double bestSimilarity = -2.0;
      Map<String, Object> similarities = new LinkedHashMap<>();
      for (int i = 1; i < results.size(); i++) {
        double similarity = cosineSimilarity(topicVector, results.get(i).getOutput());
        String candidate = TRIAGE_CATALOGUE.get(i - 1);
        similarities.put(candidate, Math.round(similarity * 10000.0) / 10000.0);
        if (similarity > bestSimilarity) {
          bestSimilarity = similarity;
          bestMatch = candidate;
        }
      }

      probe.put("vectors", results.size());
      probe.put("dimensions", topicVector.length);
      probe.put("responseModel", response.getMetadata().getModel());
      probe.put("similarities", similarities);
      probe.put("bestMatch", bestMatch);
      boolean passed = results.size() == texts.size() && topicVector.length > 0;
      probe.put(STATUS, passed ? PASSED : FAILED);
      if (!passed) {
        probe.put(ERROR, "embedding response was incomplete or empty");
      }
    } catch (RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  /**
   * Sends the bundled dashboard-warning photo as image input and checks the model actually
   * read the warning text, in the given request mode.
   */
  public Map<String, Object> runDigitalInspection(OpenRouterRequestMode requestMode) {
    Map<String, Object> probe = probe("digital_inspection/image-input", this.visionModelId);
    probe.put("requestMode", requestMode.name());
    probe.put("photo", DASHBOARD_PHOTO);
    try {
      byte[] photo = new ClassPathResource(DASHBOARD_PHOTO).getContentAsByteArray();
      UserMessage message =
          UserMessage.builder()
              .text(
                  "You are the Garage digital inspection bay. Read the attached dashboard"
                      + " warning-lamp photo. State the exact warning text you see, then one"
                      + " sentence on what the mechanic should check first.")
              .media(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(photo).build())
              .build();
      OpenRouterChatOptions options =
          OpenRouterChatOptions.builder()
              .model(this.visionModelId)
              .requestMode(requestMode)
              .temperature(0.1)
              .maxCompletionTokens(600)
              .includeUsage(requestMode == OpenRouterRequestMode.OPENAI_RESPONSES ? null : true)
              .provider(this.provider)
              .build();

      ChatResponse response = this.chatModel.call(new Prompt(List.of(message), options));
      probe.put(USAGE, GarageResponses.usage(response.getMetadata().getUsage()));
      String reply = GarageResponses.text(response);
      boolean warningRead = reply.toLowerCase(Locale.ROOT).contains("engine");

      probe.put("photoBytes", photo.length);
      probe.put("reply", reply);
      probe.put("warningTextRead", warningRead);
      boolean passed = StringUtils.hasText(reply) && warningRead;
      probe.put(STATUS, passed ? PASSED : FAILED);
      if (!passed) {
        probe.put(ERROR, "model reply did not read the CHECK ENGINE warning from the photo");
      }
    } catch (IOException | RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  /** Generates an image through the unified Image API (POST /images) and saves it. */
  public Map<String, Object> runPaintBay(String topic) {
    Map<String, Object> probe = probe("paint_bay/image-api", this.imageModelId);
    try {
      ImageResponse response =
          this.imageModel.call(
              new ImagePrompt(
                  paintPrompt(topic),
                  imageOptions()));
      recordGeneratedImage(probe, response, "paint-bay-image");
    } catch (IOException | RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  /**
   * Generates an image through the streaming Image API surface: partial previews (when the
   * provider streams them) followed by the completed image.
   */
  public Map<String, Object> runStreamingPaintBay(String topic) {
    Map<String, Object> probe = probe("paint_bay/image-api-streaming", this.imageModelId);
    try {
      OpenRouterImageModel openRouterImageModel = (OpenRouterImageModel) this.imageModel;
      List<ImageResponse> events =
          openRouterImageModel
              .stream(
                  new ImagePrompt(
                      paintPrompt(topic),
                      imageOptions()))
              .collectList()
              .block(STREAM_TIMEOUT);
      if (events == null) {
        events = List.of();
      }

      int partialEvents = 0;
      ImageResponse completed = null;
      for (ImageResponse event : events) {
        ImageGeneration generation = event.getResult();
        if (generation != null
            && generation.getMetadata()
                instanceof OpenRouterImageGenerationMetadata openRouterMetadata
            && openRouterMetadata.partialImageIndex() != null) {
          partialEvents++;
        } else {
          completed = event;
        }
      }
      probe.put("events", events.size());
      probe.put("partialImageEvents", partialEvents);
      if (completed != null) {
        recordGeneratedImage(probe, completed, "paint-bay-image-streamed");
      } else {
        probe.put(STATUS, FAILED);
        probe.put(ERROR, "stream ended without a completed image event");
      }
    } catch (IOException | RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  /**
   * Generates an image through chat completions ({@code modalities: ["image", "text"]});
   * the image arrives as assistant-message media.
   */
  public Map<String, Object> runChatPaintBay(String topic) {
    Map<String, Object> probe = probe("paint_bay/chat-modalities", this.imageModelId);
    try {
      OpenRouterChatOptions options =
          OpenRouterChatOptions.builder()
              .model(this.imageModelId)
              .modalities(List.of("image", "text"))
              .imageConfig(
                  StringUtils.hasText(this.imageQuality)
                      ? Map.of("quality", this.imageQuality)
                      : Map.of())
              // Generated images are billed as a large block of completion tokens; the
              // sample's default 900-token cap would truncate them.
              .maxCompletionTokens(8000)
              .includeUsage(true)
              .provider(this.provider)
              .build();
      ChatResponse response =
          this.chatModel.call(new Prompt(List.of(new UserMessage(paintPrompt(topic))), options));
      probe.put(USAGE, GarageResponses.usage(response.getMetadata().getUsage()));

      Assert.state(response.getResult() != null, "Image response requires a generation");
      List<Media> media = response.getResult().getOutput().getMedia();
      probe.put("mediaCount", media.size());
      probe.put("replyText", GarageResponses.text(response));
      if (media.isEmpty()) {
        probe.put(STATUS, FAILED);
        probe.put(ERROR, "assistant message carried no generated-image media");
        return probe;
      }
      Media image = media.get(0);
      byte[] bytes = decodeDataUrl(String.valueOf(image.getData()));
      Path file =
          this.outputDirectory.resolve(
              "chat-paint-bay-image." + extension(image.getMimeType().toString()));
      Files.write(file, bytes);
      probe.put("mimeType", image.getMimeType().toString());
      probe.put("imageBytes", bytes.length);
      probe.put("file", file.toString());
      boolean validImage = recordDimensions(probe, bytes, image.getMimeType().toString());
      probe.put(STATUS, bytes.length > 0 && validImage ? PASSED : FAILED);
      if (bytes.length == 0 || !validImage) {
        probe.put(ERROR, "generated-image media was empty or could not be decoded");
      }
    } catch (IOException | RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  /**
   * Single image-model compatibility check for the sweep: one generation through the
   * {@link ImageModel} bean with the given per-entry config, optionally pinned to one
   * provider, saved under the given file stem.
   */
  public Map<String, Object> runImageModelCheck(
      String modelId, @Nullable String providerTag, Map<String, String> config, String fileStem) {
    Map<String, Object> probe = probe("image_sweep", modelId);
    probe.put("providerPin", providerTag != null ? providerTag : "default-routing");
    probe.put("config", config.isEmpty() ? "defaults" : config.toString());
    try {
      OpenRouterImageOptions.Builder options = OpenRouterImageOptions.builder().model(modelId).n(1);
      for (Map.Entry<String, String> option : config.entrySet()) {
        switch (option.getKey()) {
          case "resolution" -> options.resolution(option.getValue());
          case "quality" -> options.quality(option.getValue());
          case "aspect-ratio" -> options.aspectRatio(option.getValue());
          case "output-format" -> options.outputFormat(option.getValue());
          default ->
              throw new IllegalArgumentException("unknown image sweep option: " + option.getKey());
        }
      }
      Map<String, @Nullable Object> providerOptions =
          GarageOptionsFactory.imageProviderOptions(this.provider, providerTag);
      if (providerOptions != null) {
        options.providerOptions(providerOptions);
      }
      ImageResponse response =
          this.imageModel.call(
              new ImagePrompt(
                  "A small workshop poster illustration of a vintage pickup truck in a garage,"
                      + " flat colors",
                  options.build()));
      recordGeneratedImage(probe, response, fileStem);
    } catch (IOException | RuntimeException ex) {
      fail(probe, ex);
    }
    return probe;
  }

  private void recordGeneratedImage(Map<String, Object> probe, ImageResponse response, String stem)
      throws IOException {
    Object usage = response.getMetadata().get("openrouter.usage");
    if (usage instanceof OpenRouterUsage openRouterUsage) {
      probe.put(USAGE, GarageResponses.usage(openRouterUsage));
    }
    ImageGeneration generation = response.getResult();
    Image image = generation != null ? generation.getOutput() : null;
    String b64Json = image != null ? image.getB64Json() : null;
    if (generation == null || !StringUtils.hasText(b64Json)) {
      probe.put(STATUS, FAILED);
      probe.put(ERROR, "image response carried no base64 image data");
      return;
    }
    String mediaType = "image/png";
    if (generation.getMetadata() instanceof OpenRouterImageGenerationMetadata openRouterMetadata
        && StringUtils.hasText(openRouterMetadata.mediaType())) {
      mediaType = openRouterMetadata.mediaType();
    }
    byte[] bytes = Base64.getDecoder().decode(b64Json);
    Path file = this.outputDirectory.resolve(stem + "." + extension(mediaType));
    Files.write(file, bytes);

    probe.put("mediaType", mediaType);
    probe.put("imageBytes", bytes.length);
    probe.put("file", file.toString());
    boolean validImage = recordDimensions(probe, bytes, mediaType);
    probe.put(STATUS, bytes.length > 0 && validImage ? PASSED : FAILED);
    if (bytes.length == 0 || !validImage) {
      probe.put(ERROR, "generated image was empty or could not be decoded");
    }
  }

  private String paintPrompt(String topic) {
    return "A clean, friendly workshop-poster illustration for a garage service record about: "
        + topic
        + ". Flat colors, no text.";
  }

  private OpenRouterImageOptions imageOptions() {
    OpenRouterImageOptions.Builder options =
        OpenRouterImageOptions.builder().model(this.imageModelId).n(1);
    if (StringUtils.hasText(this.imageQuality)) {
      options.quality(this.imageQuality);
    }
    Map<String, @Nullable Object> providerOptions =
        GarageOptionsFactory.imageProviderOptions(this.provider, null);
    if (providerOptions != null) {
      options.providerOptions(providerOptions);
    }
    return options.build();
  }

  boolean recordDimensions(Map<String, Object> probe, byte[] bytes, String mediaType)
      throws IOException {
    if ("image/svg+xml".equals(mediaType)) {
      if (!validSvg(bytes)) {
        return false;
      }
      probe.put("dimensions", "vector");
      return true;
    }
    if ("image/webp".equals(mediaType) && !hasWebpSignature(bytes)) {
      return false;
    }
    BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
    if (decoded == null) {
      return false;
    }
    probe.put("width", decoded.getWidth());
    probe.put("height", decoded.getHeight());
    return decoded.getWidth() > 0 && decoded.getHeight() > 0;
  }

  static boolean hasWebpSignature(byte[] bytes) {
    return bytes.length > 20
        && bytes[0] == 'R'
        && bytes[1] == 'I'
        && bytes[2] == 'F'
        && bytes[3] == 'F'
        && bytes[8] == 'W'
        && bytes[9] == 'E'
        && bytes[10] == 'B'
        && bytes[11] == 'P'
        && Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(bytes, 4, 4)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt()) == bytes.length - 8L;
  }

  private boolean validSvg(byte[] bytes) {
    try {
      javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      var builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
        @Override
        public void error(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
          throw exception;
        }

        @Override
        public void fatalError(org.xml.sax.SAXParseException exception) throws org.xml.sax.SAXException {
          throw exception;
        }
      });
      var root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
      return "svg".equals(root.getLocalName()) && "http://www.w3.org/2000/svg".equals(root.getNamespaceURI());
    }
    catch (javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException | IOException ex) {
      return false;
    }
  }

  private Map<String, Object> probe(String bay, String model) {
    Map<String, Object> probe = new LinkedHashMap<>();
    probe.put("bay", bay);
    probe.put("model", model);
    return probe;
  }

  private void fail(Map<String, Object> probe, Exception ex) {
    probe.put(STATUS, FAILED);
    probe.put(ERROR, ex.getClass().getSimpleName() + ": " + ex.getMessage());
  }

  private byte[] decodeDataUrl(String data) {
    int comma = data.indexOf(',');
    if (data.startsWith("data:") && comma > 0) {
      return Base64.getDecoder().decode(data.substring(comma + 1));
    }
    return Base64.getDecoder().decode(data);
  }

  private String extension(String mediaType) {
    return switch (mediaType) {
      case "image/jpeg" -> "jpg";
      case "image/webp" -> "webp";
      case "image/svg+xml" -> "svg";
      default -> "png";
    };
  }

  public static double cosineSimilarity(float[] left, float[] right) {
    double dot = 0.0;
    double leftNorm = 0.0;
    double rightNorm = 0.0;
    for (int i = 0; i < Math.min(left.length, right.length); i++) {
      dot += left[i] * right[i];
      leftNorm += left[i] * left[i];
      rightNorm += right[i] * right[i];
    }
    if (leftNorm == 0.0 || rightNorm == 0.0) {
      return 0.0;
    }
    return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }
}
