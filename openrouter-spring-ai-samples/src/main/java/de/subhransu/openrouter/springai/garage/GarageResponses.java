package de.subhransu.openrouter.springai.garage;

import de.subhransu.openrouter.springai.chat.OpenRouterUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

public final class GarageResponses {

  private GarageResponses() {}

  public static String text(ChatResponse response) {
    if (response == null || response.getResult() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text != null ? text : "";
  }

  public static String finishReason(ChatResponse response) {
    Generation result = response != null ? response.getResult() : null;
    if (result == null || result.getMetadata() == null) {
      return "";
    }
    String finishReason = result.getMetadata().getFinishReason();
    return finishReason != null ? finishReason : "";
  }

  public static String reasoning(ChatResponse response) {
    Generation result = response != null ? response.getResult() : null;
    if (result == null || result.getMetadata() == null) {
      return "";
    }
    Object reasoning = result.getMetadata().get("openrouter.reasoning");
    return reasoning != null ? reasoning.toString() : "";
  }

  public static Map<String, Object> reasoningEvidence(
      ChatResponse response, String phase, int round) {
    String reasoning = reasoning(response);
    Integer reasoningTokens =
        response != null && response.getMetadata().getUsage() instanceof OpenRouterUsage usage
            ? usage.getReasoningTokens()
            : null;
    int toolCallCount =
        response != null && response.getResult() != null
            ? response.getResult().getOutput().getToolCalls().size()
            : 0;
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("phase", phase);
    evidence.put("round", round);
    evidence.put("reasoningCharacters", reasoning.length());
    evidence.put("reasoningTokens", reasoningTokens);
    evidence.put("usageReported", reasoningTokens != null);
    evidence.put("toolCallCount", toolCallCount);
    return evidence;
  }

  public static boolean reasoningObserved(List<Map<String, Object>> rounds) {
    return rounds.stream()
        .anyMatch(
            round ->
                ((Number) round.getOrDefault("reasoningCharacters", 0)).intValue() > 0
                    || (round.get("reasoningTokens") instanceof Number tokens
                        && tokens.intValue() > 0));
  }

  public static Map<String, Object> metadata(ChatResponse response) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (response == null) {
      return metadata;
    }
    ChatResponseMetadata responseMetadata = response.getMetadata();
    metadata.put("id", responseMetadata.getId());
    metadata.put("model", responseMetadata.getModel());
    metadata.put("usage", usage(responseMetadata.getUsage()));
    responseMetadata
        .entrySet()
        .forEach(entry -> metadata.put("metadata." + entry.getKey(), entry.getValue()));
    return metadata;
  }

  public static Map<String, Object> generationMetadata(ChatResponse response) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    Generation result = response != null ? response.getResult() : null;
    if (result == null || result.getMetadata() == null) {
      return metadata;
    }
    metadata.put("finishReason", finishReason(response));
    metadata.put(
        "openrouter.native_finish_reason",
        result.getMetadata().get("openrouter.native_finish_reason"));
    metadata.put("openrouter.model", result.getMetadata().get("openrouter.model"));
    String reasoning = reasoning(response);
    if (!reasoning.isEmpty()) {
      metadata.put("openrouter.reasoning.characters", reasoning.length());
    }
    return metadata;
  }

  public static Map<String, Object> usage(Usage usage) {
    Map<String, Object> values = new LinkedHashMap<>();
    if (usage == null) {
      return values;
    }
    values.put("promptTokens", usage.getPromptTokens());
    values.put("completionTokens", usage.getCompletionTokens());
    values.put("totalTokens", usage.getTotalTokens());
    values.put("nativeUsage", usage.getNativeUsage());
    if (usage instanceof de.subhransu.openrouter.springai.chat.OpenRouterUsage openRouterUsage) {
      values.put("cachedTokens", openRouterUsage.getCachedTokens());
      values.put("reasoningTokens", openRouterUsage.getReasoningTokens());
      values.put("cost", openRouterUsage.getCost());
    }
    return values;
  }
}
