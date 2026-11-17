# Chat options

How per-request options combine with model defaults, how to set fallbacks, routing, attribution, sessions, traces, log probabilities and predicted output, and how option objects are copied.

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

A null request option inherits the default; it cannot clear it, as the `seed` row in the diagram shows. The exception is the output format: a request that sets `responseFormat` or `outputSchema` replaces both defaults, as described in [Structured output](request-modes.md#structured-output). To drop a default for one request, leave it out of the defaults or use a separate model or client.

### Pitfall: switching the request mode

A switch to Responses fails before HTTP when the model defaults hold a value that Responses rejects, such as `includeUsage=true`. A null request value inherits that default, and `includeUsage(false)` is rejected too. Keep such options out of the model defaults and set them on each Chat Completions request.

## Routing, fallbacks and attribution

Set fallback models, provider preferences and a service tier as defaults or per request. A request value replaces the default value. `models` lists alternative models; `allowFallbacks` controls fallback between upstream providers:

```java
OpenRouterChatOptions options = OpenRouterChatOptions.builder()
    .model("openai/gpt-oss-120b")
    .models(List.of("openai/gpt-oss-20b"))
    .provider(OpenRouterProviderPreferences.builder()
        .allowFallbacks(true)
        .order(List.of("openai"))
        .sort(OpenRouterProviderSort.THROUGHPUT)
        .build())
    .serviceTier(OpenRouterServiceTier.FLEX)
    .build();
```

The service tier is `AUTO`, `DEFAULT`, `FLEX` or `PRIORITY`.

`OpenRouterProviderSort`, `OpenRouterDataCollection` and `OpenRouterQuantization` hold the sort, data collection and quantization values that OpenRouter documents. The String setters also take values that OpenRouter adds later. An undocumented value is sent as written and each distinct value is logged once as a warning. Request lists such as `models`, `order` and `quantizations` must not contain `null`; the builder rejects a null element.

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

## Sessions and traces

`sessionId` groups related requests, such as one conversation or agent run, and is sent as `session_id`. OpenRouter routes every chat request in a session to the same upstream provider, which helps prompt-cache hits across turns. It also groups the requests in OpenRouter's observability output. `trace` holds the `trace_id`, `trace_name`, `span_name`, `generation_name` and `parent_span_id` that OpenRouter sends to its broadcast destinations. Entries in its `metadata` map are sent next to them as custom keys.

A session ID usually belongs to one conversation, so set it per request:

```java
String answer = client.prompt().user(question)
    .options(OpenRouterChatOptions.builder()
        .sessionId(conversationId)
        .trace(OpenRouterTrace.builder().traceId(runId).generationName("answer").build()))
    .call().content();
```

Both options work in Chat Completions and Responses mode, in calls and streams. `OpenRouterEmbeddingOptions`, `OpenRouterImageOptions`, `OpenRouterAudioSpeechOptions` and `OpenRouterAudioTranscriptionOptions` have the same two options. For embeddings, images and audio OpenRouter uses the session ID only for observability and does not send it to the upstream provider.

A request `sessionId` overrides the default. A request `trace` replaces the default trace as a whole; it does not merge with it field by field. The builders reject a `sessionId` longer than 256 characters, OpenRouter's limit, and a `metadata` key that repeats a named trace field, such as `trace_id`. Boot defaults are checked at startup:

```properties
spring.ai.openrouter.chat.session-id=synthetic-session
spring.ai.openrouter.chat.trace.trace-name=example-app
spring.ai.openrouter.chat.trace.metadata.environment=staging
```

The `embedding` and `image` prefixes take the same keys. A default session ID suits an application that runs one session per instance. Otherwise set it per request. `extraBody` still rejects `session_id` and `trace`; use the typed options.

## Log probabilities, logit bias, verbosity and predicted output

Where Spring AI's `OpenAiChatOptions` has the same option, the name is the same, so code that moves from that integration keeps it. Each option is sent as the OpenRouter field in the second column:

| Option               | Wire field             | Request modes               | Value                                                                                                      |
| -------------------- | ---------------------- | --------------------------- | ---------------------------------------------------------------------------------------------------------- |
| `logprobs`           | `logprobs`             | Chat Completions            | Whether to return the log probability of each output token                                                 |
| `topLogprobs`        | `top_logprobs`         | Chat Completions, Responses | 0 to 20 alternatives per token; Chat Completions also needs `logprobs=true`                                |
| `logitBias`          | `logit_bias`           | Chat Completions            | Token ID to a bias from -100 to 100; a request map replaces the default map                                |
| `verbosity`          | `verbosity`            | Chat Completions            | `low`, `medium`, `high`, `xhigh` or `max`                                                                  |
| `promptCacheKey`     | `prompt_cache_key`     | Chat Completions, Responses | A non-empty key; see [Request-level cache controls](reasoning-and-caching.md#request-level-cache-controls) |
| `promptCacheOptions` | `prompt_cache_options` | Chat Completions, Responses | See [Request-level cache controls](reasoning-and-caching.md#request-level-cache-controls)                  |
| `prediction`         | `prediction`           | Chat Completions            | Text that the answer is expected to largely repeat, such as a file being edited                            |

Read the log probabilities with `OpenRouterLogprobs.from(assistantMessage)`. It returns an empty `Optional` when the provider sent neither content nor refusal tokens:

```java
ChatResponse response = client.prompt().user("Answer yes or no.")
    .options(OpenRouterChatOptions.builder().logprobs(true).topLogprobs(3))
    .call().chatResponse();
OpenRouterLogprobs.from(response.getResult().getOutput()).ifPresent(logprobs -> {
    for (OpenRouterLogprobs.Token token : logprobs.content()) {
        System.out.println(token.token() + " " + token.logprob() + " " + token.topLogprobs());
    }
});
```

- `content()` holds the answer tokens and `refusal()` the refusal tokens, in output order. Entries without a token string or a numeric log probability are left out. `bytes()` is `null` when the provider sent no byte list or a value that is not an integer from 0 to 255.
- In a stream, each message holds the tokens so far, so the last message holds them all.
- The raw values stay in `openrouter.choice.extensions`; see [Optional request and response extensions](extensions.md).
- In Responses mode `topLogprobs` is sent, but the library does not read log probabilities from Responses output, so the reader returns an empty `Optional`.

The builders reject a `topLogprobs` outside 0 to 20, an undocumented `verbosity`, an empty `promptCacheKey`, a null token ID or bias, and a typed option together with the same `extraBody` key, such as `promptCacheKey` and `extraBody` `prompt_cache_key`. A Boot default with one of these fails at startup. These requests fail with `IllegalArgumentException` before HTTP:

- A Responses request with `logprobs`, `logitBias`, `verbosity` or `prediction`, even when the value comes from the model defaults. See [Pitfall: switching the request mode](#pitfall-switching-the-request-mode).
- A Chat Completions request with `topLogprobs` but without `logprobs=true`, from the typed option or `extraBody`.
- A request option and a default that set the same field twice, once typed and once in `extraBody`. Use one of them.

Model and provider support still applies. A prediction belongs to one request, so it has no Boot property. The other options do:

```properties
spring.ai.openrouter.chat.logprobs=true
spring.ai.openrouter.chat.top-logprobs=3
spring.ai.openrouter.chat.logit-bias.[50256]=-100
spring.ai.openrouter.chat.verbosity=low
spring.ai.openrouter.chat.prompt-cache-key=example-app
spring.ai.openrouter.chat.prompt-cache-options.mode=explicit
spring.ai.openrouter.chat.prompt-cache-options.ttl=12h
```

Explicit cache mode turns off the provider's automatic caching for every request without a cache breakpoint. Read [Request-level cache controls](reasoning-and-caching.md#request-level-cache-controls) before you set it as a default.

## Option snapshots

- Builders, copies and model defaults hold their own copies of collections. Getters return routing lists and JSON maps and lists as read-only. Use `mutate()` to derive changed options.
- JSON maps and lists are copied recursively. Callbacks and other application objects, such as tool context values, are shared by identity and not cloned.
- Options have no setters, as in Spring AI 2.0: `mutate()` returns a builder for a changed copy and leaves the original untouched. Boot property beans stay mutable, so do not change them while calls run.
