package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.util.Arrays;
import java.util.List;

/**
 * The single Garage capability registry. CLI discovery, scene assertions, JSON evidence,
 * Markdown reports, and documentation tests all consume this enum.
 */
// Keep the capability registry readable as literal rows.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public enum GarageFeature {
  STARTER_AUTOCONFIGURATION("starter-autoconfiguration", "Starter + Boot auto-configuration", "service-story", Kind.LIVE),
  SYNCHRONOUS_CHAT("synchronous-chat", "Synchronous chat", "service-story", Kind.LIVE),
  CHAT_COMPLETIONS_MODE("chat-completions-mode", "Chat Completions mode", "service-story", Kind.LIVE),
  RESPONSES_MODE("responses-mode", "Responses mode", "service-story", Kind.LIVE),
  PLAIN_STREAMING("plain-streaming", "Plain SSE streaming", "streaming-dispatch", Kind.LIVE),
  TOOL_LOOP("tool-loop", "Tool loop via ToolCallingAdvisor", "service-story", Kind.LIVE),
  MIXED_TOOL_SCHEMAS("mixed-tool-schemas", "Mixed tool schemas", "service-story", Kind.LIVE),
  STREAMING_TOOL_AGGREGATION("streaming-tool-aggregation", "Streamed tool-call fragment aggregation", "streaming-dispatch", Kind.LIVE),
  RETURN_DIRECT("return-direct", "returnDirect tools", "express-invoice", Kind.LIVE),
  MODEL_DELEGATION("model-delegation", "Model-to-model delegation", "service-story", Kind.LIVE),
  REASONING("reasoning", "Reasoning options + deltas", "service-story", Kind.LIVE),
  BASIC_USAGE("basic-usage", "Basic usage accounting", "service-story", Kind.LIVE),
  EXTENDED_USAGE("extended-usage", "Cost, cached & reasoning tokens", "service-story", Kind.LIVE),
  RESPONSE_METADATA("response-metadata", "Response metadata + finish reasons", "service-story", Kind.LIVE),
  STRUCTURED_OUTPUT("structured-output", "Structured output", "digital-inspection", Kind.LIVE),
  EMBEDDINGS("embeddings", "Embeddings API", "modality-bays", Kind.LIVE),
  IMAGE_INPUT("image-input", "Image input (vision)", "modality-bays", Kind.LIVE),
  IMAGE_GENERATION("image-generation", "Image generation (Image API + chat modalities)", "modality-bays", Kind.LIVE),
  MODEL_FALLBACK("model-fallback", "Model fallback list", "routing-lane", Kind.LIVE),
  PROVIDER_PREFERENCES("provider-preferences", "Provider preferences", "routing-lane", Kind.LIVE),
  ROUTE("route", "route directive", "routing-lane", Kind.LIVE),
  SERVICE_TIERS("service-tiers", "Service tiers", "routing-lane", Kind.LIVE),
  ATTRIBUTION_HEADERS("attribution-headers", "Attribution headers", "attribution-check-in", Kind.LIVE),
  REQUEST_METADATA_USER("request-metadata-user", "Request metadata + user", "dyno-tuning", Kind.CONTRACT),
  STANDARD_SAMPLING("standard-sampling", "Standard sampling & limits", "dyno-tuning", Kind.CONTRACT),
  OPENROUTER_SAMPLERS("openrouter-samplers", "OpenRouter sampler extras", "dyno-tuning", Kind.CONTRACT),
  TOOL_CONTEXT_MERGE("tool-context-merge", "Tool context + option merge behavior", "dyno-tuning", Kind.CONTRACT),
  CONNECTION_TIMEOUT("connection-timeout", "Connection timeout", "recovery-road-test", Kind.OFFLINE),
  SYNC_RETRY("sync-retry", "Sync retry", "recovery-road-test", Kind.OFFLINE),
  ERROR_SURFACING("error-surfacing", "API / stream error surfacing", "recovery-road-test", Kind.OFFLINE),
  OBSERVATION_SYNC("observation-sync", "Micrometer observation - sync", "service-story", Kind.LIVE),
  OBSERVATION_STREAM("observation-stream", "Micrometer observation - stream", "streaming-dispatch", Kind.LIVE),
  TELEMETRY_CORRELATION("telemetry-correlation", "Feature to telemetry correlation", "service-story", Kind.LIVE),
  FULL_PROPERTY_BINDING("full-property-binding", "Full property-binding surface", "dyno-tuning", Kind.CONTRACT),
  EXTENSION_POINTS("extension-points", "Base URL / bean back-off extension points", "recovery-road-test", Kind.OFFLINE);

  private final String id;
  private final String title;
  private final String sceneId;
  private final Kind kind;

  GarageFeature(String id, String title, String sceneId, Kind kind) {
    this.id = id;
    this.title = title;
    this.sceneId = sceneId;
    this.kind = kind;
  }

  public String id() {
    return this.id;
  }

  public String title() {
    return this.title;
  }

  public String sceneId() {
    return this.sceneId;
  }

  public Kind kind() {
    return this.kind;
  }

  public boolean supports(OpenRouterRequestMode mode) {
    if (this == RESPONSES_MODE) {
      return mode == OpenRouterRequestMode.OPENAI_RESPONSES;
    }
    return mode == OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS
        || (this != CHAT_COMPLETIONS_MODE && this != STRUCTURED_OUTPUT
            && this != STREAMING_TOOL_AGGREGATION && !this.sceneId.equals("recovery-road-test"));
  }

  public List<OpenRouterRequestMode> coverageModes(List<OpenRouterRequestMode> selected) {
    if (this == EMBEDDINGS || this == IMAGE_GENERATION) {
      return selected.contains(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS)
          ? List.of(OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS) : selected;
    }
    if (this == CHAT_COMPLETIONS_MODE || this == RESPONSES_MODE) {
      return selected.stream().filter(this::supports).toList();
    }
    return selected;
  }

  public static GarageFeature fromId(String id) {
    return Arrays.stream(values())
        .filter(feature -> feature.id.equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown Garage feature: " + id));
  }

  public static List<GarageFeature> forScene(String sceneId) {
    return Arrays.stream(values()).filter(feature -> feature.sceneId.equals(sceneId)).toList();
  }

  public enum Kind {
    LIVE,
    OFFLINE,
    CONTRACT
  }
}
