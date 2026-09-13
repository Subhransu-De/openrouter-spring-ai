package de.subhransu.openrouter.springai.garage.scenes;

import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.BAY;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.ERROR;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.PASSED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.STATUS;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageModalityBays;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand.ImageSurface;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.stereotype.Component;

/**
 * The Garage's non-chat modality bays: an embeddings-backed triage matcher, a digital
 * inspection bay that reads a real photo (image input), and a paint bay that generates
 * images through both library surfaces (the unified Image API sync + streaming, and
 * chat-completions modalities). The embedding and image APIs have no request-mode axis,
 * so those bays run once per run (in the chat-completions pass, or in the only pass when
 * chat mode is not selected); the image-input inspection runs in every request mode.
 */
@Slf4j
@Component
public final class ModalityBaysScene extends GarageSceneSupport {

  private final EmbeddingModel embeddingModel;
  private final ImageModel imageModel;

  public ModalityBaysScene(EmbeddingModel embeddingModel, ImageModel imageModel) {
    super(
        "modality-bays",
        "Modality bays",
        "Embeddings triage matcher, image-input inspection, and image generation over"
            + " the Image API (sync + streaming) and chat modalities.",
        false);
    this.embeddingModel = embeddingModel;
    this.imageModel = imageModel;
  }

  @Override
  public SceneResult execute(SceneContext context) throws Exception {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    GarageCommand command = context.command();
    GarageModalityBays bays =
        new GarageModalityBays(
            context.chatModel(),
            this.embeddingModel,
            this.imageModel,
            context.outputDirectory(),
            command.embeddingModel(),
            command.visionModel(),
            command.imageModel(),
            command.imageQuality());

    boolean modeIndependentBays =
        context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
            || !command.requestModes().contains(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);

    Map<GarageFeature, List<Map<String, Object>>> probesByFeature = new LinkedHashMap<>();
    if (modeIndependentBays && command.runsEmbeddings()) {
      probesByFeature.put(
          GarageFeature.EMBEDDINGS, List.of(bays.runTriageMatcher(command.topic())));
    }
    if (command.runsImageInput()) {
      probesByFeature.put(
          GarageFeature.IMAGE_INPUT,
          List.of(bays.runDigitalInspection(context.requestMode())));
    }
    if (modeIndependentBays && command.runsImageGeneration()) {
      probesByFeature.put(
          GarageFeature.IMAGE_GENERATION,
          switch (command.imageSurface()) {
            case SYNC -> List.of(bays.runPaintBay(command.topic()));
            case STREAMING -> List.of(bays.runStreamingPaintBay(command.topic()));
            case CHAT -> List.of(bays.runChatPaintBay(command.topic()));
            case ALL ->
                List.of(
                    bays.runPaintBay(command.topic()),
                    bays.runStreamingPaintBay(command.topic()),
                    bays.runChatPaintBay(command.topic()));
            case NONE ->
                throw new IllegalStateException(
                    "unreachable image surface: " + ImageSurface.NONE);
          });
    }

    List<Map<String, Object>> probes = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    for (Map.Entry<GarageFeature, List<Map<String, Object>>> entry : probesByFeature.entrySet()) {
      GarageFeature feature = entry.getKey();
      context.evidence().record(
          feature,
          operationId,
          mode,
          EvidenceLevel.CONFIGURED,
          "models",
          Map.of(
              "embedding", command.embeddingModel(),
              "vision", command.visionModel(),
              "image", command.imageModel()));
      context.evidence().record(
          feature,
          operationId,
          mode,
          EvidenceLevel.EXECUTED,
          "bays",
          entry.getValue().stream().map(probe -> probe.get(BAY)).toList());
      context.evidence().record(
          feature, operationId, mode, EvidenceLevel.OBSERVED, "probes", entry.getValue());
      boolean featurePassed = true;
      for (Map<String, Object> probe : entry.getValue()) {
        probes.add(probe);
        context.evidence().recordCost(operationId, GarageCosts.usageMaps(probe));
        log.info("{} [{}]: {}", probe.get(BAY), probe.get("model"), probe.get(STATUS));
        if (!PASSED.equals(probe.get(STATUS))) {
          featurePassed = false;
          failures.add(probe.get(BAY) + ": " + probe.getOrDefault(ERROR, "unknown failure"));
        }
      }
      if (featurePassed) {
        context.evidence().record(
            feature, operationId, mode, EvidenceLevel.ASSERTED, "assertion", "every probe passed");
      }
    }

    if (!failures.isEmpty()) {
      IllegalStateException failure =
          new IllegalStateException(
              "Garage modality bay checks failed: " + String.join("; ", failures));
      probesByFeature.keySet().forEach(
          feature -> context.evidence().error(feature, operationId, mode, failure));
      throw failure;
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("probes", probes);
    details.put("costUsd", GarageCosts.usageMaps(probes));
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }
}
