# Observability

Observations, usage metadata and logging for chat, embedding and image calls.

The starter includes Spring AI's standard observation auto-configuration for chat, embedding, and image models. OpenRouter model calls emit `gen_ai.client.operation` observations with the `openrouter` provider, operation, request model, response model where available, and token-usage context. When a `MeterRegistry` is present, chat and embedding usage is also recorded as `gen_ai.client.token.usage`.

Chat and embedding response usage is available as `OpenRouterUsage`; image responses expose it in metadata under `openrouter.usage`. It retains provider cost, cached and reasoning token counts, and the native usage DTO. Cached tokens are also available through Spring AI's portable `Usage.getCacheReadInputTokens()`. Streaming chat model observations retain the latest provider usage snapshot in both request modes, including cost and detailed counts.

```java
ChatResponse response = chatModel.call(prompt);
if (response.getMetadata().getUsage() instanceof OpenRouterUsage usage) {
    Double cost = usage.getCost();                // null when not reported
    Integer cached = usage.getCachedTokens();     // null when not reported
    Integer reasoning = usage.getReasoningTokens();
}
```

Missing cost and detail counts are `null`, which differs from an explicit zero. Unavailable cache-write counts are also `null`. To ask OpenRouter for usage on Chat Completions requests, set [`include-usage`](getting-started.md#main-properties). Responses requests reject that option.

Chat and image streams record request-mapping and transport errors on their observation. Each subscription stops its observation on completion, error, or cancellation.

The starter provides the Spring AI handlers, but it does not choose monitoring backends for the application:

- Metrics require a `MeterRegistry`. Spring Boot Actuator supplies the Boot observation wiring and a registry; add the registry/exporter for your monitoring system to publish them externally.
- Traces require a Micrometer `Tracer` bridge and tracing exporter.
- Prompt, completion, image-prompt, and error logging are opt-in through Spring AI's standard properties. Content logging does not require an exporter; when a `Tracer` is present its handlers become tracing-aware, and error logging requires that tracer.

```yaml
# All four default to false. Logged prompts and completions may contain sensitive data.
spring:
  ai:
    chat:
      observations:
        log-prompt: true
        log-completion: true
        include-error-logging: true
    image:
      observations:
        log-prompt: true
```

Applications may supply their own model observation handlers or `ChatModelObservationConvention`, `EmbeddingModelObservationConvention`, and `ImageModelObservationConvention` beans. Boot backs off a standard handler when the application supplies the same handler type, and the OpenRouter models use the custom conventions.
