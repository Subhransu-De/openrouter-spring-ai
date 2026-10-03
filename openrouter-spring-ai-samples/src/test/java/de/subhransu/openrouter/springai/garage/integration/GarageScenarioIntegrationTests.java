package de.subhransu.openrouter.springai.garage.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;

/**
 * Sad paths and edge cases from {@code openrouter-mock/scenarios}. A sad path must fail exactly
 * the targeted scene for the intended reason while the rest of the selection passes; an edge case
 * must pass. Every case also proves the mock answered every request it received.
 */
// Keep scene names and reasons explicit in each case.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@ExtendWith(OutputCaptureExtension.class)
class GarageScenarioIntegrationTests extends MockedGarageIntegrationTest {

  private static final String CHAT_MODE = "OPENAI_CHAT_COMPLETIONS";
  private static final String RESPONSES_MODE = "OPENAI_RESPONSES";
  private static final String ANY_MODE = "*";

  private static final String SERVICE_CHAT = "{\"scenes\":[\"service-story\"],\"requestModes\":[\"chat\"]}";
  private static final String SERVICE_RESPONSES =
      "{\"scenes\":[\"service-story\"],\"requestModes\":[\"responses\"]}";
  private static final String ROUTING = text("routing-lane", "chat");
  private static final String INSPECTION = text("digital-inspection", "chat");
  private static final String INVOICE = text("express-invoice", "chat");
  private static final String DISPATCH_CHAT = text("streaming-dispatch", "chat");
  private static final String DISPATCH_RESPONSES = text("streaming-dispatch", "responses");
  private static final String ATTRIBUTION = text("attribution-check-in", "chat");
  private static final String VISION = "{\"capabilities\":[\"vision\"]}";
  private static final String EMBEDDINGS = "{\"capabilities\":[\"embedding\"]}";
  private static final String IMAGE_SYNC = "{\"capabilities\":[\"image\"]}";
  private static final String IMAGE_STREAM = "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"streaming\"}}";
  // OpenAI image models stream partial images; Gemini image models answer stream=true with JSON.
  private static final String OPENAI_IMAGE_STREAM = "images-stream-openai-sse";
  private static final String IMAGE_STREAM_OPENAI =
      "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"streaming\"},\"models\":{\"image\":\"openai/gpt-5-image-mini\"}}";
  private static final String IMAGE_CHAT = "{\"capabilities\":[\"image\"],\"image\":{\"surface\":\"chat\"}}";

  private static final String CHECK = "check-failed";
  private static final String PROVIDER = "provider-error";
  private static final String PROTOCOL = "protocol-error";
  private static final String TRUNCATED = "truncated";
  private static final String NO_ENDPOINT = "no-endpoint";

  record SadPath(String scenario, String request, String scene, String mode, String code, String reason) {

    @Override
    public String toString() {
      return this.scenario;
    }
  }

  static Stream<SadPath> sadPaths() {
    return Stream.of(
        new SadPath("service-story-text-only", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "Required model-directed tool calls and follow-ups did not complete"),
        new SadPath("service-story-malformed-arguments", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "inspect_vehicle_profile did not complete"),
        new SadPath("service-story-unknown-tool", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "No ToolCallback found for tool name: replace_engine"),
        new SadPath("service-story-reused-call-id", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "Tool call ID was missing or reused"),
        new SadPath("service-story-missing-cost", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "cost or cached-token evidence was missing"),
        new SadPath("service-story-no-reasoning", SERVICE_CHAT, "service-story", CHAT_MODE, CHECK,
            "reasoning text or reasoning-token evidence was missing"),
        new SadPath("service-story-specialist-unavailable", SERVICE_CHAT, "service-story", CHAT_MODE, PROVIDER,
            "hand_to_specialist did not complete"),
        new SadPath("service-story-responses-incomplete", SERVICE_RESPONSES, "service-story",
            RESPONSES_MODE, TRUNCATED, "final Foreman response was empty"),
        new SadPath("routing-lane-no-fallback", ROUTING, "routing-lane", CHAT_MODE, CHECK,
            "served model did not prove fallback from the unavailable primary"),
        new SadPath("routing-lane-invalid-model", ROUTING, "routing-lane", CHAT_MODE, PROVIDER,
            "failed with status 400"),
        new SadPath("routing-lane-missing-provider", ROUTING, "routing-lane", CHAT_MODE, CHECK,
            "served provider was missing"),
        new SadPath("digital-inspection-schema-violation", INSPECTION, "digital-inspection", CHAT_MODE, CHECK,
            "structured inspection did not satisfy the required schema"),
        new SadPath("digital-inspection-not-json", INSPECTION, "digital-inspection", CHAT_MODE, CHECK,
            "Unrecognized token 'The'"),
        new SadPath("express-invoice-text-reply", INVOICE, "express-invoice", CHAT_MODE, CHECK,
            "returnDirect contract mismatch"),
        new SadPath("streaming-dispatch-truncated", DISPATCH_CHAT, "streaming-dispatch", CHAT_MODE, TRUNCATED,
            "stream ended before protocol termination"),
        new SadPath("streaming-dispatch-duplicate-callback", DISPATCH_CHAT, "streaming-dispatch", CHAT_MODE, CHECK,
            "streamed bulletin callback executed 2 times instead of once"),
        new SadPath("streaming-dispatch-responses-failed", DISPATCH_RESPONSES, "streaming-dispatch",
            RESPONSES_MODE, PROVIDER, "OpenRouter responses stream failed"),
        new SadPath("attribution-unauthorized", ATTRIBUTION, "attribution-check-in", CHAT_MODE, PROVIDER,
            "failed with status 401"),
        new SadPath("vision-misread", VISION, "modality-bays", ANY_MODE, CHECK,
            "model reply did not read the CHECK ENGINE warning from the photo"),
        new SadPath("embeddings-count-mismatch", EMBEDDINGS, "modality-bays", CHAT_MODE, PROTOCOL,
            "Embedding response count must match input count"),
        new SadPath("images-undecodable", IMAGE_SYNC, "modality-bays", CHAT_MODE, CHECK,
            "generated image was empty or could not be decoded"),
        new SadPath("images-webp-without-signature", IMAGE_SYNC, "modality-bays", CHAT_MODE, CHECK,
            "generated image was empty or could not be decoded"),
        new SadPath("images-stream-partial-only", IMAGE_STREAM, "modality-bays", CHAT_MODE, TRUNCATED,
            "OpenRouter image stream ended without a completed image"),
        new SadPath("images-stream-error", IMAGE_STREAM, "modality-bays", CHAT_MODE, PROVIDER,
            "OpenRouter image generation stream failed"),
        new SadPath("chat-paint-without-image", IMAGE_CHAT, "modality-bays", CHAT_MODE, CHECK,
            "assistant message carried no generated-image media"),
        new SadPath("service-story-specialist-cut-off", SERVICE_CHAT, "service-story", CHAT_MODE, TRUNCATED,
            "stopped at its token limit"),
        new SadPath("vision-cut-off", VISION, "modality-bays", CHAT_MODE, TRUNCATED,
            "did not read the CHECK ENGINE warning"),
        new SadPath("chat-paint-cut-off-image", IMAGE_CHAT, "modality-bays", CHAT_MODE, TRUNCATED,
            "paint_bay/chat-modalities"),
        new SadPath("digital-inspection-no-endpoint", INSPECTION, "digital-inspection", CHAT_MODE, NO_ENDPOINT,
            "failed with status 404"),
        new SadPath("digital-inspection-choice-error", INSPECTION, "digital-inspection", CHAT_MODE, PROVIDER,
            "chat-completion choice failed"),
        new SadPath("streaming-dispatch-no-tool-endpoint", DISPATCH_CHAT, "streaming-dispatch", CHAT_MODE,
            NO_ENDPOINT, "failed with status 404"),
        new SadPath("embeddings-guardrail-blocked", EMBEDDINGS, "modality-bays", CHAT_MODE, NO_ENDPOINT,
            "failed with status 404"),
        new SadPath("service-story-blank-tool-name", SERVICE_CHAT, "service-story", CHAT_MODE, PROTOCOL,
            "nonblank tool call name"),
        new SadPath("service-story-responses-blank-tool-name", SERVICE_RESPONSES, "service-story",
            RESPONSES_MODE, PROTOCOL, "nonblank tool call name"));
  }

  record EdgeCase(String scenario, String request) {

    @Override
    public String toString() {
      return this.scenario;
    }
  }

  static Stream<EdgeCase> edgeCases() {
    return Stream.of(
        new EdgeCase("images-svg", IMAGE_SYNC),
        new EdgeCase("images-without-media-type", IMAGE_SYNC),
        new EdgeCase("images-stream-json-fallback", IMAGE_STREAM),
        new EdgeCase("routing-lane-rate-limited-once", ROUTING),
        new EdgeCase("service-story-parallel-tool-calls", SERVICE_CHAT),
        new EdgeCase("streaming-dispatch-keepalive", DISPATCH_CHAT),
        new EdgeCase(OPENAI_IMAGE_STREAM, IMAGE_STREAM_OPENAI));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sadPaths")
  void aSadPathFailsOnlyTheTargetedSceneForTheIntendedReason(SadPath sadPath, CapturedOutput output) {
    OpenRouterMock.useScenario(sadPath.scenario());

    JsonNode run = runToCompletion(sadPath.request());

    assertThat(run.path("status").asString()).isEqualTo("FAILED");
    run.path("scenes").forEach(scene -> {
      boolean targeted = sadPath.scene().equals(scene.path("sceneId").asString())
          && (ANY_MODE.equals(sadPath.mode()) || sadPath.mode().equals(scene.path("requestMode").asString()));
      assertThat(scene.path("status").asString())
          .as(scene.path("sceneId").asString() + " in " + scene.path("requestMode").asString())
          .isEqualTo(targeted ? "FAILED" : "PASSED");
      if (targeted) {
        assertThat(scene.path("reason").asString()).as("failure reason code").isEqualTo(sadPath.code());
      } else {
        assertThat(scene.path("reason").isNull()).as("passing scene has no reason").isTrue();
      }
    });
    assertThat(failureLines(output, sadPath.scene())).as("logged failure reasons")
        .anyMatch(line -> line.contains(sadPath.reason()));
    assertThat(OpenRouterMock.unmatched()).as("requests the mock could not answer").isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("edgeCases")
  void anEdgeCaseStillPasses(EdgeCase edgeCase, CapturedOutput output) {
    OpenRouterMock.useScenario(edgeCase.scenario());

    JsonNode run = runToCompletion(edgeCase.request());

    assertThat(run.path("status").asString()).as(String.join("\n", failureLines(output, ""))).isEqualTo("PASSED");
    assertThat(run.path("scenes")).allSatisfy(scene -> assertThat(scene.path("status").asString())
        .isEqualTo("PASSED"));
    assertThat(OpenRouterMock.unmatched()).isEmpty();
  }

  private static List<String> failureLines(CapturedOutput output, String scene) {
    List<String> lines = new ArrayList<>();
    for (String line : output.getAll().split("\\R")) {
      if (line.contains("FAIL " + scene)) {
        lines.add(line.substring(line.indexOf("FAIL ")));
      }
    }
    return lines;
  }

  private static String text(String scene, String mode) {
    return "{\"capabilities\":[\"text\"],\"scenes\":[\"" + scene + "\"],\"requestModes\":[\"" + mode + "\"]}";
  }
}
