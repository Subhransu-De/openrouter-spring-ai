package de.subhransu.openrouter.springai.garage.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.garage.GarageProperties;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class GarageReportWriterTests {

  @TempDir Path output;

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void reportStatusIncludesRequiredEvidenceCompleteness(boolean incomplete) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    GarageReportWriter writer = new GarageReportWriter(mapper, new GarageEvidence(),
        mock(GarageTelemetry.class), mock(GarageTransportEvidence.class));
    GarageCommand command = GarageCommand.from(new String[] {"--text"}, new GarageProperties());
    SceneResult result = SceneResult.passed("digital-inspection", "synthetic-operation",
        OpenRouterRequestMode.OPENAI_RESPONSES, Duration.ZERO, this.output, Map.of());
    List<String> missing = incomplete ? List.of("structured-output") : List.of();

    GarageReportWriter.ReportPaths reports = writer.write(this.output, command, List.of(result), missing);

    var json = mapper.readTree(reports.json().toFile());
    String expected = incomplete ? "failed" : "passed";
    assertThat(json.get("status").stringValue()).isEqualTo(expected);
    assertThat(json.get("incompleteFeatures").size()).isEqualTo(missing.size());
    assertThat(Files.readString(reports.markdown())).contains("# Run status: " + expected);
  }
}
