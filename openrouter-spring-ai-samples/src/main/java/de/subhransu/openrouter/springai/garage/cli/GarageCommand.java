package de.subhransu.openrouter.springai.garage.cli;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/** Typed Garage CLI command. */
public record GarageCommand(
    String topic,
    Path outputRoot,
    boolean full,
    boolean help,
    boolean listScenes,
    boolean offlineContracts,
    boolean text,
    boolean embedding,
    boolean vision,
    ImageSurface imageSurface,
    @Nullable String imageQuality,
    String foremanModel,
    String specialistModel,
    String embeddingModel,
    String visionModel,
    String imageModel,
    List<String> fallbackModels,
    List<OpenRouterRequestMode> requestModes,
    List<String> sceneIds,
    List<String> embeddingSweepModels,
    List<String> imageSweepModels) {

  private static final String STREAMING_DISPATCH = "streaming-dispatch";
  private static final String RECOVERY_ROAD_TEST = "recovery-road-test";

  private static final List<OpenRouterRequestMode> ALL_REQUEST_MODES =
      List.of(
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
          OpenRouterRequestMode.OPENAI_RESPONSES);

  private static final List<String> FULL_SCENES =
      List.of(
          "service-story",
          STREAMING_DISPATCH,
          "digital-inspection",
          "modality-bays",
          "express-invoice",
          "routing-lane",
          "dyno-tuning",
          "attribution-check-in",
          RECOVERY_ROAD_TEST);

  private static final List<String> TEXT_SCENES =
      List.of(
          "service-story",
          STREAMING_DISPATCH,
          "digital-inspection",
          "express-invoice",
          "dyno-tuning",
          "attribution-check-in",
          RECOVERY_ROAD_TEST);

  public static GarageCommand from(String[] args, GarageProperties properties) {
    String topic = properties.getTopic();
    Path outputRoot = properties.getOutputDir();
    boolean full = properties.isFull();
    boolean help = false;
    boolean listScenes = false;
    boolean offlineContracts = false;
    boolean modesExplicit = false;
    boolean scenesExplicit = false;
    boolean text = false;
    boolean embedding = false;
    boolean vision = false;
    boolean image = false;
    boolean surfaceExplicit = false;
    String foremanModel = properties.getForemanModel();
    String specialistModel = properties.getSpecialistModel();
    String embeddingModel = properties.getEmbeddingModel();
    String visionModel = properties.getVisionModel();
    String imageModel = properties.getImageModel();
    List<String> fallbackModels = sanitize(properties.getFallbackModels());
    List<OpenRouterRequestMode> requestModes = sanitizeModes(properties.getRequestModes());
    List<String> sceneIds = new ArrayList<>(List.of("service-story"));
    List<String> embeddingSweepModels = List.of();
    List<String> imageSweepModels = List.of();
    ImageSurface imageSurface = ImageSurface.NONE;
    String imageQuality = null;

    for (String arg : args) {
      if ("--text".equals(arg)) {
        text = true;
      } else if ("--embedding".equals(arg)) {
        embedding = true;
      } else if ("--vision".equals(arg)) {
        vision = true;
      } else if ("--image".equals(arg)) {
        image = true;
      } else if ("--auto".equals(arg)) {
        // Backward-compatible no-op: every run now fails on unsuccessful checks.
      } else if ("--full".equals(arg)) {
        full = true;
      } else if ("--offline-contracts".equals(arg)) {
        offlineContracts = true;
      } else if ("--list-scenes".equals(arg)) {
        listScenes = true;
      } else if ("--help".equals(arg) || "-h".equals(arg)) {
        help = true;
      } else if (arg.startsWith("--topic=")) {
        topic = value(arg);
      } else if (arg.startsWith("--output=")) {
        outputRoot = Path.of(value(arg));
      } else if (arg.startsWith("--foreman-model=")) {
        foremanModel = value(arg);
      } else if (arg.startsWith("--specialist-model=")) {
        specialistModel = value(arg);
      } else if (arg.startsWith("--embedding-model=")) {
        embeddingModel = value(arg);
      } else if (arg.startsWith("--vision-model=")) {
        visionModel = value(arg);
      } else if (arg.startsWith("--image-model=")) {
        imageModel = value(arg);
      } else if (arg.startsWith("--image-surface=")) {
        imageSurface = ImageSurface.parse(value(arg));
        surfaceExplicit = true;
      } else if (arg.startsWith("--image-quality=")) {
        imageQuality = value(arg);
      } else if (arg.startsWith("--max-completion-tokens=")) {
        properties.setMaxCompletionTokens(positiveInteger(arg));
      } else if (arg.startsWith("--specialist-max-completion-tokens=")) {
        properties.setSpecialistMaxCompletionTokens(positiveInteger(arg));
      } else if (arg.startsWith("--reasoning-effort=")) {
        properties.setReasoningEffort(value(arg));
      } else if (arg.startsWith("--provider-sort=")) {
        properties.setProviderSort(value(arg));
      } else if (arg.startsWith("--provider-require-parameters=")) {
        properties.setProviderRequireParameters(booleanValue(arg));
      } else if (arg.startsWith("--provider-order=")) {
        properties.setProviderOrder(parseList(value(arg)));
      } else if (arg.startsWith("--provider-ignore=")) {
        properties.setProviderIgnore(parseList(value(arg)));
      } else if (arg.startsWith("--provider-quantizations=")) {
        properties.setProviderQuantizations(parseList(value(arg)));
      } else if (arg.startsWith("--max-cost-usd=")) {
        // Backward-compatible no-op: Garage reports what a run cost but enforces no ceiling.
      } else if (arg.startsWith("--embedding-sweep=")) {
        embeddingSweepModels = parseList(value(arg));
      } else if (arg.startsWith("--image-sweep=")) {
        imageSweepModels = parseList(value(arg));
      } else if (arg.startsWith("--fallback-models=")) {
        fallbackModels = parseList(value(arg));
      } else if (arg.startsWith("--request-mode=") || arg.startsWith("--request-modes=")) {
        requestModes = parseModes(value(arg));
        modesExplicit = true;
      } else if (arg.startsWith("--scene=") || arg.startsWith("--scenes=")) {
        sceneIds = parseList(value(arg));
        scenesExplicit = true;
      } else if ("--stream".equals(arg)) {
        sceneIds = add(sceneIds, STREAMING_DISPATCH);
        scenesExplicit = true;
      } else if (!isBootOption(arg)) {
        throw new IllegalArgumentException("Unknown Garage option: " + arg);
      }
    }

    boolean capabilitiesExplicit = text || embedding || vision || image;
    if (properties.isStream() && !scenesExplicit && !capabilitiesExplicit && !full
        && !offlineContracts && embeddingSweepModels.isEmpty() && imageSweepModels.isEmpty()) {
      sceneIds = add(sceneIds, STREAMING_DISPATCH);
    }
    if ((capabilitiesExplicit || full || scenesExplicit)
        && (!embeddingSweepModels.isEmpty() || !imageSweepModels.isEmpty())) {
      throw new IllegalArgumentException("Sweeps cannot be combined with capability or scene selections");
    }
    if (scenesExplicit && sceneIds.isEmpty()) {
      throw new IllegalArgumentException("--scene must select at least one scene");
    }
    if (full && scenesExplicit) {
      throw new IllegalArgumentException("--full cannot be narrowed with --scene; use capability flags instead");
    }
    if (offlineContracts && (capabilitiesExplicit || full
        || !embeddingSweepModels.isEmpty() || !imageSweepModels.isEmpty())) {
      throw new IllegalArgumentException("--offline-contracts cannot be combined with live capabilities or sweeps");
    }
    if (surfaceExplicit && !image && !full) {
      throw new IllegalArgumentException("--image-surface requires --image or --full");
    }
    if (image || full) {
      if (surfaceExplicit && imageSurface == ImageSurface.NONE) {
        throw new IllegalArgumentException("--image cannot use --image-surface=none");
      }
      if (!surfaceExplicit) {
        imageSurface = full ? ImageSurface.ALL : ImageSurface.SYNC;
      }
    }
    if (full) {
      text = true;
      embedding = true;
      vision = true;
    } else if (capabilitiesExplicit && !scenesExplicit) {
      sceneIds = new ArrayList<>();
      if (text) {
        sceneIds.addAll(TEXT_SCENES);
      }
      if (embedding || vision || image) {
        sceneIds.add("modality-bays");
      }
    }
    if (capabilitiesExplicit && scenesExplicit) {
      for (String scene : sceneIds) {
        boolean allowed = "modality-bays".equals(scene)
            ? embedding || vision || image
            : text && (TEXT_SCENES.contains(scene) || "routing-lane".equals(scene));
        if (!allowed) {
          throw new IllegalArgumentException("Scene " + scene + " is outside the selected capabilities");
        }
      }
      if ((embedding || vision || image) && !sceneIds.contains("modality-bays")) {
        throw new IllegalArgumentException("Selected modalities require modality-bays in --scene");
      }
      if (text && sceneIds.stream().allMatch("modality-bays"::equals)) {
        throw new IllegalArgumentException("--text requires at least one text scene");
      }
    }
    if (!full && !embedding && !vision && !image && sceneIds.contains("modality-bays")) {
      throw new IllegalArgumentException("modality-bays requires --embedding, --vision, or --image");
    }
    if ((full || text || vision) && !modesExplicit) {
      requestModes = ALL_REQUEST_MODES;
    }
    if ((imageSurface == ImageSurface.CHAT || imageSurface == ImageSurface.ALL)
        && !requestModes.contains(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)) {
      throw new IllegalArgumentException(
          "Chat image generation requires --request-mode=chat or --request-mode=both");
    }
    if (full && !scenesExplicit) {
      sceneIds = FULL_SCENES;
    }
    if (offlineContracts && !scenesExplicit) {
      sceneIds = List.of(RECOVERY_ROAD_TEST, "dyno-tuning");
    }
    if (offlineContracts && requiresLiveScene(sceneIds)) {
      throw new IllegalArgumentException("--offline-contracts accepts only offline scenes");
    }
    text = sceneIds.stream().anyMatch(scene -> !"modality-bays".equals(scene));
    return new GarageCommand(
        topic,
        outputRoot,
        full,
        help,
        listScenes,
        offlineContracts,
        text,
        embedding,
        vision,
        imageSurface,
        imageQuality,
        foremanModel,
        specialistModel,
        embeddingModel,
        visionModel,
        imageModel,
        List.copyOf(fallbackModels),
        List.copyOf(requestModes),
        List.copyOf(sceneIds),
        List.copyOf(embeddingSweepModels),
        List.copyOf(imageSweepModels));
  }

  public boolean runsEmbeddings() {
    return this.embedding;
  }

  public List<String> capabilities() {
    List<String> selected = new ArrayList<>();
    if (this.text) {
      selected.add("text");
    }
    if (this.embedding) {
      selected.add("embedding");
    }
    if (this.vision) {
      selected.add("vision");
    }
    if (runsImageGeneration()) {
      selected.add("image");
    }
    return List.copyOf(selected);
  }

  public boolean runsImageInput() {
    return this.vision;
  }

  public boolean runsImageGeneration() {
    return this.imageSurface != ImageSurface.NONE;
  }

  public boolean requiresApiKey() {
    return !this.embeddingSweepModels.isEmpty() || !this.imageSweepModels.isEmpty()
        || requiresLiveScene(this.sceneIds);
  }

  private static boolean requiresLiveScene(List<String> scenes) {
    return scenes.stream()
        .anyMatch(scene -> !RECOVERY_ROAD_TEST.equals(scene) && !"dyno-tuning".equals(scene));
  }

  private static String value(String arg) {
    return arg.substring(arg.indexOf('=') + 1);
  }

  private static boolean isBootOption(String arg) {
    if ("--debug".equals(arg) || "--trace".equals(arg)) {
      return true;
    }
    int separator = arg.indexOf('=');
    if (!arg.startsWith("--") || separator < 3) {
      return false;
    }
    String key = arg.substring(2, separator);
    return "debug".equals(key) || "trace".equals(key)
        || key.matches("[\\w-]+(?:\\.[\\w\\[\\]-]+)+");
  }

  private static boolean booleanValue(String arg) {
    String raw = value(arg).strip();
    if ("true".equalsIgnoreCase(raw)) {
      return true;
    }
    if ("false".equalsIgnoreCase(raw)) {
      return false;
    }
    throw new IllegalArgumentException(arg + " must be true or false");
  }

  private static int positiveInteger(String arg) {
    int parsed = Integer.parseInt(value(arg));
    if (parsed <= 0) {
      throw new IllegalArgumentException(arg + " must be positive");
    }
    return parsed;
  }

  private static List<String> parseList(String raw) {
    return !StringUtils.hasText(raw) ? List.of() : sanitize(Arrays.asList(raw.split(",")));
  }

  private static List<String> sanitize(List<String> values) {
    if (values == null) {
      return List.of();
    }
    Set<String> sanitized = new LinkedHashSet<>();
    values.stream().filter(StringUtils::hasText).map(String::strip).forEach(sanitized::add);
    return new ArrayList<>(sanitized);
  }

  private static List<String> add(List<String> values, String value) {
    List<String> copy = new ArrayList<>(values);
    if (!copy.contains(value)) {
      copy.add(value);
    }
    return copy;
  }

  private static List<OpenRouterRequestMode> parseModes(String raw) {
    if (!StringUtils.hasText(raw)) {
      return List.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
    }
    if ("all".equalsIgnoreCase(raw) || "both".equalsIgnoreCase(raw)) {
      return ALL_REQUEST_MODES;
    }
    List<OpenRouterRequestMode> modes = new ArrayList<>();
    for (String value : raw.split(",")) {
      String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', '_');
      modes.add(
          switch (normalized) {
            case "chat", "chat_completions", "openai_chat_completions" ->
                OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS;
            case "responses", "openai_responses" -> OpenRouterRequestMode.OPENAI_RESPONSES;
            default -> OpenRouterRequestMode.valueOf(value.strip().toUpperCase(Locale.ROOT));
          });
    }
    return sanitizeModes(modes);
  }

  private static List<OpenRouterRequestMode> sanitizeModes(
      List<OpenRouterRequestMode> modes) {
    if (modes == null || modes.isEmpty()) {
      return List.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
    }
    return new ArrayList<>(new LinkedHashSet<>(modes));
  }

  public enum ImageSurface {
    NONE,
    SYNC,
    STREAMING,
    CHAT,
    ALL;

    static ImageSurface parse(String value) {
      String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
      if ("STREAM".equals(normalized)) {
        normalized = "STREAMING";
      }
      return valueOf(normalized);
    }
  }
}
