# Chat options

How runtime options combine with model defaults, and how option objects are copied.

## Replacement and composition

![A raw ChatModel call replaces the model defaults with the prompt options and falls back to Chat Completions. ChatClient layers model defaults, client defaults and request options: a value overrides, null inherits, and stop and tool lists append, so a null seed inherits 7 and Responses rejects it before HTTP.](images/option-composition.svg)

With a raw model, supply complete `OpenRouterChatOptions` (including model and mode) or derive them from `getOptions()`. In Spring AI 2.0.1, `.options(builder)` replaces the client-level `.defaultOptions(builder)` customizer; it does not erase the model defaults. `client.prompt(Prompt)` composes supplied prompt options with the client customizer. For example, given an injected `OpenRouterApi api`, this synthetic mode switch fails before HTTP because the model defaults retain `includeUsage=true`:

```java
var chatModel = OpenRouterChatModel.builder()
    .openRouterApi(api)
    .defaultOptions(OpenRouterChatOptions.builder()
        .model("your-model").includeUsage(true).build())
    .build();
ChatClient client = ChatClient.builder(chatModel).build();
client.prompt().user("Say hello.")
    .options(OpenRouterChatOptions.builder()
        .requestMode(OpenRouterRequestMode.OPENAI_RESPONSES)
        .includeUsage(null))
    .call().content();
```

Setting `includeUsage(false)` also fails: Responses rejects any non-null value. For clients that switch modes, set `includeUsage(true)` only on individual Chat Completions requests. Mutating a request to null does not clear a value inherited during composition.

Embedding and image models merge supplied options with their defaults directly; unset fields inherit. Their behavior is intentionally different from raw chat replacement. The implemented Spring AI interfaces are `ChatModel`/`StreamingChatModel`, `EmbeddingModel`, and `ImageModel`. `OpenRouterImageModel.stream()` is a provider extension, and accepting a `Document` for embedding does not implement `DocumentEmbeddingModel`.

## Null means inherit

A null runtime option means "inherit the configured default", not "clear it", as the `seed` row in the diagram above shows. Calling `defaults.mutate().seed(null).build()` creates a separate defaults object with no seed; it does not clear a seed when that object is merged back into the original defaults.

## Option snapshots

Option builders, copies, and model defaults own detached collection snapshots. Provider routing lists, image input references, and nested JSON maps/lists (metadata, image configuration, provider options, and tool choice) are read-only through getters. Use `mutate()` to derive changed options. Tool-context maps/lists are also snapshotted; callbacks and other opaque application objects remain shared by identity. Use JSON maps/lists for container isolation; arbitrary mutable objects are not cloned. Null values and collection order are preserved. Embedding builders return independent results; explicit setters change only the option instance on which they are called.

JSON maps and lists are copied recursively. Snapshots do not promise arbitrary deep immutability or general thread safety. Existing option setters and Boot property beans remain mutable, so do not mutate shared options concurrently with calls.
