package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class GarageOptionsFactoryTests {

  private final GarageOptionsFactory factory =
      new GarageOptionsFactory(new GarageProperties());

  @Test
  void dynoProfilePopulatesEverySamplerAndContextField() {
    OpenRouterChatOptions options =
        this.factory.dynoTuning(
            "op-1",
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            "garage/model",
            "topic",
            List.of(tool()));

    assertThat(options.getTopP()).isNotNull();
    assertThat(options.getTopK()).isNotNull();
    assertThat(options.getMaxTokens()).isNotNull();
    assertThat(options.getStopSequences()).isNotEmpty();
    assertThat(options.getSeed()).isNotNull();
    assertThat(options.getPresencePenalty()).isNotNull();
    assertThat(options.getFrequencyPenalty()).isNotNull();
    assertThat(options.getRepetitionPenalty()).isNotNull();
    assertThat(options.getMinP()).isNotNull();
    assertThat(options.getTopA()).isNotNull();
    assertThat(options.getUser()).isEqualTo("garage-demo");
    assertThat(options.getMetadata())
        .containsEntry("operationId", "op-1")
        .containsEntry("sceneId", "dyno-tuning");
    assertThat(options.getToolContext())
        .containsEntry("garage.jobId", "op-1")
        .containsEntry("garage.tenant", "sample-shop");
  }

  @Test
  void routingProfileIncludesEveryProviderFieldRouteAndNonAutoTier() {
    OpenRouterChatOptions options =
        this.factory.routingLane(
            "op-2",
            OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
            "garage/primary",
            List.of("garage/fallback"),
            "topic");
    Map<String, Object> snapshot = this.factory.snapshot(options);

    @SuppressWarnings("unchecked")
    Map<String, Object> provider = (Map<String, Object>) snapshot.get("provider");
    assertThat(provider.values()).doesNotContainNull();
    assertThat(options.getModels()).containsExactly("garage/fallback");
    assertThat(options.getRoute()).isEqualTo("fallback");
    assertThat(options.getServiceTier().value()).isEqualTo("default");
  }

  @Test
  void responsesDynoProfileContainsOnlySupportedOptions() {
    OpenRouterChatOptions options =
        this.factory.dynoTuning(
            "op-3",
            OpenRouterRequestMode.OPENAI_RESPONSES,
            "garage/model",
            "topic",
            List.of(tool()));

    assertThat(this.factory.unsupportedInMode(options)).isEmpty();
    assertThat(options.getIncludeUsage()).isNull();
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
  void schemaProbeRequiresParameterSupportEvenWhenGeneralRoutingIsDisabled(boolean routingEnabled) {
    GarageProperties properties = new GarageProperties();
    properties.setProviderPreferencesEnabled(routingEnabled);
    GarageOptionsFactory optionsFactory = new GarageOptionsFactory(properties);
    for (boolean outputSchema : List.of(false, true)) {
      OpenRouterChatOptions options = optionsFactory.digitalInspection(
          "synthetic-schema", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS,
          "garage/model", "synthetic report", "{\"type\":\"object\"}", outputSchema);
      assertThat(options.getOutputSchema()).isEqualTo(outputSchema ? "{\"type\":\"object\"}" : null);
      if (outputSchema) {
        assertThat(options.getResponseFormat()).isNull();
      } else {
        assertThat(options.getResponseFormat()).isNotNull();
      }
      var request = new de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper(
          new tools.jackson.databind.ObjectMapper()).map(List.of(), options, false, List.of());
      assertThat(request.provider().requireParameters()).isTrue();
      assertThat(request.provider().sort()).isEqualTo(routingEnabled ? properties.getProviderSort() : null);
    }
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
  }

  @Test
  void disabledProviderPreferencesLeaveRequestsUnrouted() {
    GarageProperties properties = new GarageProperties();
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
