package de.subhransu.openrouter.springai.garage;

import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.ERROR;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.FAILED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.PASSED;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.STATUS;
import static de.subhransu.openrouter.springai.garage.GarageEvidenceKeys.USAGE;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterProviderPreferences;
import de.subhransu.openrouter.springai.embedding.OpenRouterEmbeddingOptions;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.report.GarageReportWriter;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunRequest;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.image.ImageModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Starts Garage runs and sweeps, one at a time, and keeps their outcomes since startup.
 * Individual scenes own execution and evidence assertions.
 */
// The evidence collectors are shared singletons reset per run, so only one run may be active.
@Slf4j
@Service
@ConditionalOnProperty(prefix = "garage", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GarageRunService implements DisposableBean {

  private static final String SWEEP_ANCHOR_TEXT = "the engine overheats and loses coolant on long climbs";
  private static final String SWEEP_SIMILAR_TEXT = "motor running hot with coolant loss during uphill driving";
  private static final String SWEEP_UNRELATED_TEXT = "a recipe for lemon sponge cake with vanilla icing";

  private final ChatModel chatModel;
  private final EmbeddingModel embeddingModel;
  private final ImageModel imageModel;
  private final ChatClient chatClient;
  private final ObjectMapper objectMapper;
  private final Environment environment;
  private final Map<String, GarageScene> scenes;
  private final GarageEvidence evidence;
  private final GarageTelemetry telemetry;
  private final GarageTransportEvidence transportEvidence;
  private final ObservationRegistry observationRegistry;
  private final GarageReportWriter reportWriter;
  private final Executor executor;
  private final Map<String, GarageRun> runs = new ConcurrentHashMap<>();
  private @Nullable String activeRunId;

  @Autowired
  public GarageRunService(
      ChatModel chatModel,
      EmbeddingModel embeddingModel,
      ImageModel imageModel,
      ObjectMapper objectMapper,
      Environment environment,
      List<GarageScene> scenes,
      GarageEvidence evidence,
      GarageTelemetry telemetry,
      GarageTransportEvidence transportEvidence,
      ObservationRegistry observationRegistry,
      GarageReportWriter reportWriter) {
    this(chatModel, embeddingModel, imageModel, objectMapper, environment, scenes, evidence,
        telemetry, transportEvidence, observationRegistry, reportWriter,
        Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "garage-run")));
  }

  GarageRunService(
      ChatModel chatModel,
      EmbeddingModel embeddingModel,
      ImageModel imageModel,
      ObjectMapper objectMapper,
      Environment environment,
      List<GarageScene> scenes,
      GarageEvidence evidence,
      GarageTelemetry telemetry,
      GarageTransportEvidence transportEvidence,
      ObservationRegistry observationRegistry,
      GarageReportWriter reportWriter,
      Executor executor) {
    this.chatModel = chatModel;
    this.embeddingModel = embeddingModel;
    this.imageModel = imageModel;
    this.chatClient = ChatClient.builder(chatModel).build();
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
    this.executor = executor;
  }

  public List<GarageScene> scenes() {
    return List.copyOf(this.scenes.values());
  }

  public GarageRun start(GarageRunRequest request) {
    GarageProperties settings = runSettings();
    GarageRunPlan plan = GarageRunPlan.from(request, settings);
    selectedScenes(plan.sceneIds());
    return submit(GarageRun.Kind.SCENES, plan, settings);
  }

  public GarageRun startSweep(GarageSweepRequest request) {
    GarageProperties settings = runSettings();
    return submit(GarageRun.Kind.SWEEP, GarageRunPlan.forSweep(request, settings), settings);
  }

  public Optional<GarageRun> find(String id) {
    return Optional.ofNullable(this.runs.get(id));
  }

  @Override
  public void destroy() {
    if (this.executor instanceof ExecutorService) {
      ((ExecutorService) this.executor).shutdownNow();
    }
  }

  /** A fresh binding of {@code garage.*}, so one run's overrides never reach the next. */
  private GarageProperties runSettings() {
    return Binder.get(this.environment)
        .bindOrCreate("garage", Bindable.of(GarageProperties.class));
  }

  private GarageRun submit(GarageRun.Kind kind, GarageRunPlan plan, GarageProperties settings) {
    if (plan.requiresApiKey()) {
      assertApiKeyConfigured();
    }
    GarageRun run;
    synchronized (this) {
      if (this.activeRunId != null) {
        throw new RunActiveException(this.activeRunId);
      }
      String id = UUID.randomUUID().toString();
      run = GarageRun.running(id, kind, plan, settings.getOutputDir().resolve(slug(id)));
      this.runs.put(id, run);
      this.activeRunId = id;
    }
    GarageRun started = run;
    this.executor.execute(() -> complete(started, settings));
    return run;
  }

  private void complete(GarageRun run, GarageProperties settings) {
    GarageRun finished = run.failedToComplete("Garage run stopped unexpectedly");
    try {
      finished =
          run.kind() == GarageRun.Kind.SWEEP
              ? executeSweeps(run, settings)
              : executeScenes(run, settings);
      log.info("Garage run {} finished: {}", run.id(), finished.status());
    } catch (Exception failure) {
      log.error("Garage run {} could not complete", run.id(), failure);
      finished = run.failedToComplete(failure.getClass().getSimpleName() + ": " + failure.getMessage());
    } finally {
      // Publish the outcome and free the service together, so a client that sees the
      // finished run can start the next one immediately.
      synchronized (this) {
        this.runs.put(run.id(), finished);
        this.activeRunId = null;
      }
    }
  }

  private GarageRun executeScenes(GarageRun run, GarageProperties settings) throws IOException {
    GarageRunPlan plan = run.plan();
    List<GarageScene> selected = selectedScenes(plan.sceneIds());
    this.evidence.reset();
    this.telemetry.reset();
    this.transportEvidence.reset();
    Files.createDirectories(run.directory());
    logHeader(plan, selected, run.directory());

    GarageOptionsFactory optionsFactory = new GarageOptionsFactory(settings);
    List<SceneResult> results = new ArrayList<>();
    for (OpenRouterRequestMode requestMode : plan.requestModes()) {
      for (GarageScene scene : selected) {
        if ("recovery-road-test".equals(scene.id())
            && requestMode != OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
          continue;
        }
        results.add(runScene(plan, settings, optionsFactory, scene, requestMode, run.directory()));
      }
    }
    List<String> incompleteFeatures = incompleteFeatures(plan, selected);

    GarageReportWriter.ReportPaths reports =
        this.reportWriter.write(run.directory(), plan, results, incompleteFeatures);
    log.info("Capability report: {}", reports.markdown().toAbsolutePath());
    log.info("Evidence bundle   : {}", reports.json().toAbsolutePath());

    long failures =
        results.stream().filter(result -> result.status() == SceneResult.Status.FAILED).count();
    double recordedCostUsd = this.evidence.recordedCostUsd();
    boolean passed = failures == 0 && incompleteFeatures.isEmpty();
    String message = passed
        ? null
        : failures + " scene runs failed and " + incompleteFeatures.size()
            + " features lacked complete evidence " + incompleteFeatures;
    List<GarageRun.SceneOutcome> outcomes =
        results.stream()
            .map(result -> new GarageRun.SceneOutcome(
                result.sceneId(),
                result.requestMode().name(),
                result.status().name(),
                result.duration().toMillis()))
            .toList();
    return run.completed(
        passed ? GarageRun.Status.PASSED : GarageRun.Status.FAILED,
        outcomes,
        incompleteFeatures,
        recordedCostUsd,
        message,
        Map.of(GarageRun.EVIDENCE, reports.json(), GarageRun.REPORT, reports.markdown()));
  }

  private SceneResult runScene(
      GarageRunPlan plan,
      GarageProperties settings,
      GarageOptionsFactory optionsFactory,
      GarageScene scene,
      OpenRouterRequestMode requestMode,
      Path runDirectory)
      throws IOException {
    Path outputDirectory = runDirectory.resolve(modeSlug(requestMode)).resolve(scene.id());
    Files.createDirectories(outputDirectory);
    log.info("=== {} / {} ===", scene.title().toUpperCase(Locale.ROOT), requestMode);
    Instant started = Instant.now();
    SceneContext context =
        new SceneContext(
            plan,
            requestMode,
            outputDirectory,
            this.chatModel,
            this.chatClient,
            settings,
            optionsFactory,
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
      this.evidence.featureSnapshot().stream()
          .filter(item -> operationId.equals(item.get("operationId")))
          .filter(item -> requestMode.name().equals(item.get("requestMode")))
          .forEach(item -> this.evidence.error(GarageFeature.fromId(Objects.requireNonNull(item.get("featureId"), "featureId").toString()),
              operationId, requestMode.name(), failure));
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

  private GarageRun executeSweeps(GarageRun run, GarageProperties settings) throws IOException {
    GarageRunPlan plan = run.plan();
    Files.createDirectories(run.directory());
    List<SweepOutput> outputs = new ArrayList<>();
    if (!plan.embeddingSweepModels().isEmpty()) {
      outputs.add(
          new SweepOutput(
              GarageRun.EMBEDDING_SWEEP,
              "embedding-models",
              "embedding-sweep.json",
              runEmbeddingSweep(plan, settings)));
    }
    if (!plan.imageSweepModels().isEmpty()) {
      outputs.add(
          new SweepOutput(
              GarageRun.IMAGE_SWEEP,
              "image-models",
              "image-sweep.json",
              runImageSweep(plan, settings, run.directory())));
    }
    List<Map<String, Object>> allResults =
        outputs.stream().flatMap(output -> output.results().stream()).toList();
    double recordedCostUsd = GarageCosts.usageMaps(allResults);
    Map<String, Path> files = new LinkedHashMap<>();
    for (SweepOutput output : outputs) {
      files.put(output.key(), writeSweepDocument(run.directory(), output, recordedCostUsd));
    }
    long failures =
        allResults.stream().filter(result -> !PASSED.equals(result.get(STATUS))).count();
    return run.completed(
        failures == 0 ? GarageRun.Status.PASSED : GarageRun.Status.FAILED,
        List.of(),
        List.of(),
        recordedCostUsd,
        failures == 0 ? null : failures + " sweep entries failed",
        files);
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
  private List<Map<String, Object>> runEmbeddingSweep(GarageRunPlan plan, GarageProperties settings) {
    log.info("=== EMBEDDING MODEL SWEEP ({} combinations) ===", plan.embeddingSweepModels().size());
    List<Map<String, Object>> results = new ArrayList<>();
    for (String entry : plan.embeddingSweepModels()) {
      results.add(embeddingSweepResult(ModelPin.parse(entry), settings));
    }
    return results;
  }

  private Map<String, Object> embeddingSweepResult(ModelPin pin, GarageProperties settings) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("model", pin.modelId());
    result.put("provider", pin.providerTag() != null ? pin.providerTag() : "default-routing");
    try {
      OpenRouterEmbeddingOptions.Builder options =
          OpenRouterEmbeddingOptions.builder().model(pin.modelId());
      OpenRouterProviderPreferences provider =
          GarageOptionsFactory.pinnedProviderPreferences(
              GarageOptionsFactory.serviceProviderPreferences(settings),
              pin.providerTag());
      if (provider != null) {
        options.provider(provider);
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
   * images and per-entry evidence land in the run directory.
   */
  private List<Map<String, Object>> runImageSweep(
      GarageRunPlan plan, GarageProperties settings, Path runDirectory) {
    log.info("=== IMAGE MODEL SWEEP ({} entries) ===", plan.imageSweepModels().size());
    GarageModalityBays bays =
        new GarageModalityBays(
            this.chatModel,
            this.embeddingModel,
            this.imageModel,
            runDirectory,
            plan.embeddingModel(),
            plan.visionModel(),
            plan.imageModel(),
            plan.imageQuality(),
            GarageOptionsFactory.serviceProviderPreferences(settings));

    List<Map<String, Object>> results = new ArrayList<>();
    int index = 0;
    for (String entry : plan.imageSweepModels()) {
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

  private Path writeSweepDocument(Path runDirectory, SweepOutput output, double recordedCostUsd)
      throws IOException {
    List<Map<String, Object>> results = output.results();
    int passed = (int) results.stream().filter(result -> PASSED.equals(result.get(STATUS))).count();
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("application", "garage");
    document.put("sweep", output.sweep());
    document.put("createdAt", Instant.now().toString());
    document.put(PASSED, passed);
    document.put(FAILED, results.size() - passed);
    document.put("recordedCostUsd", recordedCostUsd);
    document.put("results", results);
    Path sweepJson = runDirectory.resolve(output.fileName());
    this.objectMapper.writerWithDefaultPrettyPrinter()
        .writeValue(sweepJson.toFile(), this.evidence.sanitizeForEvidence(document));
    log.info(
        "Sweep result: {}/{} models passed. Evidence: {}",
        passed,
        results.size(),
        sweepJson.toAbsolutePath());
    return sweepJson;
  }

  private List<String> incompleteFeatures(GarageRunPlan plan, List<GarageScene> selected) {
    Set<GarageFeature> required =
        selected.stream()
            .flatMap(scene -> scene.features().stream())
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!plan.runsEmbeddings()) {
      required.remove(GarageFeature.EMBEDDINGS);
    }
    if (!plan.runsImageInput()) {
      required.remove(GarageFeature.IMAGE_INPUT);
    }
    if (!plan.runsImageGeneration()) {
      required.remove(GarageFeature.IMAGE_GENERATION);
    }
    return required.stream()
        .filter(feature -> feature.coverageModes(plan.requestModes()).stream()
            .filter(feature::supports)
            .anyMatch(mode -> !"covered".equals(this.evidence.coverageStatus(feature, mode))))
        .map(GarageFeature::id)
        .toList();
  }

  private List<GarageScene> selectedScenes(List<String> sceneIds) {
    Set<String> unknown = new LinkedHashSet<>(sceneIds);
    unknown.removeAll(this.scenes.keySet());
    if (!unknown.isEmpty()) {
      throw new IllegalArgumentException(
          "Unknown Garage scenes " + unknown + "; GET /api/scenes lists valid ids");
    }
    return sceneIds.stream().map(this.scenes::get).toList();
  }

  private String lastOperationId(String sceneId, OpenRouterRequestMode requestMode) {
    return this.evidence.featureSnapshot().stream()
        .filter(item -> sceneId.equals(item.get("sceneId")))
        .filter(item -> requestMode.name().equals(item.get("requestMode")))
        .map(item -> Objects.requireNonNull(item.get("operationId"), "operationId").toString())
        .reduce((first, second) -> second)
        .orElse(sceneId + "-failed-" + UUID.randomUUID());
  }

  private void logHeader(GarageRunPlan plan, List<GarageScene> selected, Path runDirectory) {
    log.info(
        """
        GARAGE run
        Foreman model    : {}
        Specialist model : {}
        Embedding model  : {}
        Vision model     : {}
        Image model      : {}
        Capabilities     : {}
        Image surface    : {}
        Request modes    : {}
        Scenes           : {}
        Offline contracts: {}
        Output folder    : {}""",
        plan.foremanModel(),
        plan.specialistModel(),
        plan.embeddingModel(),
        plan.visionModel(),
        plan.imageModel(),
        plan.capabilities(),
        plan.imageSurface(),
        plan.requestModes(),
        selected.stream().map(GarageScene::id).toList(),
        plan.offlineContracts(),
        runDirectory.toAbsolutePath());
  }

  private void assertApiKeyConfigured() {
    String apiKey = this.environment.getProperty("spring.ai.openrouter.api-key");
    if (!StringUtils.hasText(apiKey) || "garage-missing-api-key".equals(apiKey)) {
      throw new MissingApiKeyException();
    }
  }

  private String modeSlug(OpenRouterRequestMode requestMode) {
    return switch (requestMode) {
      case OPENAI_CHAT_COMPLETIONS -> "chat-completions";
      case OPENAI_RESPONSES -> "responses";
    };
  }

  private String slug(String runId) {
    return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now())
        + "-garage-run-" + runId.substring(0, 8);
  }

  /** Another run is still executing; the evidence collectors allow only one at a time. */
  public static final class RunActiveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String activeRunId;

    public RunActiveException(String activeRunId) {
      super("Garage run " + activeRunId + " is still running");
      this.activeRunId = activeRunId;
    }

    public String activeRunId() {
      return this.activeRunId;
    }
  }

  /** The selected scenes call OpenRouter but no API key is configured. */
  public static final class MissingApiKeyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingApiKeyException() {
      super("The selected scenes call OpenRouter; set OPENROUTER_API_KEY or"
          + " spring.ai.openrouter.api-key, or select offlineContracts.");
    }
  }

  /** A sweep entry's model id, optionally pinned to one provider as {@code model@providerTag}. */
  private record ModelPin(String modelId, @Nullable String providerTag) {

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
      String key, String sweep, String fileName, List<Map<String, Object>> results) {}
}
