package de.subhransu.openrouter.springai.garage;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

public final class GarageResponses {

  private GarageResponses() {}

  public static String text(@Nullable ChatResponse response) {
    if (response == null || response.getResult() == null) {
      return "";
    }
    String text = response.getResult().getOutput().getText();
    return text != null ? text : "";
  }

  public static String finishReason(@Nullable ChatResponse response) {
    Generation result = response != null ? response.getResult() : null;
    if (result == null || result.getMetadata() == null) {
      return "";
    }
    String finishReason = result.getMetadata().getFinishReason();
    return finishReason != null ? finishReason : "";
  }

  public static String reasoning(@Nullable ChatResponse response) {
    Generation result = response != null ? response.getResult() : null;
    if (result == null || result.getMetadata() == null) {
      return "";
    }
    Object reasoning = result.getMetadata().get("openrouter.reasoning");
    return reasoning != null ? reasoning.toString() : "";
  }

  public static Map<String, Object> metadata(@Nullable ChatResponse response) {
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

  public static Map<String, Object> generationMetadata(@Nullable ChatResponse response) {
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

  public static Map<String, Object> usage(@Nullable Usage usage) {
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
