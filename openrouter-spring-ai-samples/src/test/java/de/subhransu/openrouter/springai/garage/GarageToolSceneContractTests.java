package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesContent;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesOutputItem;
import de.subhransu.openrouter.springai.api.dto.Usage;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatProperties;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.garage.cli.GarageCommand;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageObservationConvention;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.ExpressInvoiceScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import de.subhransu.openrouter.springai.garage.scenes.ServiceStoryScene;
import de.subhransu.openrouter.springai.garage.scenes.StreamingDispatchScene;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import reactor.core.publisher.Flux;

class GarageToolSceneContractTests {

  @TempDir Path output;

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void returnDirectInvoiceUsesExactlyOneModelCall(OpenRouterRequestMode mode) {
    OpenRouterApi api = mock(OpenRouterApi.class);
    when(api.chatCompletion(any()))
        .thenReturn(
            new ChatCompletionResponse(
                "invoice-1",
                "chat.completion",
                1L,
                "garage/model",
                "garage-stub",
                List.of(
                    new Choice(
                        0,
                        new ChatMessage(
                            "assistant",
                            "",
                            null,
                            null,
                            List.of(
                                new ToolCall(
                                    "call-1",
                                    "function",
                                    new FunctionCall(
                                        "generate_express_invoice",
                                        "{\"item\":\"diagnostic inspection\",\"amount\":89}")))),
                        null,
                        "tool_calls",
                        "tool_calls")),
                null));
    when(api.responses(any()))
        .thenReturn(new ResponsesResult(
            "invoice-1", "response", 1L, "garage/model", "completed",
            List.of(new ResponsesOutputItem(
                "item-1", "function_call", "completed", null, null, "call-1",
                "generate_express_invoice",
                "{\"item\":\"diagnostic inspection\",\"amount\":89}", null)),
            null, null));
    TestContext test = context(api, "express-invoice", mode);

    SceneResult result = new ExpressInvoiceScene().execute(test.context());

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    verify(api, times(mode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS ? 1 : 0)).chatCompletion(any());
    verify(api, times(mode == OpenRouterRequestMode.OPENAI_RESPONSES ? 1 : 0)).responses(any());
  }

  @Test
  void fragmentedStreamingArgumentsMergeIntoOneGarageCallback() throws Exception {
    OpenRouterApi api = mock(OpenRouterApi.class);
    when(api.chatCompletionStream(any()))
        .thenReturn(
            Flux.just(textChunk("plain intake", "stop")),
            Flux.just(
                toolChunk("call-2", "lookup_service_bulletin", "{\"vinPrefix\":\"TR", null),
                toolChunk(null, null, "K7\",\"modelYear\":19", null),
                toolChunk(null, null, "72,\"symptom\":\"overheating\"}", "tool_calls")),
            Flux.just(textChunk("bulletin dispatched", "stop")));
    TestContext test = context(api, "streaming-dispatch");

    SceneResult result = new StreamingDispatchScene().execute(test.context());

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat(result.details()).containsEntry("bulletinCalls", 1L);
    verify(api, times(3)).chatCompletionStream(any());
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void dynoTuningExecutesInBothModes(OpenRouterRequestMode mode) {
    var test = context(mock(OpenRouterApi.class), "dyno-tuning", mode);
    var result = new de.subhransu.openrouter.springai.garage.scenes.DynoTuningScene().execute(test.context());
    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void serviceStoryRejectsTextWithoutModelToolCalls(OpenRouterRequestMode mode) {
    OpenRouterApi api = mock(OpenRouterApi.class);
    stubStory(api, List.of());
    var test = context(api, "service-story", mode);
    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new ServiceStoryScene().execute(test.context()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("model-directed tool calls");
    assertThat(test.context().evidence().featureSnapshot()).noneMatch(item -> Boolean.TRUE.equals(item.get("complete")));
  }

  @ParameterizedTest
  @EnumSource(OpenRouterRequestMode.class)
  void serviceStoryRequiresAllFourModelDirectedToolsAndFollowUp(OpenRouterRequestMode mode) throws Exception {
    OpenRouterApi api = mock(OpenRouterApi.class);
    stubStory(api, List.of(
        storyCall("inspect_vehicle_profile", "{\"concern\":\"synthetic\",\"severity\":4,\"safetyCritical\":true}"),
        storyCall("hand_to_specialist", "{\"job\":\"synthetic inspection\"}"),
        storyCall("score_repair_plan", "{\"safetyRisk\":4,\"reliabilityRisk\":3,\"costRisk\":2}"),
        storyCall("log_to_jobsheet", "{\"title\":\"Synthetic\",\"markdown\":\"Inspect brakes\"}")));
    var test = context(api, "service-story", mode);
    var result = new ServiceStoryScene().execute(test.context());
    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat((Double) result.details().get("costUsd"))
        .isCloseTo(0.03, org.assertj.core.api.Assertions.within(0.000001));
    assertThat(test.context().evidence().featureSnapshot()).allMatch(item -> Boolean.TRUE.equals(item.get("complete")));
    verify(api, times(mode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS ? 3 : 0)).chatCompletion(any());
    verify(api, times(mode == OpenRouterRequestMode.OPENAI_RESPONSES ? 3 : 0)).responses(any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing-tools", "wrong-tool", "malformed", "wrong-type"})
  void serviceStoryRejectsInvalidToolRounds(String variation) {
    for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
      OpenRouterApi api = mock(OpenRouterApi.class);
      ToolCall call = switch (variation) {
        case "wrong-tool" -> storyCall("generate_express_invoice", "{\"item\":\"synthetic\",\"amount\":1}");
        case "malformed" -> storyCall("inspect_vehicle_profile", "{");
        case "wrong-type" -> storyCall("inspect_vehicle_profile", "{\"concern\":\"synthetic\",\"severity\":{},\"safetyCritical\":true}");
        default -> storyCall("log_to_jobsheet", "{\"title\":\"Synthetic\",\"markdown\":\"Inspect brakes\"}");
      };
      stubStory(api, List.of(call));
      var test = context(api, "service-story", mode);
      org.assertj.core.api.Assertions.assertThatThrownBy(
          () -> new ServiceStoryScene().execute(test.context()))
          .isInstanceOf(RuntimeException.class);
      assertThat(test.context().evidence().featureSnapshot()).noneMatch(item -> Boolean.TRUE.equals(item.get("complete")));
    }
  }

  private ToolCall storyCall(String name, String arguments) {
    return new ToolCall("synthetic-" + name, "function", new FunctionCall(name, arguments));
  }

  private void stubStory(OpenRouterApi api, List<ToolCall> calls) {
    var usage = new Usage(10, 5, 15, 0, 1, 0.01, null, null, null);
    var answer = new ChatCompletionResponse("synthetic", "chat.completion", 1L, "garage/model", null,
        List.of(new Choice(0, new ChatMessage("assistant", "Synthetic recommendation", null, null, null), null, "stop", "stop")), usage);
    var toolRound = new ChatCompletionResponse("synthetic-tools", "chat.completion", 1L, "garage/model", null,
        List.of(new Choice(0, new ChatMessage("assistant", "", null, null, calls), null, "tool_calls", "tool_calls")), usage);
    when(api.chatCompletion(any())).thenReturn(calls.isEmpty() ? answer : toolRound, answer);
    var responseAnswer = new ResponsesResult("synthetic", "response", 1L, "garage/model", "completed",
        List.of(new ResponsesOutputItem("synthetic-message", "message", "completed", "assistant",
            List.of(new ResponsesContent("output_text", "Synthetic recommendation")))), usage, null);
    var responseCalls = calls.stream().map(call -> new ResponsesOutputItem("item-" + call.id(),
        "function_call", "completed", null, null, call.id(), call.function().name(), call.function().arguments(), null)).toList();
    when(api.responses(any())).thenReturn(calls.isEmpty() ? responseAnswer : new ResponsesResult(
        "synthetic-tools", "response", 1L, "garage/model", "completed", responseCalls, usage, null), responseAnswer);
  }

  @Test
  void streamingToolResultWithoutAFollowUpResponseCannotPass() {
    OpenRouterApi api = mock(OpenRouterApi.class);
    when(api.chatCompletionStream(any())).thenReturn(
        Flux.just(textChunk("synthetic intake", "stop")),
        Flux.just(toolChunk("synthetic-call", "lookup_service_bulletin",
            "{\"vinPrefix\":\"TRK7\",\"modelYear\":1972,\"symptom\":\"synthetic\"}", "tool_calls")),
        Flux.empty());
    var test = context(api, "streaming-dispatch");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> new StreamingDispatchScene().execute(test.context()))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no response");
  }

  private TestContext context(OpenRouterApi api, String sceneId) {
    return context(api, sceneId, OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS);
  }

  private TestContext context(OpenRouterApi api, String sceneId, OpenRouterRequestMode mode) {
    GarageProperties properties = new GarageProperties();
    properties.setReasoningEnabled(false);
    GarageEvidence evidence = new GarageEvidence();
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    GarageTelemetry telemetry = new GarageTelemetry(meters, evidence);
    ObservationRegistry registry = ObservationRegistry.create();
    registry
        .observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(meters))
        .observationHandler(telemetry);
    OpenRouterChatModel model =
        OpenRouterChatModel.builder()
            .openRouterApi(api)
            .defaultOptions(applicationChatProperties().toOptions())
            .observationRegistry(registry)
            .objectMapper(new ObjectMapper())
            .build();
    model.setObservationConvention(new GarageObservationConvention());
    GarageCommand command =
        GarageCommand.from(
            new String[] {"--scene=" + sceneId, "--foreman-model=garage/model"},
            properties);
    GarageTransportEvidence transport = new GarageTransportEvidence(evidence);
    return new TestContext(
        new SceneContext(
            command,
            mode,
            this.output.resolve(sceneId),
            model,
            ChatClient.builder(model).build(),
            properties,
            new GarageOptionsFactory(properties),
            new ObjectMapper(),
            evidence,
            telemetry,
            transport,
            registry));
  }

  private OpenRouterChatProperties applicationChatProperties() {
    StandardEnvironment environment = new StandardEnvironment();
    // Use the shipped YAML without inheriting developer machine overrides.
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    try {
      new YamlPropertySourceLoader().load("garage", new ClassPathResource("application.yml"))
          .forEach(environment.getPropertySources()::addLast);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return Binder.get(environment)
        .bind("spring.ai.openrouter.chat", OpenRouterChatProperties.class).get();
  }

  private ChatCompletionChunk textChunk(String text, String finishReason) {
    return new ChatCompletionChunk(
        "chunk-1",
        "chat.completion.chunk",
        1L,
        "garage/model",
        "garage-stub",
        List.of(
            new Choice(
                0,
                null,
                new Delta("assistant", text, null, null),
                finishReason,
                finishReason)),
        null,
        null);
  }

  private ChatCompletionChunk toolChunk(
      String id, String name, String arguments, String finishReason) {
    return new ChatCompletionChunk(
        "chunk-tool",
        "chat.completion.chunk",
        1L,
        "garage/model",
        "garage-stub",
        List.of(
            new Choice(
                0,
                null,
                new Delta(
                    "assistant",
                    null,
                    null,
                    List.of(
                        new ToolCall(
                            id,
                            "function",
                            new FunctionCall(name, arguments),
                            0))),
                finishReason,
                finishReason)),
        null,
        null);
  }

  private record TestContext(SceneContext context) {}
}
