# Chat options

How per-request options combine with model defaults, how to set fallbacks, routing and attribution, and how option objects are copied.

Two rules decide which options a request uses:

- A raw `ChatModel.call(prompt)` uses the prompt's options and ignores the model defaults. Without prompt options, the defaults apply.
- `ChatClient` layers model defaults, client defaults and request options, in that order.

## Replacement and composition

![A raw ChatModel call replaces the model defaults with the prompt options and falls back to Chat Completions. ChatClient layers model defaults, client defaults and request options: a value overrides, null inherits, and stop and tool lists append, so a null seed inherits 7 and Responses rejects it before HTTP.](images/option-composition.svg)

With `ChatClient`, set only the fields that change. Unset fields inherit:

```java
String text = client.prompt().user("Say hello.")
    .options(OpenRouterChatOptions.builder().temperature(0.0).maxCompletionTokens(200))
    .call().content();
```

In Spring AI 2.0.2, `.options(builder)` replaces the client-level `.defaultOptions(builder)` customizer. It does not erase the model defaults. `client.prompt(Prompt)` composes the prompt's options with the client customizer.

With a raw model, supply complete options or derive them from `getOptions()`:

```java
ChatOptions options = chatModel.getOptions().mutate().temperature(0.0).build();
chatModel.call(new Prompt("Say hello.", options));
```

Portable Spring AI `ChatOptions` that are not `OpenRouterChatOptions` keep only the portable fields. OpenRouter fields are dropped and the mode resets to Chat Completions.

Embedding and image models merge supplied options with their defaults, and unset fields inherit. This differs from raw chat replacement.

### Pitfall: switching the request mode

A mode switch fails before HTTP when the model defaults hold a value that Responses rejects. Here the defaults set `includeUsage=true`, and a null request value inherits it:

```java
var chatModel = OpenRouterChatModel.builder()
    .openRouterApi(api)
    .defaultOptions(OpenRouterChatOptions.builder()
        .model("openai/gpt-oss-120b").includeUsage(true).build())
    .build();
ChatClient client = ChatClient.builder(chatModel).build();
client.prompt().user("Say hello.")
    .options(OpenRouterChatOptions.builder()
        .requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
        .includeUsage(null))
    .call().content();
```

`includeUsage(false)` fails too, because Responses rejects any non-null value. The fix is to keep `includeUsage` out of the model defaults and set `includeUsage(true)` on each Chat Completions request.

## Null means inherit

In `ChatClient` composition, a null request option inherits the configured default. It does not clear it, as the `seed` row in the diagram shows. You cannot clear an inherited value for one request. Leave the value out of the defaults, or use a separate model or client.

A copy such as `defaults.mutate().seed(null).build()` has no seed, but merging it back into the original defaults still inherits the original seed.

## Routing, fallbacks and attribution

Set fallback models, provider preferences and a service tier as defaults or per request. A request value replaces the default value:

```java
OpenRouterChatOptions options = OpenRouterChatOptions.builder()
    .model("openai/gpt-oss-120b")
    .models(List.of("openai/gpt-oss-20b")) // fallback models
    .provider(new OpenRouterProviderPreferences(true, null, null,
        List.of("openai"), null, null, "throughput"))
    .serviceTier(OpenRouterServiceTier.FLEX)
    .build();
```

The `OpenRouterProviderPreferences` arguments are `allowFallbacks`, `requireParameters`, `dataCollection`, `order`, `ignore`, `quantizations` and `sort`. The service tier is `AUTO`, `DEFAULT`, `FLEX` or `PRIORITY`.

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
