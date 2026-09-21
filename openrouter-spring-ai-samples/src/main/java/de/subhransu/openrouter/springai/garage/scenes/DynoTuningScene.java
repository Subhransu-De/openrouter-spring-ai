package de.subhransu.openrouter.springai.garage.scenes;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.autoconfigure.OpenRouterChatProperties;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterChatRequestMapper;
import de.subhransu.openrouter.springai.chat.mapper.OpenRouterResponsesRequestMapper;
import de.subhransu.openrouter.springai.garage.evidence.EvidenceLevel;
import de.subhransu.openrouter.springai.garage.evidence.GarageFeature;
import de.subhransu.openrouter.springai.garage.evidence.GarageToolCallback;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/** Offline wire-contract scene for every standard and OpenRouter sampling option. */
@Component
public final class DynoTuningScene extends GarageSceneSupport {

  public DynoTuningScene() {
    super(
        "dyno-tuning",
        "Dyno tuning",
        "Serializes all sampling, metadata, user, context, and merge controls without a paid call.",
        true);
  }

  @Override
  public SceneResult execute(SceneContext context) {
    Instant started = Instant.now();
    String mode = context.requestMode().name();
    String operationId = context.evidence().newOperation(id(), mode);
    ToolCallback contextTool =
        GarageToolCallback.wrap(
            contextTool(),
            context.evidence(),
            operationId,
            id(),
            mode,
            GarageFeature.TOOL_CONTEXT_MERGE);
    OpenRouterChatOptions options =
        context.optionsFactory()
            .dynoTuning(
                operationId,
                context.requestMode(),
                context.command().foremanModel(),
                context.command().topic(),
                List.of(contextTool));
    Map<String, Object> snapshot = context.optionsFactory().snapshot(options);
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.CONFIGURED, "effectiveOptions", snapshot);

    Object wireRequest;
    if (context.requestMode() == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) {
      wireRequest =
          new OpenRouterChatRequestMapper(context.objectMapper())
              .map(
                  List.of(new UserMessage("Dyno option contract")),
                  options,
                  false,
                  List.of(contextTool.getToolDefinition()));
    } else {
      wireRequest =
          new OpenRouterResponsesRequestMapper(context.objectMapper())
              .map(
                  List.of(new UserMessage("Dyno option contract")),
                  options,
                  false,
                  List.of(contextTool.getToolDefinition()));
    }

    String contextResult =
        contextTool.call("{}", new ToolContext(Objects.requireNonNull(options.getToolContext(), "Configured tool context")));
    OpenRouterChatOptions defaults =
        OpenRouterChatOptions.builder()
            .model("garage/default-model")
            .metadata(Map.of("defaultOnly", true))
            .toolCallbacks(simpleTool("default_tool"))
            .toolContext("defaultOnly", "retained")
            .build();
    OpenRouterChatOptions merged = defaults.merge(options);
    OpenRouterChatOptions coverageProfile = coverageProfileOptions();
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("effectiveOptions", snapshot);
    details.put("wireRequest", wireRequest);
    details.put("unsupportedInMode", context.optionsFactory().unsupportedInMode(options));
    details.put("toolContextResult", contextResult);
    details.put("mergedOptions", context.optionsFactory().snapshot(merged));
    details.put("coverageProfileOptions", context.optionsFactory().snapshot(coverageProfile));
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.EXECUTED, "wireContract", wireRequest);
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.OBSERVED, "contractOutcome", details);

    List<String> failures =
        assertions(options, merged, coverageProfile, wireRequest, contextResult);
    if (!failures.isEmpty()) {
      IllegalStateException failure = new IllegalStateException(String.join("; ", failures));
      features().forEach(feature -> context.evidence().error(feature, operationId, mode, failure));
      throw failure;
    }
    context.evidence().recordAll(
        features(), operationId, mode, EvidenceLevel.ASSERTED, "assertions", "passed");
    return SceneResult.passed(
        id(),
        operationId,
        context.requestMode(),
        Duration.between(started, Instant.now()),
        context.outputDirectory(),
        details);
  }

  private List<String> assertions(
      OpenRouterChatOptions options,
      OpenRouterChatOptions merged,
      OpenRouterChatOptions coverageProfile,
      Object wireRequest,
      String contextResult) {
    List<String> failures = new ArrayList<>();
    if (options.getTopP() == null
        || options.getTopK() == null
        || options.getMaxTokens() == null
        || options.getPresencePenalty() == null
        || options.getFrequencyPenalty() == null) {
      failures.add("one or more sampler controls were absent");
    }
    if (options.getUser() == null || options.getMetadata() == null) {
      failures.add("user or metadata was absent");
    }
    if (coverageProfile.getModels() == null
        || coverageProfile.getProvider() == null
        || coverageProfile.getProvider().dataCollection() == null
        || coverageProfile.getProvider().order() == null
        || coverageProfile.getProvider().ignore() == null
        || coverageProfile.getProvider().quantizations() == null
        || coverageProfile.getReasoning() == null
        || coverageProfile.getRoute() == null) {
      failures.add("application-coverage.yml did not bind the complete chat property surface");
    }
    if (!contextResult.contains("sample-shop") || !contextResult.contains("garage.jobId")) {
      failures.add("tool context did not reach the callback");
    }
    if (merged.getToolCallbacks() == null || merged.getToolCallbacks().size() != 1
        || !"garage_context".equals(merged.getToolCallbacks().get(0).getToolDefinition().name())
        || merged.getToolContext() == null || !merged.getToolContext().containsKey("defaultOnly")
        || !merged.getToolContext().containsKey("garage.jobId")) {
      failures.add("runtime callback replacement or tool-context merge semantics changed");
    }
    if (wireRequest instanceof ChatCompletionRequest request) {
      if (request.stop() == null
          || request.seed() == null
          || request.repetitionPenalty() == null
          || request.minP() == null
          || request.topA() == null
          || request.user() == null
          || request.metadata() == null) {
        failures.add("Chat Completions wire request dropped options");
      }
    } else if (wireRequest instanceof ResponsesRequest request) {
      if (request.topP() == null
          || request.topK() == null
          || request.user() == null
          || request.metadata() == null) {
        failures.add("Responses wire request dropped supported options");
      }
    }
    return failures;
  }

  private OpenRouterChatOptions coverageProfileOptions() {
    try {
      List<org.springframework.core.env.PropertySource<?>> sources =
          new YamlPropertySourceLoader()
              .load("garage-coverage", new ClassPathResource("application-coverage.yml"));
      OpenRouterChatProperties properties =
          new Binder(ConfigurationPropertySources.from(sources))
              .bindOrCreate(OpenRouterChatProperties.CONFIG_PREFIX, OpenRouterChatProperties.class);
      return properties.toOptions();
    } catch (Exception failure) {
      throw new IllegalStateException("Failed to bind application-coverage.yml", failure);
    }
  }

  private ToolCallback contextTool() {
    return new ToolCallback() {
      private final ToolDefinition definition =
          ToolDefinition.builder()
              .name("garage_context")
              .description("Return the non-model Garage tool context keys.")
              .inputSchema("{\"type\":\"object\",\"properties\":{}}")
              .build();

      @Override
      public ToolDefinition getToolDefinition() {
        return this.definition;
      }

      @Override
      public String call(String input) {
        return "no-context";
      }

      @Override
      public String call(String input, @Nullable ToolContext toolContext) {
        return toolContext != null ? toolContext.getContext().toString() : call(input);
      }
    };
  }

  private ToolCallback simpleTool(String name) {
    return new ToolCallback() {
      private final ToolDefinition definition =
          ToolDefinition.builder()
              .name(name)
              .description("Default callback used to prove replacement semantics.")
              .inputSchema("{\"type\":\"object\",\"properties\":{}}")
              .build();

      @Override
      public ToolDefinition getToolDefinition() {
        return this.definition;
      }

      @Override
      public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
      }

      @Override
      public String call(String input) {
        return "default";
      }
    };
  }
}
