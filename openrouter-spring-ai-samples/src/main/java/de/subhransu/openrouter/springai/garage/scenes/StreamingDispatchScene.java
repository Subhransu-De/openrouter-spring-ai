package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.garage.GarageCosts;
import de.subhransu.openrouter.springai.garage.GarageResponses;
import de.subhransu.openrouter.springai.garage.GarageTools;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/** Plain streaming plus one multi-field streamed service-bulletin tool call. */
@Component
public final class StreamingDispatchScene extends GarageSceneSupport {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  public StreamingDispatchScene() {
    super(
        "streaming-dispatch",
        "Streaming dispatch",
        "Retains stream observations and proves one merged multi-field callback execution.",
        false);
  }

  @Override
  public SceneResult execute(SceneContext context) throws Exception {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    List<GarageFeature> applicable = new ArrayList<>(features());
    if (context.requestMode() == OpenRouterRequestMode.OPENAI_RESPONSES) {
      applicable.remove(GarageFeature.STREAMING_TOOL_AGGREGATION);
    }
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.CONFIGURED, "scene", id());

    OpenRouterChatOptions plainOptions =
        context.optionsFactory()
            .plain(
                operationId,
                id(),
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic());
    Prompt plainPrompt =
        new Prompt(
            List.of(
                new SystemMessage("Stream a three-sentence Garage intake note."),
                new UserMessage(context.command().topic())),
            plainOptions);
    List<ChatResponse> plainChunks;
    try (GarageTransportEvidence.Scope ignored =
        context.transportEvidence().activate(operationId, id())) {
      plainChunks = context.chatModel().stream(plainPrompt).collectList().block(TIMEOUT);
    }
    if (plainChunks == null) {
      plainChunks = List.of();
    }

    List<Map<String, Object>> toolInvocations = List.of();
    int toolStreamEvents = 0;
    double toolStreamCost = 0.0;
    if (context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
      GarageTools tools =
          new GarageTools(
              context.chatModel(),
              context.properties(),
              context.outputDirectory(),
              context.requestMode(),
              operationId,
              id());
      ToolCallback callback =
          Arrays.stream(ToolCallbacks.from(tools))
              .filter(
                  item ->
                      "lookup_service_bulletin".equals(item.getToolDefinition().name()))
              .findFirst()
              .map(
                  item ->
                      GarageToolCallback.wrap(
                          item,
                          context.evidence(),
                          operationId,
                          id(),
                          mode,
                          GarageFeature.STREAMING_TOOL_AGGREGATION))
              .orElseThrow();
      OpenRouterChatOptions toolOptions =
          context.optionsFactory()
              .streamingDispatch(
                  operationId,
                  context.requestMode(),
                  context.command().foremanModel(),
                  context.command().topic(),
                  callback)
              .mutate()
              .toolChoice("auto")
              .build();
      Prompt toolPrompt =
          new Prompt(
              List.of(
                  new SystemMessage(
                      "You are dispatch. Call lookup_service_bulletin exactly once with VIN prefix"
                          + " TRK7, model year 1972, and symptom overheating. Then summarize it."),
                  new UserMessage("Dispatch the required bulletin lookup now.")),
              toolOptions);
      List<ChatResponse> toolChunks;
      try (GarageTransportEvidence.Scope ignored =
          context.transportEvidence().activate(operationId, id())) {
        toolChunks =
            context.chatClient().prompt(toolPrompt).stream().chatResponse().collectList().block(TIMEOUT);
      }
      toolStreamEvents = toolChunks != null ? toolChunks.size() : 0;
      toolStreamCost = toolChunks != null ? GarageCosts.stream(toolChunks) : 0.0;
      toolInvocations = tools.invocations();
    }

    int visibleCharacters =
        plainChunks.stream().mapToInt(chunk -> GarageResponses.text(chunk).length()).sum();
    int reasoningCharacters =
        plainChunks.stream().mapToInt(chunk -> GarageResponses.reasoning(chunk).length()).sum();
    long bulletinCalls =
        toolInvocations.stream()
            .filter(item -> "lookup_service_bulletin".equals(item.get("tool")))
            .count();
    List<Map<String, Object>> observations =
        context.telemetry().awaitObservationsFor(operationId, Duration.ofSeconds(1));
    List<String> failures = new ArrayList<>();
    if (plainChunks.isEmpty() || visibleCharacters + reasoningCharacters == 0) {
      failures.add("plain stream produced no visible or reasoning signal");
    }
    if (observations.stream().noneMatch(item -> Boolean.TRUE.equals(item.get("streaming")))) {
      failures.add("no correlated streaming observation was retained");
    }
    if (context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
        && bulletinCalls != 1) {
      failures.add("streamed bulletin callback executed " + bulletinCalls + " times instead of once");
    }

    Map<String, Object> details = new LinkedHashMap<>();
    details.put("plainEvents", plainChunks.size());
    details.put("visibleCharacters", visibleCharacters);
    details.put("reasoningCharacters", reasoningCharacters);
    details.put("toolStreamEvents", toolStreamEvents);
    details.put("bulletinCalls", bulletinCalls);
    details.put(
        "toolAggregation",
        context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
            ? "executed"
            : "unsupported-in-responses-mode");
    details.put("observations", observations);
    details.put("transport", context.transportEvidence().forOperation(operationId));
    details.put("costUsd", GarageCosts.stream(plainChunks) + toolStreamCost);
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.EXECUTED, "stream", details);
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.OBSERVED, "stream", details);
    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      applicable.forEach(feature -> context.evidence().error(feature, operationId, mode, failure));
      throw failure;
    }
    context.evidence().recordAll(
        applicable, operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }
}
