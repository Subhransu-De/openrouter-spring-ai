package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Each provider returns reasoning in its own format, with opaque encrypted data or signatures
 * that must reach the next tool round unchanged. The first service-story round of each scenario
 * answers in one format; the follow-up request must carry every opaque value back.
 */
class GarageReasoningReplayIntegrationTests extends MockedGarageIntegrationTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  record Format(String scenario, String mode) {

    @Override
    public String toString() {
      return this.scenario;
    }
  }

  static Stream<Format> formats() {
    return Stream.of(
        new Format("service-story-gemini-reasoning", "chat"),
        new Format("service-story-anthropic-reasoning", "chat"),
        new Format("service-story-openai-reasoning", "chat"),
        new Format("service-story-responses-gemini-reasoning", "responses"),
        new Format("service-story-responses-anthropic-reasoning", "responses"),
        new Format("service-story-responses-openai-reasoning", "responses"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("formats")
  void opaqueReasoningIsReplayedUnchanged(Format format) throws Exception {
    OpenRouterMock.useScenario(format.scenario());

    JsonNode run = runToCompletion(
        "{\"scenes\":[\"service-story\"],\"requestModes\":[\"" + format.mode() + "\"]}");

    assertThat(run.path("status").asString()).isEqualTo("PASSED");
    boolean chat = "chat".equals(format.mode());
    JsonNode followUp = OpenRouterMock.requests(chat ? "/chat/completions" : "/responses").stream()
        .filter(request -> chat
            ? request.path("messages").toString().contains("\"tool_call_id\":\"call_inspect\"")
            : request.path("input").toString().contains("\"function_call_output\""))
        .findFirst().orElseThrow();
    List<String> opaque = opaqueValues(format.scenario());
    assertThat(opaque).as("fixture opaque values").isNotEmpty();
    String replayed = (chat ? followUp.path("messages") : followUp.path("input")).toString();
    assertThat(replayed).as("follow-up request").contains(opaque);
    assertThat(OpenRouterMock.unmatched()).isEmpty();
  }

  private static List<String> opaqueValues(String scenario) throws Exception {
    String body = new ClassPathResource("openrouter-mock/__files/scenarios/" + scenario + "/inspect.json")
        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    List<String> values = new ArrayList<>();
    collect(JSON.readTree(body.strip()), values);
    return values;
  }

  private static void collect(JsonNode node, List<String> values) {
    if (node.isObject()) {
      for (String field : List.of("data", "signature", "encrypted_content")) {
        if (node.path(field).isString()) {
          values.add(node.path(field).asString());
        }
      }
      node.forEach(child -> collect(child, values));
    } else if (node.isArray()) {
      node.forEach(child -> collect(child, values));
    }
  }
}
