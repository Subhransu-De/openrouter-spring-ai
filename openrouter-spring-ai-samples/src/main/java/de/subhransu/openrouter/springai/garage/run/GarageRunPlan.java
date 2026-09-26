package de.subhransu.openrouter.springai.garage.run;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageProperties;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/** Validated selection for one Garage run or sweep. */
public record GarageRunPlan(
    String topic,
    boolean full,
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
  private static final String MODALITY_BAYS = "modality-bays";

  private static final List<OpenRouterRequestMode> ALL_REQUEST_MODES =
      List.of(
          OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
          OpenRouterRequestMode.OPENAI_RESPONSES);

  private static final List<String> FULL_SCENES =
      List.of(
          "service-story",
          STREAMING_DISPATCH,
          "digital-inspection",
          MODALITY_BAYS,
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

  /**
   * Validates a run request. Option overrides are written to {@code settings}, so callers
   * must pass a per-run copy rather than the shared bound properties.
   */
  public static GarageRunPlan from(GarageRunRequest request, GarageProperties settings) {
    Selection selection = new Selection(settings);
    if (request.capabilities() != null) {
      for (String capability : request.capabilities()) {
        switch (normalize(capability)) {
          case "text" -> selection.text = true;
          case "embedding" -> selection.embedding = true;
          case "vision" -> selection.vision = true;
          case "image" -> selection.image = true;
          default -> throw new IllegalArgumentException("Unknown capability: " + capability);
        }
      }
    }
    if (request.scenes() != null) {
      selection.sceneIds = sanitize(request.scenes());
      selection.scenesExplicit = true;
    }
    if (request.full() != null) {
      selection.full = request.full();
    }
    selection.offlineContracts = Boolean.TRUE.equals(request.offlineContracts());
    if (request.requestModes() != null) {
      selection.requestModes = parseModes(request.requestModes());
      selection.modesExplicit = true;
    }
    if (StringUtils.hasText(request.topic())) {
      selection.topic = request.topic();
    }
    applyModels(selection, request.models());
    if (request.image() != null) {
      if (request.image().surface() != null) {
        selection.imageSurface = ImageSurface.parse(request.image().surface());
        selection.surfaceExplicit = true;
      }
      selection.imageQuality = request.image().quality();
    }
    if (request.limits() != null) {
      if (request.limits().maxCompletionTokens() != null) {
        settings.setMaxCompletionTokens(
            positive("limits.maxCompletionTokens", request.limits().maxCompletionTokens()));
      }
      if (request.limits().specialistMaxCompletionTokens() != null) {
        settings.setSpecialistMaxCompletionTokens(
            positive(
                "limits.specialistMaxCompletionTokens",
                request.limits().specialistMaxCompletionTokens()));
      }
    }
    if (request.reasoningEffort() != null) {
      settings.setReasoningEffort(request.reasoningEffort());
    }
    applyProvider(settings, request.provider());
    return selection.validate(settings);
  }

  /** Validates a sweep request; like {@link #from}, overrides are written to {@code settings}. */
  public static GarageRunPlan forSweep(GarageSweepRequest request, GarageProperties settings) {
    Selection selection = new Selection(settings);
    selection.embeddingSweepModels = sanitize(request.embeddingModels());
    selection.imageSweepModels = sanitize(request.imageModels());
    if (selection.embeddingSweepModels.isEmpty() && selection.imageSweepModels.isEmpty()) {
      throw new IllegalArgumentException("A sweep needs embeddingModels or imageModels");
    }
    selection.sceneIds = List.of();
    selection.imageQuality = request.imageQuality();
    applyProvider(settings, request.provider());
    return selection.validate(settings);
  }

  public boolean isSweep() {
    return !this.embeddingSweepModels.isEmpty() || !this.imageSweepModels.isEmpty();
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
    return isSweep() || requiresLiveScene(this.sceneIds);
  }

  private static boolean requiresLiveScene(List<String> scenes) {
    return scenes.stream()
        .anyMatch(scene -> !RECOVERY_ROAD_TEST.equals(scene) && !"dyno-tuning".equals(scene));
  }

  private static void applyModels(Selection selection, GarageRunRequest.@Nullable Models models) {
    if (models == null) {
      return;
    }
    if (models.foreman() != null) {
      selection.foremanModel = models.foreman();
    }
    if (models.specialist() != null) {
      selection.specialistModel = models.specialist();
    }
    if (models.embedding() != null) {
      selection.embeddingModel = models.embedding();
    }
    if (models.vision() != null) {
      selection.visionModel = models.vision();
    }
    if (models.image() != null) {
      selection.imageModel = models.image();
    }
    if (models.fallbacks() != null) {
      selection.fallbackModels = sanitize(models.fallbacks());
    }
  }

  private static void applyProvider(
      GarageProperties settings, GarageRunRequest.@Nullable Provider provider) {
    if (provider == null) {
      return;
    }
    if (provider.sort() != null) {
      settings.setProviderSort(provider.sort());
    }
    if (provider.requireParameters() != null) {
      settings.setProviderRequireParameters(provider.requireParameters());
    }
    if (provider.order() != null) {
      settings.setProviderOrder(sanitize(provider.order()));
    }
    if (provider.ignore() != null) {
      settings.setProviderIgnore(sanitize(provider.ignore()));
    }
    if (provider.quantizations() != null) {
      settings.setProviderQuantizations(sanitize(provider.quantizations()));
    }
  }

  private static int positive(String field, int value) {
    if (value <= 0) {
      throw new IllegalArgumentException(field + " must be positive");
    }
    return value;
  }

  private static String normalize(String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }

  private static List<String> sanitize(@Nullable List<String> values) {
    if (values == null) {
      return List.of();
    }
    Set<String> sanitized = new LinkedHashSet<>();
    values.stream()
        .filter(StringUtils::hasText)
        .map(String::strip)
        .forEach(sanitized::add);
    return new ArrayList<>(sanitized);
  }

  private static List<String> add(List<String> values, String value) {
    List<String> copy = new ArrayList<>(values);
    if (!copy.contains(value)) {
      copy.add(value);
    }
    return copy;
  }

  private static List<OpenRouterRequestMode> parseModes(List<String> raw) {
    List<String> values = sanitize(raw);
    if (values.isEmpty()) {
      throw new IllegalArgumentException("requestModes must select at least one mode");
    }
    List<OpenRouterRequestMode> modes = new ArrayList<>();
    for (String value : values) {
      String normalized = normalize(value).replace('-', '_');
      switch (normalized) {
        case "all", "both" -> modes.addAll(ALL_REQUEST_MODES);
        case "chat", "chat_completions", "openai_chat_completions" ->
            modes.add(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
        case "responses", "openai_responses" -> modes.add(OpenRouterRequestMode.OPENAI_RESPONSES);
        default -> throw new IllegalArgumentException("Unknown request mode: " + value);
      }
    }
    return sanitizeModes(modes);
  }

  private static List<OpenRouterRequestMode> sanitizeModes(
      @Nullable List<OpenRouterRequestMode> modes) {
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
      try {
        return valueOf(normalized);
      } catch (IllegalArgumentException ex) {
        throw new IllegalArgumentException("Unknown image surface: " + value, ex);
      }
    }
  }

  /** Mutable selection state while a request is validated. */
  private static final class Selection {

    private String topic;
    private boolean full;
    private boolean offlineContracts;
    private boolean modesExplicit;
    private boolean scenesExplicit;
    private boolean text;
    private boolean embedding;
    private boolean vision;
    private boolean image;
    private boolean surfaceExplicit;
    private String foremanModel;
    private String specialistModel;
    private String embeddingModel;
    private String visionModel;
    private String imageModel;
    private List<String> fallbackModels;
    private List<OpenRouterRequestMode> requestModes;
    private List<String> sceneIds = new ArrayList<>(List.of("service-story"));
    private List<String> embeddingSweepModels = List.of();
    private List<String> imageSweepModels = List.of();
    private ImageSurface imageSurface = ImageSurface.NONE;
    private @Nullable String imageQuality;

    Selection(GarageProperties properties) {
      this.topic = properties.getTopic();
      this.full = properties.isFull();
      this.foremanModel = properties.getForemanModel();
      this.specialistModel = properties.getSpecialistModel();
      this.embeddingModel = properties.getEmbeddingModel();
      this.visionModel = properties.getVisionModel();
      this.imageModel = properties.getImageModel();
      this.fallbackModels = sanitize(properties.getFallbackModels());
      this.requestModes = sanitizeModes(properties.getRequestModes());
    }

    GarageRunPlan validate(GarageProperties properties) {
      boolean sweep = !this.embeddingSweepModels.isEmpty() || !this.imageSweepModels.isEmpty();
      boolean capabilitiesExplicit = this.text || this.embedding || this.vision || this.image;
      if (properties.isStream() && !this.scenesExplicit && !capabilitiesExplicit && !this.full
          && !this.offlineContracts && !sweep) {
        this.sceneIds = add(this.sceneIds, STREAMING_DISPATCH);
      }
      if (this.scenesExplicit && this.sceneIds.isEmpty()) {
        throw new IllegalArgumentException("scenes must select at least one scene");
      }
      if (this.full && this.scenesExplicit) {
        throw new IllegalArgumentException(
            "full cannot be narrowed with scenes; use capabilities instead");
      }
      if (this.offlineContracts && (capabilitiesExplicit || this.full)) {
        throw new IllegalArgumentException(
            "offlineContracts cannot be combined with live capabilities");
      }
      if (this.surfaceExplicit && !this.image && !this.full) {
        throw new IllegalArgumentException("image.surface requires the image capability or full");
      }
      if (this.image || this.full) {
        if (this.surfaceExplicit && this.imageSurface == ImageSurface.NONE) {
          throw new IllegalArgumentException("The image capability cannot use image.surface=none");
        }
        if (!this.surfaceExplicit) {
          this.imageSurface = this.full ? ImageSurface.ALL : ImageSurface.SYNC;
        }
      }
      if (this.full) {
        this.text = true;
        this.embedding = true;
        this.vision = true;
      } else if (capabilitiesExplicit && !this.scenesExplicit) {
        this.sceneIds = new ArrayList<>();
        if (this.text) {
          this.sceneIds.addAll(TEXT_SCENES);
        }
        if (this.embedding || this.vision || this.image) {
          this.sceneIds.add(MODALITY_BAYS);
        }
      }
      if (capabilitiesExplicit && this.scenesExplicit) {
        for (String scene : this.sceneIds) {
          boolean allowed = MODALITY_BAYS.equals(scene)
              ? this.embedding || this.vision || this.image
              : this.text && (TEXT_SCENES.contains(scene) || "routing-lane".equals(scene));
          if (!allowed) {
            throw new IllegalArgumentException(
                "Scene " + scene + " is outside the selected capabilities");
          }
        }
        if ((this.embedding || this.vision || this.image)
            && !this.sceneIds.contains(MODALITY_BAYS)) {
          throw new IllegalArgumentException("Selected modalities require modality-bays in scenes");
        }
        if (this.text && this.sceneIds.stream().allMatch(MODALITY_BAYS::equals)) {
          throw new IllegalArgumentException("The text capability requires at least one text scene");
        }
      }
      if (!this.full && !this.embedding && !this.vision && !this.image
          && this.sceneIds.contains(MODALITY_BAYS)) {
        throw new IllegalArgumentException(
            "modality-bays requires the embedding, vision, or image capability");
      }
      if ((this.full || this.text || this.vision) && !this.modesExplicit) {
        this.requestModes = ALL_REQUEST_MODES;
      }
      if ((this.imageSurface == ImageSurface.CHAT || this.imageSurface == ImageSurface.ALL)
          && !this.requestModes.contains(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)) {
        throw new IllegalArgumentException(
            "Chat image generation requires the chat request mode");
      }
      if (this.full && !this.scenesExplicit) {
        this.sceneIds = FULL_SCENES;
      }
      if (this.offlineContracts && !this.scenesExplicit) {
        this.sceneIds = List.of(RECOVERY_ROAD_TEST, "dyno-tuning");
      }
      if (this.offlineContracts && requiresLiveScene(this.sceneIds)) {
        throw new IllegalArgumentException("offlineContracts accepts only offline scenes");
      }
      this.text = this.sceneIds.stream().anyMatch(scene -> !MODALITY_BAYS.equals(scene));
      return new GarageRunPlan(
          this.topic,
          this.full,
          this.offlineContracts,
          this.text,
          this.embedding,
          this.vision,
          this.imageSurface,
          this.imageQuality,
          this.foremanModel,
          this.specialistModel,
          this.embeddingModel,
          this.visionModel,
          this.imageModel,
          List.copyOf(this.fallbackModels),
          List.copyOf(this.requestModes),
          List.copyOf(this.sceneIds),
          List.copyOf(this.embeddingSweepModels),
          List.copyOf(this.imageSweepModels));
    }
  }
}
