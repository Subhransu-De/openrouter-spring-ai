# Chat

What the code does not tell you about chat: the status of each request mode, how OpenRouter treats sessions, how this library differs from Spring AI's OpenAI module, and how upstream providers handle caching.

## Request modes

Use Chat Completions. It is the default and the production path. Responses sends requests to OpenRouter's `/responses` endpoint. It is experimental: opt in with `spring.ai.openrouter.chat.request-mode=responses` only to test that endpoint. It accepts fewer options, and an option it does not support fails before the request is sent instead of being dropped.

## Sessions

`sessionId` is sent as `session_id`. OpenRouter routes every chat request in a session to the same upstream provider, which keeps prompt-cache hits across turns, and groups the session in its observability output. For embeddings, images, speech and transcription, OpenRouter uses the session ID only for observability and does not send it upstream. The 256-character limit is OpenRouter's.

A session ID usually belongs to one conversation, so set it per request. A Boot default suits only an application that runs one session per instance.

## Structured output

- When both `outputSchema` and `responseFormat` are set, `outputSchema` wins, because `ChatClient.entity(...)` parses the reply against that schema. This follows Spring AI's OpenAI options.
- The routed provider decides whether a schema is enforced.
- Spring AI's OpenAI module sends a JSON-schema response format as `strict: true` unless told otherwise. This library sends `strict` only when you set it, so the provider's default applies.
- A `completed` status or a `stop` finish reason does not prove that the output ended before the token limit. When it matters, compare the output token usage with `maxCompletionTokens`.

## Tool calling

Spring AI 2.0 runs the tool loop in `ToolCallingAdvisor`, not in the chat model. The model advertises your tools and returns tool calls; it never runs them.

`spring.ai.tools.resolution.fallback.enabled=true` lets the `ToolCallbackResolver` find a tool by name, so the model can run any tool bean it names.

### Strict function tools

The library checks a strict schema and never rewrites it. Spring AI's OpenAI module rewrites each schema instead: it sets `additionalProperties: false`, makes optional properties nullable and lists every property as required. So a schema generated from a type with an optional field works there and fails here:

```json
{
  "type": "object",
  "properties": { "city": { "type": "string" }, "note": { "type": "string" } },
  "required": ["city"]
}
```

Spring AI's schema generator marks a parameter optional when it has `@ToolParam(required = false)`, `@Nullable` or an equivalent Jackson or OpenAPI annotation. For strict tools, keep every parameter required. For a value that may be absent, write the schema the way the rewrite would:

```json
{
  "type": "object",
  "properties": {
    "city": { "type": "string" },
    "note": { "type": ["string", "null"] }
  },
  "required": ["city", "note"],
  "additionalProperties": false
}
```

The check covers the common [strict function contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode), not every provider keyword, size limit or model restriction. The library does not discover which routes support strict tools. A provider rejection surfaces as a normal API error, with no retry as non-strict.

### Tool failure policy

By default, a failed tool returns `{"error":"Tool execution failed"}` to the model and the loop continues. The starter supplies `OpenRouterToolExecutionExceptionProcessor` before Spring AI creates its shared tool manager, so the policy also applies to other providers that use that manager. Startup logs a warning when another provider's chat model is in the context.

Spring AI does not expose which processor a `ToolCallingManager` uses, so a plain `ToolCallingManager.builder().build()` bean fails at startup. Build custom managers with `OpenRouterToolCallingManagers.withFailurePolicy(...)`, or implement `OpenRouterToolFailurePolicy`. The check accepts only processors declared as beans, so an inline processor instance fails it.

One case is not detected: in a parent context with global lazy initialization, a `FactoryBean` manager whose product type is known only at runtime. Declare it as a regular eager bean.

`spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true` skips the manager check and makes the application responsible for redacting failure results.

## Reasoning

Keep the whole assistant message, metadata included, in history. The next request and each tool round replay the reasoning from that metadata, in order. To redact reasoning, start a new conversation without it: in Responses mode, editing the text, tool calls or refusal while keeping the reasoning is rejected.

## Prompt caching

Attach a `List<OpenRouterCacheBreakpoint>` to a system or user message's metadata to mark the end of a stable prompt prefix. There are no cache defaults or Boot properties, because placement belongs to individual messages. A custom history store must restore the typed list: plain maps, for example after a JSON round trip, fail.

Support depends on the upstream provider:

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | Rejected. Its per-block `prompt_cache_breakpoint` is not implemented here, so only [request-level controls](#request-level-cache-controls) apply.                                                                  |

Upstream providers enforce model eligibility and minimum prompt sizes; the library does not infer them. See [OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching). Request-level automatic caching (a top-level `cache_control`) is not exposed.

### Request-level cache controls

- `promptCacheKey` groups requests that share a prompt prefix. Without a `sessionId`, OpenRouter also uses it as the sticky routing key.
- OpenRouter applies `promptCacheOptions` only to OpenAI models that support explicit prompt caching (GPT-5.6 and newer); other providers ignore it.
- Explicit mode, the only mode OpenRouter documents, turns off OpenAI's automatic caching, so the provider caches only blocks marked with an `OpenRouterCacheBreakpoint`. A request without breakpoints is not cached, which OpenRouter documents as the way to avoid the cache-write charge for a prompt sent once. Responses mode has no breakpoints here, so explicit mode always turns caching off there. That is why the mode is required, and why it is risky as a Boot default.
- OpenRouter converts the `cache_control` marker to OpenAI's `prompt_cache_breakpoint` but does not translate its TTL, so `Ttl.ONE_HOUR` has no effect on OpenAI. The `ttl` of `promptCacheOptions` (`30m` by default, or `12h`) sets the minimum lifetime instead. The `12h` lifetime is billed at a higher cache-write rate.

## Log probabilities and predicted output

`logprobs`, `topLogprobs`, `logitBias`, `verbosity` and `prediction` are named as in Spring AI's `OpenAiChatOptions`, so code that moves from that integration keeps them. In Responses mode `topLogprobs` is sent, but the library does not read log probabilities from Responses output, so `OpenRouterLogprobs.from(...)` returns an empty `Optional`. A prediction belongs to one request, so it has no Boot property.

## Extra request fields

`extraBody` and `providerExtraBody` accept the keys in OpenRouter's [parameter reference](https://openrouter.ai/docs/api_reference/parameters), [provider routing guide](https://openrouter.ai/docs/guides/routing/provider-selection) and [Responses schema](https://openrouter.ai/docs/api/api-reference/responses/create-a-response) that have no typed option. In Boot properties, keep the wire spelling with bracket notation, such as `spring.ai.openrouter.chat.extra-body.[top_logprobs]=0`.

OpenRouter applies its [middle-out message transform](https://openrouter.ai/docs/guides/features/message-transforms) by default to endpoints with a context of 8,192 tokens or less. A prompt longer than the context is then shortened and answered without an error. To get a context-length error instead, send `extraBody(Map.of("transforms", List.of()))`, or set `spring.ai.openrouter.chat.extra-body.[transforms]=` in Boot properties.
