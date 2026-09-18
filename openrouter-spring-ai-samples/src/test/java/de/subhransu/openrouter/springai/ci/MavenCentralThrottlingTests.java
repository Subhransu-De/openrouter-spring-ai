package de.subhransu.openrouter.springai.ci;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Maven Central answers 403 when the shared GitHub runner egress pool is throttled, and the
 * resolver retries only 429 and 503 by default, so a throttled response fails the job on first
 * contact. These checks pin the two mitigations in place.
 */
class MavenCentralThrottlingTests {

  private static final Path ROOT = repositoryRoot();

  @ParameterizedTest
  @ValueSource(strings = { "aether.connector.http.retryHandler", "aether.transport.http.retryHandler" })
  void everyMavenRunRetriesThrottledCentralResponses(String resolver) throws IOException {
    Map<String, String> flags = mavenConfigFlags();

    assertThat(flags).containsKey(resolver + ".count");
    assertThat(Integer.parseInt(flags.get(resolver + ".count"))).isGreaterThanOrEqualTo(2);
    assertThat(flags.get(resolver + ".serviceUnavailable")).isNotNull();
    assertThat(flags.get(resolver + ".serviceUnavailable").split(",")).contains("403", "429", "503");
  }

  @ParameterizedTest
  @ValueSource(strings = { "maven-build", "maven-test" })
  void mavenMatrixJobsSaveTheArtifactsTheyDownload(String job) throws IOException {
    String definition = workflowJob(job);

    // setup-java's cache saves only on a key miss, so a hit freezes the snapshot forever.
    assertThat(definition).doesNotContain("cache: maven");
    assertThat(definition).contains("uses: actions/cache@", "path: ~/.m2/repository", "restore-keys:");
    assertThat(definition).containsPattern("key: [^\\n]*\\$\\{\\{ github\\.run_id \\}\\}");
  }

  private static Map<String, String> mavenConfigFlags() throws IOException {
    Map<String, String> flags = new LinkedHashMap<>();
    for (String line : Files.readAllLines(ROOT.resolve(".mvn/maven.config"))) {
      String flag = line.strip();
      if (flag.isEmpty()) {
        continue;
      }
      // Maven 3 has no comment syntax here; every line is passed through as a CLI argument.
      assertThat(flag).startsWith("-D").contains("=");
      int separator = flag.indexOf('=');
      flags.put(flag.substring(2, separator), flag.substring(separator + 1));
    }
    return flags;
  }

  private static String workflowJob(String name) throws IOException {
    List<String> lines = Files.readAllLines(ROOT.resolve(".github/workflows/ci.yml"));
    StringBuilder definition = new StringBuilder();
    boolean inside = false;
    for (String line : lines) {
      if (line.startsWith("  " + name + ":")) {
        inside = true;
      }
      else if (inside && !line.isBlank() && !line.startsWith("   ")) {
        break;
      }
      if (inside) {
        definition.append(line).append('\n');
      }
    }
    assertThat(definition).as("job %s in ci.yml", name).isNotEmpty();
    return definition.toString();
  }

  private static Path repositoryRoot() {
    Path start = Path.of("").toAbsolutePath();
    for (Path directory = start; directory != null; directory = directory.getParent()) {
      if (Files.isDirectory(directory.resolve(".github/workflows"))) {
        return directory;
      }
    }
    throw new IllegalStateException("No repository root above " + start);
  }
}
