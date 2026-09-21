package de.subhransu.openrouter.springai.garage.evidence;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/** Records every tool attempt, including argument conversion failures before tool entry. */
public final class GarageToolCallback implements ToolCallback {

  private final ToolCallback delegate;
  private final GarageEvidence evidence;
  private final String operationId;
  private final String sceneId;
  private final String requestMode;
  private final List<GarageFeature> features;

  private GarageToolCallback(
      ToolCallback delegate,
      GarageEvidence evidence,
      String operationId,
      String sceneId,
      String requestMode,
      List<GarageFeature> features) {
    this.delegate = delegate;
    this.evidence = evidence;
    this.operationId = operationId;
    this.sceneId = sceneId;
    this.requestMode = requestMode;
    this.features = features;
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("tool", delegate.getToolDefinition().name());
    schema.put("inputSchema", delegate.getToolDefinition().inputSchema());
    schema.put("returnDirect", delegate.getToolMetadata().returnDirect());
    evidence.event(operationId, sceneId, "tool.schema", schema);
    evidence.recordAll(
        features,
        operationId,
        requestMode,
        EvidenceLevel.CONFIGURED,
        "toolSchema." + delegate.getToolDefinition().name(),
        schema);
  }

  public static ToolCallback wrap(
      ToolCallback delegate,
      GarageEvidence evidence,
      String operationId,
      String sceneId,
      String requestMode,
      GarageFeature... features) {
    return new GarageToolCallback(
        delegate,
        evidence,
        operationId,
        sceneId,
        requestMode,
        List.of(features));
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return this.delegate.getToolDefinition();
  }

  @Override
  public ToolMetadata getToolMetadata() {
    return this.delegate.getToolMetadata();
  }

  @Override
  public String call(String toolInput) {
    return invoke(toolInput, null);
  }

  @Override
  public String call(String toolInput, @Nullable ToolContext toolContext) {
    return invoke(toolInput, toolContext);
  }

  private String invoke(String toolInput, @Nullable ToolContext toolContext) {
    String tool = getToolDefinition().name();
    Map<String, Object> attempt = new LinkedHashMap<>();
    attempt.put("tool", tool);
    attempt.put("at", Instant.now().toString());
    attempt.put("input", toolInput);
    attempt.put("toolContext", toolContext != null ? toolContext.getContext() : Map.of());
    this.evidence.event(this.operationId, this.sceneId, "tool.attempted", attempt);
    try {
      String result =
          toolContext != null
              ? this.delegate.call(toolInput, toolContext)
              : this.delegate.call(toolInput);
      attempt.put("status", "success");
      attempt.put("resultCharacters", result != null ? result.length() : 0);
      this.evidence.event(this.operationId, this.sceneId, "tool.succeeded", attempt);
      this.evidence.recordAll(
          this.features,
          this.operationId,
          this.requestMode,
          EvidenceLevel.EXECUTED,
          "tool." + tool,
          "success");
      return result;
    } catch (RuntimeException failure) {
      attempt.put("status", "error");
      attempt.put("errorType", failure.getClass().getName());
      attempt.put("errorMessage", failure.getMessage());
      this.evidence.event(this.operationId, this.sceneId, "tool.failed", attempt);
      this.features.forEach(
          feature -> this.evidence.error(feature, this.operationId, this.requestMode, failure));
      throw failure;
    }
  }
}
