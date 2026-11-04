# Supported API and nullability

This page lists the types applications can depend on, and the null contracts they follow.

## Supported types

These types under `de.subhransu.openrouter.springai` are the supported API.

| API                                                                                                                        | Supported use                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `chat`, `embedding`, `image`                                                                                               | Models and their builders, options and option builders, routing/reasoning/format records, usage and generated-image metadata. Use the Spring AI model interfaces for calls and streams.                            |
| `OpenRouterIdentifiers`                                                                                                    | Provider identifiers used to select this integration.                                                                                                                                                              |
| `api.OpenRouterApi`, `api.OpenRouterRequestMode`                                                                           | Direct HTTP calls and client construction, including `RestClient.Builder`, `WebClient.Builder`, Jackson, attribution, timeouts, and response limits.                                                               |
| `api.dto`                                                                                                                  | Low-level request and response records used by `OpenRouterApi`. Build `ChatCompletionRequest` and `ResponsesRequest` with `builder()`; wire fields can be absent. Responses mode remains experimental.             |
| `chat.OpenRouterToolFailurePolicy`, `chat.OpenRouterToolCallingManagers`, `chat.OpenRouterToolExecutionExceptionProcessor` | Custom tool-manager failure policy, a factory for policy-declaring managers, and failure rendering. The policy accessor must return the actual, non-null processor. Tool execution belongs to Spring AI's advisor. |
| `errors`, `chat.errors`, `api.errors.OpenRouterApiException`                                                               | Exception types, diagnostic records, category enums, and inspection interfaces. Missing diagnostics are nullable. `OpenRouterHttpException` is sealed; consumers cannot implement it.                              |
| `autoconfigure.*Properties` and `spring.ai.openrouter.*`                                                                   | Boot property binding. Custom model, API, and tool beans use the existing auto-configuration backoff rules.                                                                                                        |

The models implement the Spring AI interfaces `ChatModel` (which includes `StreamingChatModel`), `EmbeddingModel` and `ImageModel`. `OpenRouterImageModel.stream()` is a provider extension. The embedding model accepts a `Document` but does not implement `DocumentEmbeddingModel`.

## Build low-level requests

Build low-level requests by naming the fields you set; unset fields are omitted from the JSON body. `build()` throws `NullPointerException` when the required `messages` (Chat Completions) or `input` (Responses) is missing:

```java
ChatCompletionRequest request = ChatCompletionRequest.builder()
    .model("your-model")
    .messages(List.of(new ChatMessage("user", "Say hello.", null, null, null)))
    .temperature(0.2)
    .maxTokens(256)
    .build();
ChatCompletionResponse response = api.chatCompletion(request);
```

New request fields are added to the builders. The records' canonical constructors are public but grow with each field, so code that calls them breaks. `ChatMessage` has no builder, so the example calls its short constructor.

## Implementation details

Mapper packages, `internal`, `support.OptionSnapshots`, error factories/classifiers, `OpenRouterExceptionMessage`, `OpenRouterErrorResponse`, deserializers, runtime hints, and auto-configuration implementation methods are implementation details even where Java visibility is public. They are not supported extension points and may change between releases. Prefer the consumer types above.

## Nullability

Consumer packages declare JSpecify `@NullMarked`, and `@Nullable` marks optional values. The annotations do not check anything at runtime. Kotlin with strict JSpecify checking and null-aware Java analyzers can reject unchecked use of nullable values, so use null checks or Kotlin safe calls.

What to null-check:

- Response fields that the provider may omit: usage, cost, media metadata, and exception diagnostics. When usage is absent, Spring AI metadata reports `EmptyUsage`. Missing response IDs and models keep Spring AI's defaults. Absent optional generation metadata keys are left out.
- Low-level `api.dto` records. Their fields can be absent, and response arrays can contain null entries.
- JSON metadata maps. Their values can be null.

A nullable list or map does not make its elements nullable. Request lists and Spring AI tool callback and context entries must not hold nulls. A Chat Completions tool call without an ID still runs: the Spring AI ID is empty and the follow-up request leaves the wire ID out.

For how a null option interacts with defaults, see [Replacement and composition](chat-options.md#replacement-and-composition).
