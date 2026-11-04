# Observability

Observations, usage metadata and logging for chat, embedding and image calls.

The starter includes Spring AI's standard observation auto-configuration for chat, embedding, and image models. OpenRouter model calls emit `gen_ai.client.operation` observations with the `openrouter` provider, operation, request model, response model where available, and token-usage context. When a `MeterRegistry` is present, chat and embedding usage is also recorded as `gen_ai.client.token.usage`.

Chat and embedding response usage is available as `OpenRouterUsage`; image responses expose it in metadata under `openrouter.usage`. It retains provider cost, cached and reasoning token counts, and the native usage DTO. Cached reads and cache writes are also available through Spring AI's portable `Usage.getCacheReadInputTokens()` and `Usage.getCacheWriteInputTokens()`, from `cache_write_tokens` in both request modes. Streaming chat model observations retain the latest provider usage snapshot in both request modes, including cost and detailed counts. Completed streaming observations also keep the response metadata and aggregate each choice separately, with its index and finish reason. Aggregation is skipped when the observation registry is a no-op.

```java
ChatResponse response = chatModel.call(prompt);
if (response.getMetadata().getUsage() instanceof OpenRouterUsage usage) {
    Double cost = usage.getCost();                // null when not reported
    Integer cached = usage.getCachedTokens();     // null when not reported
    Integer reasoning = usage.getReasoningTokens();
}
```

Missing cost and detail counts, including cache writes, are `null`, which differs from an explicit zero. To have OpenRouter report usage, see [`include-usage`](getting-started.md#main-properties).

Chat calls and streams record option-conversion, request-mapping and transport errors on their observation. Image streams record request-mapping and transport errors. Each stream subscription stops its observation on completion, error, or cancellation.

The starter provides the Spring AI handlers, but it does not choose monitoring backends for the application:

- Metrics require a `MeterRegistry`. Spring Boot Actuator supplies the observation wiring and a registry; add an exporter for your monitoring system.
- Traces require a Micrometer `Tracer` bridge and tracing exporter.
- Prompt, completion, image-prompt and error logging are opt-in through Spring AI's standard properties. Error logging requires a `Tracer`.

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
