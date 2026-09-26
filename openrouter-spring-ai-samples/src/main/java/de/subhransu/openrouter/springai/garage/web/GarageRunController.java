package de.subhransu.openrouter.springai.garage.web;

import de.subhransu.openrouter.springai.garage.GarageRun;
import de.subhransu.openrouter.springai.garage.GarageRunService;
import de.subhransu.openrouter.springai.garage.run.GarageRunRequest;
import de.subhransu.openrouter.springai.garage.run.GarageSweepRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** HTTP surface of the Garage: list scenes, start runs and sweeps, and fetch their evidence. */
@RestController
@RequestMapping("/api")
@ConditionalOnProperty(prefix = "garage", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GarageRunController {

  private static final MediaType MARKDOWN = MediaType.parseMediaType("text/markdown");

  private final GarageRunService service;

  public GarageRunController(GarageRunService service) {
    this.service = service;
  }

  @GetMapping("/scenes")
  public List<GarageSceneView> scenes() {
    return this.service.scenes().stream().map(GarageSceneView::of).toList();
  }

  @PostMapping("/runs")
  public ResponseEntity<GarageRunView> startRun(
      @RequestBody(required = false) @Nullable GarageRunRequest request) {
    return accepted(this.service.start(request != null ? request : GarageRunRequest.defaults()));
  }

  @PostMapping("/sweeps")
  public ResponseEntity<GarageRunView> startSweep(@RequestBody GarageSweepRequest request) {
    return accepted(this.service.startSweep(request));
  }

  @GetMapping("/runs/{id}")
  public GarageRunView run(@PathVariable String id) {
    return GarageRunView.of(find(id));
  }

  @GetMapping("/runs/{id}/evidence")
  public ResponseEntity<Resource> evidence(@PathVariable String id) throws IOException {
    return file(find(id), GarageRun.EVIDENCE, MediaType.APPLICATION_JSON);
  }

  @GetMapping("/runs/{id}/report")
  public ResponseEntity<Resource> report(@PathVariable String id) throws IOException {
    return file(find(id), GarageRun.REPORT, MARKDOWN);
  }

  @GetMapping("/runs/{id}/sweeps/{sweep}")
  public ResponseEntity<Resource> sweep(@PathVariable String id, @PathVariable String sweep)
      throws IOException {
    if (!GarageRun.EMBEDDING_SWEEP.equals(sweep) && !GarageRun.IMAGE_SWEEP.equals(sweep)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sweep " + sweep);
    }
    return file(find(id), sweep, MediaType.APPLICATION_JSON);
  }

  private GarageRun find(String id) {
    return this.service.find(id).orElseThrow(
        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown Garage run " + id));
  }

  private ResponseEntity<GarageRunView> accepted(GarageRun run) {
    return ResponseEntity.accepted()
        .location(URI.create(GarageRunView.path(run)))
        .body(GarageRunView.of(run));
  }

  private ResponseEntity<Resource> file(GarageRun run, String name, MediaType mediaType)
      throws IOException {
    if (!run.finished()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT,
          "Garage run " + run.id() + " is still running");
    }
    Path path = run.files().get(name);
    if (path == null || !Files.isRegularFile(path)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
          "Garage run " + run.id() + " has no " + name + " file");
    }
    return ResponseEntity.ok()
        .contentType(mediaType)
        .contentLength(Files.size(path))
        .body(new FileSystemResource(path));
  }
}
