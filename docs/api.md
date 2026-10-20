# Supported API and nullability

This page lists the types applications can depend on, and the null contracts they follow.

## Supported types

The supported consumer API consists of the following types under `de.subhransu.openrouter.springai`.

| API                                                                                                                        | Supported use                                                                                                                                                                                                      |
| -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `chat`, `embedding`, `image`                                                                                               | Models and their builders, options and option builders, routing/reasoning/format records, usage and generated-image metadata. Use the Spring AI model interfaces for calls and streams.                            |
| `OpenRouterIdentifiers`                                                                                                    | Provider identifiers used to select this integration.                                                                                                                                                              |
| `api.OpenRouterApi`, `api.OpenRouterRequestMode`                                                                           | Direct HTTP calls and client construction, including `RestClient.Builder`, `WebClient.Builder`, Jackson, attribution, timeouts, and response limits.                                                               |
| `api.dto`                                                                                                                  | Low-level request and response records used by `OpenRouterApi`. Build `ChatCompletionRequest` and `ResponsesRequest` with `builder()`; wire fields can be absent. Responses mode remains experimental.             |
| `chat.OpenRouterToolFailurePolicy`, `chat.OpenRouterToolCallingManagers`, `chat.OpenRouterToolExecutionExceptionProcessor` | Custom tool-manager failure policy, a factory for policy-declaring managers, and failure rendering. The policy accessor must return the actual, non-null processor. Tool execution belongs to Spring AI's advisor. |
| `errors`, `chat.errors`, `api.errors.OpenRouterApiException`                                                               | Exception types, diagnostic records, category enums, and inspection interfaces. Missing diagnostics are nullable. `OpenRouterHttpException` is sealed; consumers cannot implement it.                              |
| `autoconfigure.*Properties` and `spring.ai.openrouter.*`                                                                   | Boot property binding. Custom model, API, and tool beans use the existing auto-configuration backoff rules.                                                                                                        |

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

New OpenRouter request fields are added to the builders. The records' canonical constructors stay public because Java requires it, but their parameter lists grow with each new field, so code that calls them breaks when a field is added.

## Implementation details

Mapper packages, `internal`, `support.OptionSnapshots`, error factories/classifiers, `OpenRouterExceptionMessage`, `OpenRouterErrorResponse`, deserializers, runtime hints, and auto-configuration implementation methods are implementation details even where Java visibility is public. They are not supported extension points and may change between releases. Prefer the consumer types above.

## Nullability

Consumer packages declare JSpecify `@NullMarked`; `@Nullable` marks optional values. A nullable list or map does not imply nullable elements. Request lists and Spring AI tool callback/context entries retain their non-null element contracts. JSON metadata maps allow null values, and response arrays can contain null entries. Handle missing response fields before dereferencing them, including usage, cost, and media metadata. All maintained production packages, including implementation helpers and samples, declare `@NullMarked`. Wire DTOs retain nullable fields and elements; model mappers reject null choices, Responses output/message-content entries, and missing required tool-call fields with a protocol exception before exposing them to Spring AI. Chat Completions tool calls without IDs still execute: the Spring AI ID is empty and the follow-up wire ID remains absent. Missing response IDs/models and usage retain Spring AI metadata defaults (`EmptyUsage` when usage is absent); absent optional generation metadata keys are omitted. The annotations themselves do not perform runtime validation.

Kotlin with strict JSpecify checking and null-aware Java analyzers can reject unchecked dereferences of nullable values. Use null checks or Kotlin safe calls.

For how a null option interacts with defaults, see [Null means inherit](chat-options.md#null-means-inherit).
