# Chat options

How per-request options combine with model defaults, how to set fallbacks, routing and attribution, and how option objects are copied.

Two rules decide which options a request uses:

- A raw `ChatModel.call(prompt)` uses the prompt's options and ignores the model defaults. Without prompt options, the defaults apply.
- `ChatClient` starts from the model defaults and adds the client's `.defaultOptions(...)`. A request's `.options(...)` replaces that client customizer, but the model defaults still apply.

## Replacement and composition

![A raw ChatModel call replaces the model defaults with the prompt options, and portable ChatOptions fall back to Chat Completions. ChatClient merges request options onto the model defaults: a value overrides, null inherits and stop lists append. A request's .options(…) replaces the client's .defaultOptions(…). So a null seed inherits 7, and Responses rejects it before HTTP.](images/option-composition.svg)

With `ChatClient`, set only the fields that change. Unset fields inherit:

```java
String text = client.prompt().user("Say hello.")
    .options(OpenRouterChatOptions.builder().temperature(0.0).maxCompletionTokens(200))
    .call().content();
```

With a raw model, supply complete options or derive them from `getOptions()`. `OpenRouterChatModel` returns `OpenRouterChatOptions`, so `mutate()` keeps the OpenRouter type and fields:

```java
ChatOptions options = chatModel.getOptions().mutate().temperature(0.0).build();
chatModel.call(new Prompt("Say hello.", options));
```

Portable Spring AI `ChatOptions` that are not `OpenRouterChatOptions` keep only the portable fields. OpenRouter fields are dropped and the mode resets to Chat Completions.

Embedding and image models merge supplied options with their defaults, and unset fields inherit. This differs from raw chat replacement.

A null request option inherits the default; it cannot clear it, as the `seed` row in the diagram shows. To drop a default for one request, leave it out of the defaults or use a separate model or client.

### Pitfall: switching the request mode

A switch to Responses fails before HTTP when the model defaults hold a value that Responses rejects, such as `includeUsage=true`. A null request value inherits that default, and `includeUsage(false)` is rejected too. Keep such options out of the model defaults and set them on each Chat Completions request.

## Routing, fallbacks and attribution

Set fallback models, provider preferences and a service tier as defaults or per request. A request value replaces the default value. `models` lists alternative models; `allowFallbacks` controls fallback between upstream providers:

```java
OpenRouterChatOptions options = OpenRouterChatOptions.builder()
    .model("openai/gpt-oss-120b")
    .models(List.of("openai/gpt-oss-20b"))
    .provider(new OpenRouterProviderPreferences(
        true /* allowFallbacks */, null /* requireParameters */, null /* dataCollection */,
        List.of("openai") /* order */, null /* ignore */, null /* quantizations */,
        "throughput" /* sort */))
    .serviceTier(OpenRouterServiceTier.FLEX)
    .build();
```

The service tier is `AUTO`, `DEFAULT`, `FLEX` or `PRIORITY`.

The same defaults as Boot properties, plus the attribution headers:

```properties
spring.ai.openrouter.chat.models[0]=openai/gpt-oss-20b
spring.ai.openrouter.chat.provider.allow-fallbacks=true
spring.ai.openrouter.chat.provider.order[0]=openai
spring.ai.openrouter.chat.provider.sort=throughput
spring.ai.openrouter.chat.service-tier=FLEX
spring.ai.openrouter.app.http-referer=https://example.com
spring.ai.openrouter.app.title=Example app
spring.ai.openrouter.app.categories[0]=spring-ai
```

The `app.*` properties set the `HTTP-Referer`, `X-OpenRouter-Title` and `X-OpenRouter-Categories` headers on every request. Categories are joined with commas. For reasoning, see [Reasoning and prompt caching](reasoning-and-caching.md).

## Option snapshots

- Builders, copies and model defaults hold their own copies of collections. Getters return routing lists and JSON maps and lists as read-only. Use `mutate()` to derive changed options.
- JSON maps and lists are copied recursively. Callbacks and other application objects, such as tool context values, are shared by identity and not cloned.
- Snapshots do not make options thread-safe. Option setters and Boot property beans stay mutable, so do not change shared options while calls run.
