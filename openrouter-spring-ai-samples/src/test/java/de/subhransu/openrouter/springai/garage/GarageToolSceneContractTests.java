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
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
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
import de.subhransu.openrouter.springai.garage.run.GarageRunPlan;
import de.subhransu.openrouter.springai.garage.evidence.GarageEvidence;
import de.subhransu.openrouter.springai.garage.evidence.GarageObservationConvention;
import de.subhransu.openrouter.springai.garage.evidence.GarageTelemetry;
import de.subhransu.openrouter.springai.garage.evidence.GarageTransportEvidence;
import de.subhransu.openrouter.springai.garage.scenes.ExpressInvoiceScene;
import de.subhransu.openrouter.springai.garage.scenes.SceneContext;
import de.subhransu.openrouter.springai.garage.scenes.SceneResult;
import de.subhransu.openrouter.springai.garage.scenes.ServiceStoryScene;
import de.subhransu.openrouter.springai.garage.scenes.StreamingDispatchScene;
import io.micrometer.observation.ObservationRegistry;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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

  private static final String INVOICE_TOOL = "generate_express_invoice";

  private static final String INCOMPLETE = "Required model-directed tool calls and follow-ups did not complete";

  private final List<GarageTelemetryFixture> fixtures = new ArrayList<>();

  @AfterEach
  void closeFixtures() {
    this.fixtures.forEach(GarageTelemetryFixture::close);
  }

  @TempDir Path output;

  /** The mode under test and how many calls each of its two endpoints must receive. */
  static Stream<Arguments> endpointCalls() {
    return endpointCalls(1);
  }

  static Stream<Arguments> storyEndpointCalls() {
    return endpointCalls(3);
  }

  private static Stream<Arguments> endpointCalls(int calls) {
    return Stream.of(
        Arguments.of(Named.of("Chat Completions", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS), calls, 0),
        Arguments.of(Named.of("Responses", OpenRouterRequestMode.OPENAI_RESPONSES), 0, calls));
  }

  @ParameterizedTest
  @MethodSource("endpointCalls")
  void returnDirectInvoiceUsesExactlyOneModelCall(OpenRouterRequestMode mode, int chatCalls,
      int responsesCalls) {
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
                                        INVOICE_TOOL,
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
                INVOICE_TOOL,
                "{\"item\":\"diagnostic inspection\",\"amount\":89}", null)),
            null, null));
    TestContext test = context(api, "express-invoice", mode);

    SceneResult result = new ExpressInvoiceScene().execute(test.context());

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    verify(api, times(chatCalls)).chatCompletion(any());
    verify(api, times(responsesCalls)).responses(any());
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
    ArgumentCaptor<ChatCompletionRequest> requests = ArgumentCaptor.forClass(ChatCompletionRequest.class);
    verify(api, times(3)).chatCompletionStream(requests.capture());
    // The follow-up request replays the three fragments as one complete argument document.
    var mapper = new ObjectMapper();
    var followUp = mapper.readTree(mapper.writeValueAsString(requests.getAllValues().get(2)));
    assertThat(followUp.findValues("arguments")).extracting(node -> node.asString())
        .contains("{\"vinPrefix\":\"TRK7\",\"modelYear\":1972,\"symptom\":\"overheating\"}");
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
  @MethodSource("storyEndpointCalls")
  void serviceStoryRequiresAllFourModelDirectedToolsAndFollowUp(OpenRouterRequestMode mode, int chatCalls,
      int responsesCalls) throws Exception {
    OpenRouterApi api = mock(OpenRouterApi.class);
    stubStory(api, requiredStoryCalls());
    var test = context(api, "service-story", mode);
    var result = new ServiceStoryScene().execute(test.context());
    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat((Double) result.details().get("costUsd"))
        .isCloseTo(0.03, org.assertj.core.api.Assertions.within(0.000001));
    assertThat(test.context().evidence().featureSnapshot()).allMatch(item -> Boolean.TRUE.equals(item.get("complete")));
    verify(api, times(chatCalls)).chatCompletion(any());
    verify(api, times(responsesCalls)).responses(any());
  }

  /** The only tool round the model answers with, the exception it must cause and its message. */
  record InvalidRound(ToolCall call, Class<? extends RuntimeException> failureType, String message) {
  }

  static Stream<Arguments> invalidToolRounds() {
    List<Arguments> cases = new java.util.ArrayList<>();
    for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
      cases.add(Arguments.of(Named.of(mode + " missing tools", mode),
          new InvalidRound(storyCall("log_to_jobsheet", "{\"title\":\"Synthetic\",\"markdown\":\"Inspect brakes\"}"),
              IllegalStateException.class, INCOMPLETE)));
      cases.add(Arguments.of(Named.of(mode + " wrong tool", mode),
          new InvalidRound(storyCall(INVOICE_TOOL, "{\"item\":\"synthetic\",\"amount\":1}"),
              IllegalStateException.class, INVOICE_TOOL)));
      cases.add(Arguments.of(Named.of(mode + " malformed arguments", mode),
          new InvalidRound(storyCall("inspect_vehicle_profile", "{"), IllegalStateException.class, INCOMPLETE)));
      cases.add(Arguments.of(Named.of(mode + " wrong argument type", mode),
          new InvalidRound(storyCall("inspect_vehicle_profile",
              "{\"concern\":\"synthetic\",\"severity\":{},\"safetyCritical\":true}"),
              IllegalStateException.class, INCOMPLETE)));
    }
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("invalidToolRounds")
  void serviceStoryRejectsInvalidToolRounds(OpenRouterRequestMode mode, InvalidRound round) {
    OpenRouterApi api = mock(OpenRouterApi.class);
    stubStory(api, List.of(round.call()));
    var test = context(api, "service-story", mode);

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new ServiceStoryScene().execute(test.context()))
        .isExactlyInstanceOf(round.failureType())
        .hasMessageContaining(round.message());
    assertThat(test.context().evidence().featureSnapshot()).noneMatch(item -> Boolean.TRUE.equals(item.get("complete")));
  }

  private static ToolCall storyCall(String name, String arguments) {
    return new ToolCall("synthetic-" + name, "function", new FunctionCall(name, arguments));
  }

  private void stubStory(OpenRouterApi api, List<ToolCall> calls) {
    stubStory(api, calls, 1, 1, null);
  }

  private List<ToolCall> requiredStoryCalls() {
    return List.of(
        storyCall("inspect_vehicle_profile", "{\"concern\":\"synthetic\",\"severity\":4,\"safetyCritical\":true}"),
        storyCall("hand_to_specialist", "{\"job\":\"synthetic inspection\"}"),
        storyCall("score_repair_plan", "{\"safetyRisk\":4,\"reliabilityRisk\":3,\"costRisk\":2}"),
        storyCall("log_to_jobsheet", "{\"title\":\"Synthetic\",\"markdown\":\"Inspect brakes\"}"));
  }

  /** Where the foreman tool rounds carry reasoning evidence. */
  record ReasoningCase(String name, int earlyReasoningTokens, Integer finalReasoningTokens, String earlyText) {

    @Override
    public String toString() {
      return this.name;
    }
  }

  private static Stream<Arguments> inEveryMode(List<ReasoningCase> cases) {
    List<Arguments> arguments = new java.util.ArrayList<>();
    for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
      for (ReasoningCase reasoning : cases) {
        arguments.add(Arguments.of(Named.of(mode + ": " + reasoning, mode), reasoning));
      }
    }
    return arguments.stream();
  }

  static Stream<Arguments> acceptedReasoning() {
    return inEveryMode(List.of(
        new ReasoningCase("reasoning tokens on the tool round", 3, 0, null),
        new ReasoningCase("reasoning text on the tool round", 0, 0, "Synthetic tool selection reasoning"),
        new ReasoningCase("reasoning tokens only on the final round", 0, 2, null),
        new ReasoningCase("early tokens but final usage missing", 3, null, null),
        new ReasoningCase("early text but final usage missing", 0, null, "Synthetic tool selection reasoning")));
  }

  static Stream<Arguments> missingReasoning() {
    return inEveryMode(List.of(
        new ReasoningCase("no reasoning anywhere", 0, 0, null),
        new ReasoningCase("no reasoning and final usage missing", 0, null, null)));
  }

  @ParameterizedTest
  @MethodSource("acceptedReasoning")
  void serviceStoryAcceptsReasoningFoundInAnyForemanRound(OpenRouterRequestMode mode, ReasoningCase reasoning)
      throws Exception {
    var test = storyContext(mode, reasoning);

    var result = new ServiceStoryScene().execute(test.context());

    assertThat(result.status()).isEqualTo(SceneResult.Status.PASSED);
    assertThat(result.details()).containsEntry("reasoningObserved", true);
    var persisted = new ObjectMapper().valueToTree(
        test.context().evidence().sanitizeForEvidence(result.details()));
    assertThat(persisted.path("reasoningObserved").asBoolean()).isTrue();
  }

  @ParameterizedTest
  @MethodSource("missingReasoning")
  void serviceStoryRejectsARunWhoseForemanShowedNoReasoning(OpenRouterRequestMode mode, ReasoningCase reasoning)
      throws Exception {
    var test = storyContext(mode, reasoning);

    org.assertj.core.api.Assertions.assertThatThrownBy(
        () -> new ServiceStoryScene().execute(test.context()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("reasoning text or reasoning-token evidence was missing");
  }

  private TestContext storyContext(OpenRouterRequestMode mode, ReasoningCase reasoning) throws Exception {
    OpenRouterApi api = mock(OpenRouterApi.class);
    stubStory(api, requiredStoryCalls(), reasoning.earlyReasoningTokens(), reasoning.finalReasoningTokens(),
        reasoning.earlyText());
    return context(api, "service-story", mode);
  }

  private void stubStory(OpenRouterApi api, List<ToolCall> calls,
      int earlyReasoningTokens, Integer finalReasoningTokens, String reasoning) {
    var usage = new Usage(10, 5, 15, 0, finalReasoningTokens, 0.01, null, null, null);
    var toolUsage = new Usage(10, 5, 15, 0, earlyReasoningTokens, 0.01, null, null, null);
    var answer = new ChatCompletionResponse("synthetic", "chat.completion", 1L, "garage/model", null,
        List.of(new Choice(0, new ChatMessage("assistant", "Synthetic recommendation", null, null, null), null, "stop", "stop")), usage);
    var toolRound = new ChatCompletionResponse("synthetic-tools", "chat.completion", 1L, "garage/model", null,
        List.of(new Choice(0, new ChatMessage("assistant", "", null, null, calls, null, reasoning, null),
            null, "tool_calls", "tool_calls")), toolUsage);
    when(api.chatCompletion(any())).thenReturn(calls.isEmpty() ? answer : toolRound, answer);
    var responseAnswer = new ResponsesResult("synthetic", "response", 1L, "garage/model", "completed",
        List.of(new ResponsesOutputItem("synthetic-message", "message", "completed", "assistant",
            List.of(new ResponsesContent("output_text", "Synthetic recommendation")))), usage, null);
    var responseCalls = new java.util.ArrayList<>(calls.stream().map(call -> new ResponsesOutputItem("item-" + call.id(),
        "function_call", "completed", null, null, call.id(), call.function().name(), call.function().arguments(), null)).toList());
    if (reasoning != null) {
      responseCalls.add(0, new ObjectMapper().readValue("""
          {"id":"synthetic-reasoning","type":"reasoning","summary":[{"type":"summary_text","text":"Synthetic tool selection reasoning"}]}
          """, ResponsesOutputItem.class));
    }
    when(api.responses(any())).thenReturn(calls.isEmpty() ? responseAnswer : new ResponsesResult(
        "synthetic-tools", "response", 1L, "garage/model", "completed", responseCalls, toolUsage, null), responseAnswer);
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
    GarageTelemetryFixture fixture = new GarageTelemetryFixture(evidence);
    this.fixtures.add(fixture);
    GarageTelemetry telemetry = fixture.telemetry();
    ObservationRegistry registry = fixture.observations();
    OpenRouterChatModel model =
        OpenRouterChatModel.builder()
            .openRouterApi(api)
            .defaultOptions(applicationChatProperties().toOptions())
            .observationRegistry(registry)
            .objectMapper(new ObjectMapper())
            .build();
    model.setObservationConvention(new GarageObservationConvention());
    GarageRunPlan command =
        GarageRunRequests.plan(
            "{\"scenes\":[\"" + sceneId + "\"],\"models\":{\"foreman\":\"garage/model\"}}",
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
