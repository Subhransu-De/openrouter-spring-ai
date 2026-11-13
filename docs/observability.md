# Observability

Observations, usage metadata and logging for chat, embedding and image calls.

The starter includes Spring AI's standard observation auto-configuration for chat, embedding, and image models. OpenRouter model calls emit `gen_ai.client.operation` observations with the `openrouter` provider, operation, request model, response model where available, and token-usage context. When a `MeterRegistry` is present, chat and embedding usage is also recorded as `gen_ai.client.token.usage`.

Read usage with `OpenRouterUsage.from(...)`, which takes a `ChatResponse`, an `EmbeddingResponse` or an `ImageResponse` and never returns `null`. When the provider sent no usage, its token counts are `0` and its other values are `null`. Chat and embedding responses also return it from `getMetadata().getUsage()`, and image responses keep it in metadata under `OpenRouterMetadata.IMAGE_USAGE` (`openrouter.usage`). A usage of another type, such as the one Spring AI's `MessageAggregator` builds, keeps its token counts and cached reads. `OpenRouterUsage` retains provider cost, its breakdown, cached and reasoning token counts, and the native usage DTO. Cached reads and cache writes are also available through Spring AI's portable `Usage.getCacheReadInputTokens()` and `Usage.getCacheWriteInputTokens()`, from `cache_write_tokens` in both request modes. Streaming chat model observations retain the latest provider usage snapshot in both request modes, including cost and detailed counts. Completed streaming observations also keep the response metadata and aggregate each choice separately, with its index and finish reason. Like Spring AI's `MessageAggregator`, they do not keep generated images or audio; read media from the streamed responses. Aggregation is skipped when the observation registry is a no-op.

```java
ChatResponse response = chatModel.call(prompt);
OpenRouterUsage usage = OpenRouterUsage.from(response); // never null
Double cost = usage.getCost();                // null when not reported
Integer cached = usage.getCachedTokens();     // null when not reported
Integer reasoning = usage.getReasoningTokens();
OpenRouterCostDetails details = usage.getCostDetails(); // never null
Double prompt = details.upstreamInferencePromptCost();          // null when not reported
Double completion = details.upstreamInferenceCompletionsCost(); // null when not reported

Double imageCost = OpenRouterUsage.from(imageModel.call(imagePrompt)).getCost();
```

`getCost()` is the total that OpenRouter charged in credits. `getCostDetails()` holds what the upstream provider charged (the total, the prompt or input part, and the completion or output part) and the cost of metered OpenRouter server tools. Chat Completions and Responses use different wire names for the prompt and completion parts; both map to the same fields. `getCostDetails()` never returns `null`; when OpenRouter sends no breakdown, each of its values is `null`. `getByok()` is `true` when the request used your own provider key. OpenRouter does not price reasoning separately: reasoning tokens are billed as completion tokens, so their cost is part of the completion cost. Use `getReasoningTokens()` for the count.

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
