# Switching from another Spring AI provider

Use this page when an application moves from another Spring AI provider starter, such as OpenAI's or Anthropic's, to this one. Code that uses only Spring AI's own types keeps working: `ChatClient`, `Prompt`, `ChatResponse.getMetadata().getUsage()` and `Generation.getMetadata().getFinishReason()`.

## Change the dependency

Replace the provider's starter with this one. See [Install](getting-started.md#install).

```xml
<dependency>
    <groupId>de.subhransu</groupId>
    <artifactId>openrouter-spring-ai-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

## Change the properties

Set the selectors to `openrouter`, move the provider's prefix to `spring.ai.openrouter`, and use OpenRouter model names and an OpenRouter API key.

```yaml
# Before, with OpenAI as the example
spring:
  ai:
    model:
      chat: openai
    openai:
      api-key: ${OPENAI_API_KEY}
      chat:
        model: gpt-5.4-mini
```

```yaml
# After
spring:
  ai:
    model:
      chat: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      chat:
        model: openai/gpt-5.4-mini
```

[Getting started](getting-started.md#spring-ai-model-interfaces) lists the selector for each model.

## Change the options class

Replace the provider's options class, such as `OpenAiChatOptions` or `AnthropicChatOptions`, with `OpenRouterChatOptions`. Options that Spring AI defines, such as `temperature` and `maxTokens`, keep their names.

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().temperature(0.0).maxCompletionTokens(200))
        .call()
        .content();
```

## Read metadata with the typed readers

Each provider writes its own metadata keys. This library writes `openrouter.*` keys and has a typed reader for each, so the application needs no key names.

```java
ChatResponse response = chatClient.prompt().user("Say hello.").call().chatResponse();
Optional<String> reasoning = OpenRouterMetadata.reasoning(response.getResult());
Optional<String> refusal = OpenRouterMetadata.refusal(response.getResult());
Double cost = OpenRouterUsage.from(response).getCost();
```

## Catch the library's failures

Every failure implements `OpenRouterFailure`. HTTP failures also extend Spring AI's `TransientAiException` or `NonTransientAiException`, so a handler for those types keeps working.

```java
try {
  chatClient.prompt().user("Say hello.").call().content();
} catch (RuntimeException ex) {
  if (ex instanceof OpenRouterFailure failure && failure.isTransient()) {
    // Safe to try again later.
  }
}
```

## Check these differences

- **Retries:** this library uses Spring AI's `RetryTemplate`, which Boot builds from `spring.ai.retry.*`. A retry setting under the old provider's prefix no longer applies. See [Retries and billing](production.md#retries-and-billing).
- **Timeout:** `spring.ai.openrouter.connection.timeout` replaces the old provider's timeout property. The default is 2 minutes. See [Timeouts](production.md#timeouts).
- **Finish reasons:** the values are `STOP`, `LENGTH`, `TOOL_CALLS` and `CONTENT_FILTER`. A value that the library does not know passes through unchanged.
- **Rate limit:** `getRateLimit()` is empty, because OpenRouter sends no rate-limit headers on a successful response.
- **Stored conversations:** a history that was stored with another provider's metadata continues without its reasoning state. This library replays only the reasoning that OpenRouter returned.
- **Moderation and decisions:** `ModerationModel` is not available because OpenRouter has no moderation endpoint, and `DecisionModel` (added in Spring AI 2.1) is not implemented yet.
