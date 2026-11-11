package de.subhransu.openrouter.springai.garage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.garage.GarageProperties;
import de.subhransu.openrouter.springai.garage.GarageRun;
import de.subhransu.openrouter.springai.garage.GarageRunService;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.run.GarageRunRequest;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
import de.subhransu.openrouter.springai.garage.scenes.GarageScene;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(GarageRunController.class)
class GarageRunApiTests {

  private static final GarageRunPlan PLAN =
      GarageRunPlan.from(GarageRunRequest.defaults(), new GarageProperties());

  @TempDir Path directory;

  @Autowired MockMvcTester mvc;

  @MockitoBean GarageRunService service;

  @Test
  void startingARunReturnsAcceptedWithItsLocation() {
    when(this.service.start(any())).thenReturn(run("run-1", GarageRun.Status.RUNNING, Map.of()));

    assertThat(this.mvc.post().uri("/api/runs"))
        .hasStatus(HttpStatus.ACCEPTED)
        .hasHeader("Location", "/api/runs/run-1")
        .bodyJson()
        .hasPathSatisfying("$.status", status -> status.assertThat().isEqualTo("RUNNING"))
        .hasPathSatisfying("$.selection.scenes",
            scenes -> scenes.assertThat().asArray().containsExactly("service-story"))
        .hasPathSatisfying("$.links.self", self -> self.assertThat().isEqualTo("/api/runs/run-1"));
  }

  @Test
  void startingASweepReturnsAcceptedWithItsLocation() {
    ArgumentCaptor<GarageSweepRequest> request = ArgumentCaptor.forClass(GarageSweepRequest.class);
    when(this.service.startSweep(request.capture()))
        .thenReturn(run("sweep-1", GarageRun.Status.RUNNING, Map.of()));

    assertThat(this.mvc.post().uri("/api/sweeps").contentType(MediaType.APPLICATION_JSON)
        .content("{\"embeddingModels\": [\"synthetic/embedding\"]}"))
        .hasStatus(HttpStatus.ACCEPTED)
        .hasHeader("Location", "/api/runs/sweep-1");
    assertThat(request.getValue().embeddingModels()).containsExactly("synthetic/embedding");
  }

  @Test
  void invalidSelectionsAreBadRequests() {
    when(this.service.start(any()))
        .thenThrow(new IllegalArgumentException("full cannot be narrowed with scenes"));

    assertThat(this.mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"full\": true, \"scenes\": [\"service-story\"]}"))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .bodyJson()
        .hasPathSatisfying("$.title",
            title -> title.assertThat().isEqualTo("Invalid Garage selection"))
        .hasPathSatisfying("$.detail",
            detail -> detail.assertThat().isEqualTo("full cannot be narrowed with scenes"));
  }

  @Test
  void misspelledRequestFieldsAreBadRequests() {
    assertThat(this.mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON)
        .content("{\"capabilites\": [\"text\"]}"))
        .hasStatus(HttpStatus.BAD_REQUEST)
        .bodyJson()
        .hasPathSatisfying("$.detail",
            detail -> detail.assertThat().isEqualTo("Unknown request field 'capabilites'"));
  }

  // The conflict and missing-key problem details are asserted end to end in GarageApiIntegrationTests and
  // GarageOfflineContractsIntegrationTests: only GarageRunService can create those exceptions.

  @Test
  void malformedJsonIsABadRequest() {
    assertThat(this.mvc.post().uri("/api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"capabilities\": ["))
        .hasStatus(HttpStatus.BAD_REQUEST);
  }

  @Test
  void runsAreOnlyReachableByIdSoTheCollectionIsNotListable() {
    assertThat(this.mvc.get().uri("/api/runs")).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
  }

  @Test
  void unknownRunsAreNotFound() {
    when(this.service.find("missing")).thenReturn(Optional.empty());

    assertThat(this.mvc.get().uri("/api/runs/missing")).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(this.mvc.get().uri("/api/runs/missing/evidence")).hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void filesAreConflictsWhileTheRunIsActive() {
    when(this.service.find("run-1"))
        .thenReturn(Optional.of(run("run-1", GarageRun.Status.RUNNING, Map.of())));

    assertThat(this.mvc.get().uri("/api/runs/run-1/evidence")).hasStatus(HttpStatus.CONFLICT);
    assertThat(this.mvc.get().uri("/api/runs/run-1/report")).hasStatus(HttpStatus.CONFLICT);
  }

  @Test
  void finishedRunsServeTheirEvidenceReportAndSweeps() throws Exception {
    Path evidence = Files.writeString(this.directory.resolve("garage-run.json"), "{\"status\":\"passed\"}");
    Path report = Files.writeString(this.directory.resolve("capability-report.md"), "# Garage capability report\n");
    Path sweep = Files.writeString(this.directory.resolve("embedding-sweep.json"), "{\"failed\":0}");
    when(this.service.find("run-1")).thenReturn(Optional.of(run("run-1", GarageRun.Status.PASSED,
        Map.of(GarageRun.EVIDENCE, evidence, GarageRun.REPORT, report, GarageRun.EMBEDDING_SWEEP, sweep))));

    assertThat(this.mvc.get().uri("/api/runs/run-1"))
        .hasStatusOk()
        .bodyJson()
        .hasPathSatisfying("$.links.evidence",
            link -> link.assertThat().isEqualTo("/api/runs/run-1/evidence"))
        .hasPathSatisfying("$.links.embedding",
            link -> link.assertThat().isEqualTo("/api/runs/run-1/sweeps/embedding"));
    assertThat(this.mvc.get().uri("/api/runs/run-1/evidence"))
        .hasStatusOk().hasContentType(MediaType.APPLICATION_JSON)
        .bodyText().isEqualTo("{\"status\":\"passed\"}");
    assertThat(this.mvc.get().uri("/api/runs/run-1/report"))
        .hasStatusOk().hasContentType("text/markdown")
        .bodyText().startsWith("# Garage capability report");
    assertThat(this.mvc.get().uri("/api/runs/run-1/sweeps/embedding"))
        .hasStatusOk().bodyText().isEqualTo("{\"failed\":0}");
    assertThat(this.mvc.get().uri("/api/runs/run-1/sweeps/image")).hasStatus(HttpStatus.NOT_FOUND);
    assertThat(this.mvc.get().uri("/api/runs/run-1/sweeps/paint")).hasStatus(HttpStatus.NOT_FOUND);
  }

  @Test
  void scenesAreListedWithTheirOfflineFlagAndFeatureIds() {
    GarageScene recovery = scene("recovery-road-test", true, GarageFeature.forScene("recovery-road-test"));
    GarageScene live = scene("service-story", false, GarageFeature.forScene("service-story"));
    when(this.service.scenes()).thenReturn(List.of(recovery, live));

    assertThat(this.mvc.get().uri("/api/scenes")).hasStatusOk().bodyJson()
        .hasPathSatisfying("$.length()", size -> size.assertThat().isEqualTo(2))
        .hasPathSatisfying("$[0].id", id -> id.assertThat().isEqualTo("recovery-road-test"))
        .hasPathSatisfying("$[0].offline", offline -> offline.assertThat().isEqualTo(true))
        .hasPathSatisfying("$[1].offline", offline -> offline.assertThat().isEqualTo(false))
        .hasPathSatisfying("$[1].features",
            features -> features.assertThat().asArray().containsExactlyElementsOf(
                GarageFeature.forScene("service-story").stream().map(GarageFeature::id).toList()));
  }

  private static GarageScene scene(String id, boolean offline, List<GarageFeature> features) {
    GarageScene scene = mock(GarageScene.class);
    when(scene.id()).thenReturn(id);
    when(scene.title()).thenReturn("Synthetic " + id);
    when(scene.description()).thenReturn("Synthetic description");
    when(scene.offline()).thenReturn(offline);
    when(scene.features()).thenReturn(features);
    return scene;
  }

  private GarageRun run(String id, GarageRun.Status status, Map<String, Path> files) {
    return new GarageRun(id, GarageRun.Kind.SCENES, status, Instant.EPOCH,
        status == GarageRun.Status.RUNNING ? null : Instant.EPOCH, PLAN, this.directory, List.of(),
        List.of(), 0.0, null, files);
  }
}
