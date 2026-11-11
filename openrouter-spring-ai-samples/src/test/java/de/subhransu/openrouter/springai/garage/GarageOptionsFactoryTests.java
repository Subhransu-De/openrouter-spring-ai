package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.ObjectMapper;

class GarageOptionsFactoryTests {

  private final GarageOptionsFactory factory =
      new GarageOptionsFactory(new GarageProperties());

  /** Maps options onto the wire body of one request mode, through that mode's own mapper. */
  enum Wire {

    CHAT(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
      @Override
      Object map(ObjectMapper mapper, OpenRouterChatOptions options, boolean stream, ToolCallback callback) {
        return new OpenRouterChatRequestMapper(mapper).map(List.of(), options, stream,
            List.of(callback.getToolDefinition()));
      }
    },

    RESPONSES(OpenRouterRequestMode.OPENAI_RESPONSES) {
      @Override
      Object map(ObjectMapper mapper, OpenRouterChatOptions options, boolean stream, ToolCallback callback) {
        return new OpenRouterResponsesRequestMapper(mapper).map(List.of(), options, stream,
            List.of(callback.getToolDefinition()));
      }
    };

    private final OpenRouterRequestMode mode;

    Wire(OpenRouterRequestMode mode) {
      this.mode = mode;
    }

    abstract Object map(ObjectMapper mapper, OpenRouterChatOptions options, boolean stream, ToolCallback callback);

  }

  /** The scene profiles that send tools, built for one mode and one tool. */
  enum Profile {

    SERVICE_STORY {
      @Override
      OpenRouterChatOptions build(GarageOptionsFactory factory, OpenRouterRequestMode mode, ToolCallback tool) {
        return factory.serviceStory("synthetic", mode, "garage/model", List.of(), "synthetic inspection",
            List.of(tool));
      }
    },

    STREAMING_DISPATCH {
      @Override
      OpenRouterChatOptions build(GarageOptionsFactory factory, OpenRouterRequestMode mode, ToolCallback tool) {
        return factory.streamingDispatch("synthetic", mode, "garage/model", "synthetic inspection", tool);
      }
    },

    EXPRESS_INVOICE {
      @Override
      OpenRouterChatOptions build(GarageOptionsFactory factory, OpenRouterRequestMode mode, ToolCallback tool) {
        return factory.expressInvoice("synthetic", mode, "garage/model", "synthetic inspection", tool);
      }
    };

    abstract OpenRouterChatOptions build(GarageOptionsFactory factory, OpenRouterRequestMode mode,
        ToolCallback tool);

  }

  static Stream<Arguments> strictToolRequests() {
    return Stream.of(Wire.values()).flatMap(wire -> Stream.of(Profile.values())
        .flatMap(profile -> Stream.of(false, true).map(stream -> Arguments.of(
            Named.of(wire + " " + profile + " stream=" + stream, wire), profile, stream))));
  }

  @ParameterizedTest
  @MethodSource("strictToolRequests")
  void strictToolRequestsDoNotRequireParallelToolSupport(Wire wire, Profile profile, boolean stream) {
    GarageProperties properties = new GarageProperties();
    properties.setProviderRequireParameters(true);
    GarageOptionsFactory optionsFactory = new GarageOptionsFactory(properties);
    ToolCallback callback = tool();
    var mapper = new ObjectMapper();
    OpenRouterChatOptions options = profile.build(optionsFactory, wire.mode, callback);

    var request = wire.map(mapper, options, stream, callback);

    var body = mapper.readTree(mapper.writeValueAsString(request));
    // Strict routing treats even false as requiring support for this parameter.
    assertThat(body.has("parallel_tool_calls")).isFalse();
    assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
    assertThat(body.path("tools").size()).isEqualTo(1);
    assertThat(body.has("tool_choice")).isTrue();
  }

  static Stream<Arguments> dynoProfiles() {
    Map<String, Object> chatOnly = new HashMap<>();
    chatOnly.put("stop", List.of("GARAGE_END"));
    chatOnly.put("seed", 1972);
    chatOnly.put("repetitionPenalty", 1.05);
    chatOnly.put("minP", 0.05);
    chatOnly.put("topA", 0.1);
    Map<String, Object> responsesOmits = new HashMap<>();
    for (String key : chatOnly.keySet()) {
      responsesOmits.put(key, null);
    }
    return Stream.of(
        Arguments.of(Named.of("chat", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS), chatOnly),
        Arguments.of(Named.of("responses", OpenRouterRequestMode.OPENAI_RESPONSES), responsesOmits));
  }

  @ParameterizedTest
  @MethodSource("dynoProfiles")
  void dynoProfileSetsTheSharedSamplersAndOnlyTheSamplersTheModeSupports(OpenRouterRequestMode mode,
      Map<String, Object> modeSpecificSamplers) {
    OpenRouterChatOptions options =
        this.factory.dynoTuning("op-1", mode, "garage/model", "topic", List.of(tool()));

    Map<String, Object> snapshot = this.factory.snapshot(options);
    assertThat(snapshot).containsEntry("topP", 0.85).containsEntry("topK", 40)
        .containsEntry("maxTokens", 700).containsEntry("presencePenalty", 0.1)
        .containsEntry("frequencyPenalty", 0.1).containsEntry("user", "garage-demo");
    assertThat(snapshot).containsAllEntriesOf(modeSpecificSamplers);
    assertThat(options.getMetadata())
        .containsEntry("operationId", "op-1")
        .containsEntry("sceneId", "dyno-tuning");
    assertThat(options.getToolContext())
        .containsEntry("garage.jobId", "op-1")
        .containsEntry("garage.tenant", "sample-shop");
    // A mode never receives an option it cannot send.
    assertThat(this.factory.unsupportedInMode(options)).isEmpty();
  }

  @Test
  void routingProfileCarriesTheFullProviderPreferencesTheRouteAndANonAutoTier() {
    OpenRouterChatOptions options =
        this.factory.routingLane(
            "op-2",
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            "garage/primary",
            List.of("garage/fallback"),
            "topic");

    var provider = options.getProvider();
    assertThat(provider.allowFallbacks()).isTrue();
    assertThat(provider.requireParameters()).isFalse();
    assertThat(provider.dataCollection()).isEqualTo("allow");
    assertThat(provider.order()).containsExactly("OpenAI");
    assertThat(provider.ignore()).containsExactly("Fireworks");
    assertThat(provider.quantizations()).containsExactly("bf16", "fp16", "fp8");
    assertThat(provider.sort()).isEqualTo("throughput");
    assertThat(options.getModels()).containsExactly("garage/fallback");
    assertThat(options.getRoute()).isEqualTo("fallback");
    assertThat(options.getServiceTier().value()).isEqualTo("default");
  }

  @Test
  void reasoningTokenBudgetReplacesTheDefaultEffort() {
    GarageProperties properties = new GarageProperties();
    properties.setReasoningMaxTokens(512);
    GarageOptionsFactory tokenBudgetFactory = new GarageOptionsFactory(properties);

    OpenRouterChatOptions options =
        tokenBudgetFactory.plain(
            "op-4",
            "reasoning-budget",
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            "garage/model",
            "topic");

    assertThat(options.getReasoning().effort()).isNull();
    assertThat(options.getReasoning().maxTokens()).isEqualTo(512);
  }

  private static final String PROBE_SCHEMA = "{\"type\":\"object\"}";

  static Stream<Arguments> routingModes() {
    return Stream.of(
        Arguments.of(Named.of("routing on", true), "throughput"),
        Arguments.of(Named.of("routing off", false), null));
  }

  @ParameterizedTest
  @MethodSource("routingModes")
  void aResponseFormatProbeRequiresParameterSupportEvenWhenGeneralRoutingIsDisabled(
      boolean routingEnabled, String expectedSort) {
    OpenRouterChatOptions options = digitalInspection(routingEnabled, false);

    assertThat(options.getOutputSchema()).isNull();
    assertThat(options.getResponseFormat()).isNotNull();
    assertProviderPreferences(options, expectedSort);
  }

  @ParameterizedTest
  @MethodSource("routingModes")
  void anOutputSchemaProbeRequiresParameterSupportEvenWhenGeneralRoutingIsDisabled(
      boolean routingEnabled, String expectedSort) {
    OpenRouterChatOptions options = digitalInspection(routingEnabled, true);

    assertThat(options.getOutputSchema()).isEqualTo(PROBE_SCHEMA);
    assertThat(options.getResponseFormat()).isNull();
    assertProviderPreferences(options, expectedSort);
  }

  private static OpenRouterChatOptions digitalInspection(boolean routingEnabled, boolean outputSchema) {
    GarageProperties properties = new GarageProperties();
    properties.setProviderPreferencesEnabled(routingEnabled);
    return new GarageOptionsFactory(properties).digitalInspection(
        "synthetic-schema", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
        "garage/model", "synthetic report", PROBE_SCHEMA, outputSchema);
  }

  private static void assertProviderPreferences(OpenRouterChatOptions options, String expectedSort) {
    var request = new de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper(
        new tools.jackson.databind.ObjectMapper()).map(List.of(), options, false, List.of());
    assertThat(request.provider().requireParameters()).isTrue();
    assertThat(request.provider().sort()).isEqualTo(expectedSort);
  }

  @Test
  void specialistDelegationRoutesWithTheSharedProviderPreferences() {
    GarageProperties properties = new GarageProperties();
    properties.setProviderRequireParameters(true);
    properties.setProviderSort("price");
    ChatModel chatModel = mock(ChatModel.class);
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of()));
    GarageTools tools =
        new GarageTools(
            chatModel,
            properties,
            Path.of("target"),
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            "op-specialist",
            "service-story",
            "garage/specialist");

    tools.handToSpecialist("inspect the brake wear");

    ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(prompt.capture());
    OpenRouterChatOptions options = (OpenRouterChatOptions) prompt.getValue().getOptions();
    assertThat(options.getProvider()).isNotNull();
    assertThat(options.getProvider().requireParameters()).isTrue();
    assertThat(options.getProvider().sort()).isEqualTo("price");
    // Without this a reasoning specialist falls back to its own default effort and can
    // spend the whole completion budget thinking.
    assertThat(options.getReasoning()).isNotNull();
    assertThat(options.getReasoning().effort()).isEqualTo(properties.getReasoningEffort());
  }

  @Test
  void aPinnedSweepProviderKeepsRunStrictnessAndForbidsFallbacks() {
    GarageProperties properties = new GarageProperties();
    properties.setProviderRequireParameters(true);
    properties.setProviderSort("price");
    var shared = GarageOptionsFactory.serviceProviderPreferences(properties);

    var pinned = GarageOptionsFactory.pinnedProviderPreferences(shared, "OpenAI");

    assertThat(pinned.order()).containsExactly("OpenAI");
    assertThat(pinned.allowFallbacks()).isFalse();
    assertThat(pinned.requireParameters()).isTrue();
    assertThat(pinned.sort()).isEqualTo("price");
    assertThat(GarageOptionsFactory.pinnedProviderPreferences(shared, null)).isSameAs(shared);
  }

  @Test
  void imageProviderOptionsUseTheWireNamesAndDropEmptyPreferences() {
    GarageProperties properties = new GarageProperties();
    properties.setProviderRequireParameters(true);
    properties.setProviderSort("price");
    var shared = GarageOptionsFactory.serviceProviderPreferences(properties);

    assertThat(GarageOptionsFactory.imageProviderOptions(shared, null))
        .containsEntry("require_parameters", true)
        .containsEntry("sort", "price")
        .doesNotContainKey("order");
    assertThat(GarageOptionsFactory.imageProviderOptions(shared, "OpenAI"))
        .containsEntry("order", List.of("OpenAI"))
        .containsEntry("allow_fallbacks", false)
        .containsEntry("require_parameters", true);
    assertThat(GarageOptionsFactory.imageProviderOptions(null, null)).isNull();
    // With provider preferences disabled there is nothing shared to send.
    properties.setProviderPreferencesEnabled(false);
    assertThat(GarageOptionsFactory.serviceProviderPreferences(properties)).isNull();
  }

  private ToolCallback tool() {
    return new ToolCallback() {
      private final ToolDefinition definition =
          ToolDefinition.builder()
              .name("test_tool")
              .description("test")
              .inputSchema("{\"type\":\"object\",\"properties\":{}}")
              .build();

      @Override
      public ToolDefinition getToolDefinition() {
        return this.definition;
      }

      @Override
      public String call(String input) {
        return "ok";
      }
    };
  }
}
