package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.ERROR;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.FAILED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.PASSED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.STATUS;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.USAGE;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.report.GarageReportWriter;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Scene dispatcher only; individual stories own execution and evidence assertions. */
// The Garage is a console application: its report lines are the product, so the console
// pattern in application.yml keeps this logger's output as bare, println-style lines.
@Slf4j
@Component
@ConditionalOnProperty(prefix = "garage", name = "enabled", havingValue = "true", matchIfMissing = true)
final class GarageRunner implements CommandLineRunner {

  private static final String SWEEP_ANCHOR_TEXT = "the engine overheats and loses coolant on long climbs";
  private static final String SWEEP_SIMILAR_TEXT = "motor running hot with coolant loss during uphill driving";
  private static final String SWEEP_UNRELATED_TEXT = "a recipe for lemon sponge cake with vanilla icing";

  private final ChatModel chatModel;
  private final EmbeddingModel embeddingModel;
  private final ImageModel imageModel;
  private final ChatClient chatClient;
  private final GarageProperties properties;
  private final GarageOptionsFactory optionsFactory;
  private final ObjectMapper objectMapper;
  private final Environment environment;
  private final Map<String, GarageScene> scenes;
  private final GarageEvidence evidence;
  private final GarageTelemetry telemetry;
  private final GarageTransportEvidence transportEvidence;
  private final ObservationRegistry observationRegistry;
  private final GarageReportWriter reportWriter;

  GarageRunner(
      ChatModel chatModel,
      EmbeddingModel embeddingModel,
      ImageModel imageModel,
      GarageProperties properties,
      GarageOptionsFactory optionsFactory,
      ObjectMapper objectMapper,
      Environment environment,
      List<GarageScene> scenes,
      GarageEvidence evidence,
      GarageTelemetry telemetry,
      GarageTransportEvidence transportEvidence,
      ObservationRegistry observationRegistry,
      GarageReportWriter reportWriter) {
    this.chatModel = chatModel;
    this.embeddingModel = embeddingModel;
    this.imageModel = imageModel;
    this.chatClient = ChatClient.builder(chatModel).build();
    this.properties = properties;
    this.optionsFactory = optionsFactory;
    this.objectMapper = objectMapper;
    this.environment = environment;
    this.scenes =
        scenes.stream()
            .sorted(Comparator.comparing(GarageScene::id))
            .collect(
                Collectors.toMap(
                    GarageScene::id,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    this.evidence = evidence;
    this.telemetry = telemetry;
    this.transportEvidence = transportEvidence;
    this.observationRegistry = observationRegistry;
    this.reportWriter = reportWriter;
  }

  @Override
  public void run(String... args) throws Exception {
    GarageCommand command = GarageCommand.from(args, this.properties);
    if (command.help()) {
      printHelp();
      return;
    }
    if (command.listScenes()) {
      printScenes();
      return;
    }
    if (runSweeps(command)) {
      return;
    }
    List<GarageScene> selected = selectedScenes(command.sceneIds());
    if (command.requiresApiKey()) {
      assertApiKeyConfigured();
    }

    this.evidence.reset();
    this.telemetry.reset();
    this.transportEvidence.reset();
    Path runDirectory = command.outputRoot().resolve(slug());
    Files.createDirectories(runDirectory);
    printHeader(command, selected, runDirectory);

    List<SceneResult> results = runScenes(command, selected, runDirectory);
    List<String> incompleteFeatures = incompleteFeatures(command, selected);

    GarageReportWriter.ReportPaths reports =
        this.reportWriter.write(runDirectory, command, results, incompleteFeatures);
    log.info("\nCapability report: {}", reports.markdown().toAbsolutePath());
    log.info("Evidence bundle   : {}", reports.json().toAbsolutePath());

    List<SceneResult> failures =
        results.stream().filter(result -> result.status() == SceneResult.Status.FAILED).toList();
    double recordedCostUsd = this.evidence.recordedCostUsd();
    boolean budgetExceeded =
        command.maxCostUsd() != null && recordedCostUsd > command.maxCostUsd() + 0.000000001;
    if (!failures.isEmpty() || !incompleteFeatures.isEmpty() || budgetExceeded) {
      throw new IllegalStateException(
          "Garage completed every selected scene but "
              + failures.size()
              + " failed and "
              + incompleteFeatures.size()
              + " features lacked complete evidence "
              + incompleteFeatures
              + ", recorded cost was $"
              + String.format(Locale.ROOT, "%.8f", recordedCostUsd)
              + (command.maxCostUsd() != null
                  ? " against a $" + command.maxCostUsd() + " ceiling"
                  : "")
              + "; inspect "
              + reports.markdown().toAbsolutePath());
    }
  }

  private boolean runSweeps(GarageCommand command) throws IOException {
    if (command.embeddingSweepModels().isEmpty() && command.imageSweepModels().isEmpty()) {
      return false;
    }
    assertApiKeyConfigured();
    List<SweepOutput> outputs = new ArrayList<>();
    if (!command.embeddingSweepModels().isEmpty()) {
      outputs.add(
          new SweepOutput(
              "embedding-models",
              "embedding-sweep.json",
              runEmbeddingSweep(command)));
    }
    if (!command.imageSweepModels().isEmpty()) {
      outputs.add(
          new SweepOutput("image-models", "image-sweep.json", runImageSweep(command)));
    }
    List<Map<String, Object>> allResults =
        outputs.stream().flatMap(output -> output.results().stream()).toList();
    double recordedCostUsd = GarageCosts.usageMaps(allResults);
    for (SweepOutput output : outputs) {
      writeSweepDocument(command, output, recordedCostUsd);
    }
    long failures =
        allResults.stream().filter(result -> !PASSED.equals(result.get(STATUS))).count();
    boolean budgetExceeded =
        command.maxCostUsd() != null
            && recordedCostUsd > command.maxCostUsd() + 0.000000001;
    if (failures > 0 || budgetExceeded) {
      throw new IllegalStateException(
          "Garage sweeps completed with "
              + failures
              + " failures and recorded cost $"
              + String.format(Locale.ROOT, "%.8f", recordedCostUsd)
              + (command.maxCostUsd() != null
                  ? " against a $" + command.maxCostUsd() + " ceiling"
                  : ""));
    }
    return true;
  }

  private List<SceneResult> runScenes(
      GarageCommand command, List<GarageScene> selected, Path runDirectory) throws IOException {
    List<SceneResult> results = new ArrayList<>();
    for (OpenRouterRequestMode requestMode : command.requestModes()) {
      for (GarageScene scene : selected) {
        if ("recovery-road-test".equals(scene.id())
            && requestMode != OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
          continue;
        }
        results.add(runScene(command, scene, requestMode, runDirectory));
      }
    }
    return results;
  }

  private SceneResult runScene(
      GarageCommand command, GarageScene scene, OpenRouterRequestMode requestMode, Path runDirectory)
      throws IOException {
    Path outputDirectory = runDirectory.resolve(modeSlug(requestMode)).resolve(scene.id());
    Files.createDirectories(outputDirectory);
    log.info("\n=== {} / {} ===", scene.title().toUpperCase(Locale.ROOT), requestMode);
    Instant started = Instant.now();
    SceneContext context =
        new SceneContext(
            command,
            requestMode,
            outputDirectory,
            this.chatModel,
            this.chatClient,
            this.properties,
            this.optionsFactory,
            this.objectMapper,
            this.evidence,
            this.telemetry,
            this.transportEvidence,
            this.observationRegistry);
    try {
      SceneResult result = scene.execute(context);
      log.info("PASS {} ({} ms)", scene.id(), result.duration().toMillis());
      return result;
    } catch (Exception failure) {
      log.error("FAIL {}: {}", scene.id(), failure.getMessage());
      String operationId = lastOperationId(scene.id(), requestMode);
      return SceneResult.failed(
          scene.id(),
          operationId,
          requestMode,
          Duration.between(started, Instant.now()),
          outputDirectory,
          Map.of("costUsd", this.evidence.costFor(operationId)),
          failure);
    }
  }

  /**
   * Model/provider-compatibility sweep: calls the auto-configured {@link EmbeddingModel}
   * bean once per supplied entry, exactly as any application consuming the starter would.
   * Entries are model ids, optionally pinned to one provider as {@code model@providerTag}
   * (mapped to provider preferences {@code order} with fallbacks disabled). Each call
   * embeds an anchor, a paraphrase, and an unrelated text in one batch and requires the
   * paraphrase to rank closer than the unrelated text, so a vector that decodes but
   * carries no meaning still fails. Provider-side failures are reported, not hidden.
   */
  private List<Map<String, Object>> runEmbeddingSweep(GarageCommand command) {
    log.info(
        "\n=== EMBEDDING MODEL SWEEP ({} combinations) ===\n", command.embeddingSweepModels().size());
    List<Map<String, Object>> results = new ArrayList<>();
    for (String entry : command.embeddingSweepModels()) {
      results.add(embeddingSweepResult(ModelPin.parse(entry)));
    }
    return results;
  }

  private Map<String, Object> embeddingSweepResult(ModelPin pin) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("model", pin.modelId());
    result.put("provider", pin.providerTag() != null ? pin.providerTag() : "default-routing");
    try {
      OpenRouterEmbeddingOptions.Builder options =
          OpenRouterEmbeddingOptions.builder().model(pin.modelId());
      if (pin.providerTag() != null) {
        options.provider(
            new OpenRouterProviderPreferences(
                false, null, null, List.of(pin.providerTag()), null, null, null));
      }
      EmbeddingResponse response =
          this.embeddingModel.call(
              new EmbeddingRequest(
                  List.of(SWEEP_ANCHOR_TEXT, SWEEP_SIMILAR_TEXT, SWEEP_UNRELATED_TEXT), options.build()));
      result.put(USAGE, GarageResponses.usage(response.getMetadata().getUsage()));
      float[] anchor = response.getResults().get(0).getOutput();
      double similarScore =
          GarageModalityBays.cosineSimilarity(anchor, response.getResults().get(1).getOutput());
      double unrelatedScore =
          GarageModalityBays.cosineSimilarity(anchor, response.getResults().get(2).getOutput());
      boolean ok = anchor.length > 0 && response.getResults().size() == 3 && similarScore > unrelatedScore;
      result.put(STATUS, ok ? PASSED : FAILED);
      result.put("dimensions", anchor.length);
      result.put("similarCosine", Math.round(similarScore * 10000.0) / 10000.0);
      result.put("unrelatedCosine", Math.round(unrelatedScore * 10000.0) / 10000.0);
      result.put("responseModel", response.getMetadata().getModel());
      if (!ok) {
        result.put(
            ERROR, "semantic ordering failed: similar=" + similarScore + " unrelated=" + unrelatedScore);
      }
      log.info(
          "{} | {} | dims={} | similar={} > unrelated={}",
          pin.label(),
          ok ? PASSED : "FAILED",
          anchor.length,
          result.get("similarCosine"),
          result.get("unrelatedCosine"));
    } catch (RuntimeException ex) {
      String message = ex.getMessage() != null ? ex.getMessage().split("\\R")[0] : "";
      result.put(STATUS, FAILED);
      result.put(ERROR, ex.getClass().getSimpleName() + ": " + message);
      log.info("{} | FAILED | {}: {}", pin.label(), ex.getClass().getSimpleName(), message);
    }
    return result;
  }

  /**
   * Image-model compatibility sweep: one generation per entry through the auto-configured
   * {@link ImageModel} bean. Entries are {@code model[@providerTag][?key=value&key=value]}
   * with config keys resolution, quality, aspect-ratio, and output-format. Generated
   * images and per-entry evidence land in the output root.
   */
  private List<Map<String, Object>> runImageSweep(GarageCommand command) throws IOException {
    log.info("\n=== IMAGE MODEL SWEEP ({} entries) ===\n", command.imageSweepModels().size());
    Files.createDirectories(command.outputRoot());
    GarageModalityBays bays =
        new GarageModalityBays(
            this.chatModel,
            this.embeddingModel,
            this.imageModel,
            command.outputRoot(),
            command.embeddingModel(),
            command.visionModel(),
            command.imageModel(),
            command.imageQuality());

    List<Map<String, Object>> results = new ArrayList<>();
    int index = 0;
    for (String entry : command.imageSweepModels()) {
      index++;
      String modelPart = entry;
      Map<String, String> config = new LinkedHashMap<>();
      int question = entry.indexOf('?');
      if (question > 0) {
        modelPart = entry.substring(0, question);
        for (String pair : entry.substring(question + 1).split("&")) {
          String[] keyValue = pair.split("=", 2);
          config.put(keyValue[0], keyValue.length > 1 ? keyValue[1] : "");
        }
      }
      ModelPin pin = ModelPin.parse(modelPart);
      String stem = "image-sweep-" + index + "-" + pin.modelId().replaceAll("[^a-zA-Z0-9.]+", "-");
      Map<String, Object> result = bays.runImageModelCheck(pin.modelId(), pin.providerTag(), config, stem);
      results.add(result);
      boolean ok = PASSED.equals(result.get(STATUS));
      log.info(
          "{} | {} | {}",
          entry,
          ok ? PASSED : "FAILED",
          ok ? result.get("imageBytes") + " bytes " + result.get("mediaType") : result.get(ERROR));
    }

    return results;
  }

  private void writeSweepDocument(
      GarageCommand command, SweepOutput output, double recordedCostUsd)
      throws IOException {
    List<Map<String, Object>> results = output.results();
    int passed = (int) results.stream().filter(result -> PASSED.equals(result.get(STATUS))).count();
    Files.createDirectories(command.outputRoot());
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("application", "garage");
    document.put("sweep", output.sweep());
    document.put("createdAt", Instant.now().toString());
    document.put(PASSED, passed);
    document.put(FAILED, results.size() - passed);
    document.put("recordedCostUsd", recordedCostUsd);
    document.put("maxCostUsd", command.maxCostUsd());
    document.put(
        "costBudgetExceeded",
        command.maxCostUsd() != null
            && recordedCostUsd > command.maxCostUsd() + 0.000000001);
    document.put("results", results);
    Path sweepJson = command.outputRoot().resolve(output.fileName());
    this.objectMapper.writerWithDefaultPrettyPrinter().writeValue(sweepJson.toFile(), document);
    log.info(
        "\nSweep result: {}/{} models passed. Evidence: {}",
        passed,
        results.size(),
        sweepJson.toAbsolutePath());
  }

  private List<String> incompleteFeatures(
      GarageCommand command, List<GarageScene> selected) {
    Set<GarageFeature> required =
        selected.stream()
            .flatMap(scene -> scene.features().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!command.requestModes().contains(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)) {
      required.remove(GarageFeature.CHAT_COMPLETIONS_MODE);
      // Digital inspection explicitly reports structured output as unsupported in Responses mode.
      // Keep that outcome in the report without claiming that inference was executed.
      required.remove(GarageFeature.STRUCTURED_OUTPUT);
      // runScenes skips the Chat Completions-only recovery contract in Responses mode.
      required.removeAll(GarageFeature.forScene("recovery-road-test"));
    }
    if (!command.requestModes().contains(OpenRouterRequestMode.OPENAI_RESPONSES)) {
      required.remove(GarageFeature.RESPONSES_MODE);
    }
    if (!command.runsEmbeddings()) {
      required.remove(GarageFeature.EMBEDDINGS);
    }
    if (!command.runsImageInput()) {
      required.remove(GarageFeature.IMAGE_INPUT);
    }
    if (!command.runsImageGeneration()) {
      required.remove(GarageFeature.IMAGE_GENERATION);
    }
    List<Map<String, Object>> snapshot = this.evidence.featureSnapshot();
    return required.stream()
        .filter(
            feature ->
                snapshot.stream()
                    .filter(item -> feature.id().equals(item.get("featureId")))
                    .noneMatch(item -> Boolean.TRUE.equals(item.get("complete"))))
        .map(GarageFeature::id)
        .toList();
  }

  private List<GarageScene> selectedScenes(List<String> sceneIds) {
    Set<String> unknown = new LinkedHashSet<>(sceneIds);
    unknown.removeAll(this.scenes.keySet());
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "Unknown Garage scenes " + unknown + "; use --list-scenes for valid ids");
    }
    return sceneIds.stream().map(this.scenes::get).toList();
  }

  private String lastOperationId(String sceneId, OpenRouterRequestMode requestMode) {
    return this.evidence.featureSnapshot().stream()
        .filter(item -> sceneId.equals(item.get("sceneId")))
        .filter(item -> requestMode.name().equals(item.get("requestMode")))
        .map(item -> item.get("operationId").toString())
        .reduce((first, second) -> second)
        .orElse(sceneId + "-failed-" + UUID.randomUUID());
  }

  private void printHeader(
      GarageCommand command, List<GarageScene> selected, Path runDirectory) {
    log.info(
        """

        GARAGE - OpenRouter Spring AI capability application
        Foreman model    : {}
        Specialist model : {}
        Embedding model  : {}
        Vision model     : {}
        Image model      : {}
        Capabilities     : {}
        Image surface    : {}
        Cost ceiling USD : {}
        Request modes    : {}
        Scenes           : {}
        Offline contracts: {}
        Output folder    : {}""",
        command.foremanModel(),
        command.specialistModel(),
        command.embeddingModel(),
        command.visionModel(),
        command.imageModel(),
        command.capabilities(),
        command.imageSurface(),
        command.maxCostUsd(),
        command.requestModes(),
        selected.stream().map(GarageScene::id).toList(),
        command.offlineContracts(),
        runDirectory.toAbsolutePath());
  }

  private void printScenes() {
    log.info("GARAGE scenes\n");
    for (GarageScene scene : this.scenes.values()) {
      log.info(
          "{}\n  {}\n  execution: {}\n  features: {}\n",
          scene.id(),
          scene.description(),
          scene.offline() ? "offline/contract" : "live",
          scene.features().stream().map(GarageFeature::id).toList());
    }
  }

  private void printHelp() {
    log.info(
        """
        GARAGE sample

        Live scenes require OPENROUTER_API_KEY. Offline contracts do not.

        Options:
          --list-scenes                  List scenes and feature ids
          --text                         Text, tools, streaming and text contracts
          --embedding                    Embedding checks only; combinable with --text
          --vision                       Image-input understanding checks
          --image                        Image generation (sync by default)
          --scene=<id,id>                Run selected scenes
          --offline-contracts            Run recovery + dyno contracts without an API key
          --full                         Run every scene in both request modes
          --auto                         Deprecated no-op; failed checks always fail the run
          --stream                       Add the streaming-dispatch scene
          --request-mode=<mode>          chat, responses, both, or all
          --topic=<text>                 Customer/car request to inspect
          --fallback-models=<ids>        Comma-separated fallback models
          --output=<path>                Output root, default: outputs
          --foreman-model=<model>        Main model id
          --specialist-model=<model>     Delegated model id
          --embedding-model=<model>      Embedding model id for the modality bays
          --vision-model=<model>         Image-input model id for the modality bays
          --image-model=<model>          Image-generation model id for the modality bays
          --image-surface=<surface>      none, sync, streaming, chat, or all
          --image-quality=<quality>      Optional Image API/chat image quality
          --max-cost-usd=<amount>        Fail when recorded inference exceeds this (post-run)
          --max-completion-tokens=<n>    Main text completion limit
          --specialist-max-completion-tokens=<n>  Specialist completion limit
          --reasoning-effort=<value>     Text reasoning effort
          --provider-sort=<value>        Provider sorting preference
          --provider-order=<ids>         Provider preference list; empty clears it
          --provider-ignore=<ids>        Excluded providers; empty clears it
          --provider-quantizations=<ids> Provider quantizations; empty clears them
          --embedding-sweep=<entries>    Embedding compatibility sweep; entries are
                                         comma-separated model[@providerTag] ids
          --image-sweep=<entries>        Image-model compatibility sweep; entries are
                                         comma-separated model[@providerTag][?key=value&...]
          --help                         Show this help
        """);
  }

  private void assertApiKeyConfigured() {
    String apiKey = this.environment.getProperty("spring.ai.openrouter.api-key");
    if (!StringUtils.hasText(apiKey) || "garage-missing-api-key".equals(apiKey)) {
      throw new IllegalStateException(
          "Selected Garage scenes require OPENROUTER_API_KEY or"
              + " spring.ai.openrouter.api-key. Use --offline-contracts for the deterministic"
              + " no-key lane.");
    }
  }

  private String modeSlug(OpenRouterRequestMode requestMode) {
    return switch (requestMode) {
      case OPENAI_CHAT_COMPLETIONS -> "chat-completions";
      case OPENAI_RESPONSES -> "responses";
    };
  }

  private String slug() {
    return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        + "-garage-run";
  }

  /** A sweep entry's model id, optionally pinned to one provider as {@code model@providerTag}. */
  private record ModelPin(String modelId, String providerTag) {

    static ModelPin parse(String entry) {
      int at = entry.lastIndexOf('@');
      if (at > 0) {
        return new ModelPin(entry.substring(0, at), entry.substring(at + 1));
      }
      return new ModelPin(entry, null);
    }

    String label() {
      return modelId + (providerTag != null ? "@" + providerTag : "");
    }
  }

  private record SweepOutput(
      String sweep, String fileName, List<Map<String, Object>> results) {}
}
