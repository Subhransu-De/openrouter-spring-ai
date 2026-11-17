# Reasoning and prompt caching

Reasoning models can think before they answer. Turn it on with `reasoning(...)` on `OpenRouterChatOptions`, or with `spring.ai.openrouter.chat.reasoning.*` (`effort` or `max-tokens`, not both, plus `exclude` and `enabled`). Read the text with `OpenRouterMetadata.reasoning(generation)`, which returns an empty `Optional` when the model sent no reasoning. In a stream, it returns each chunk's delta, and `OpenRouterMetadata.reasoning(assistantMessage)` returns the text so far. Both read the `OpenRouterMetadata.REASONING` key (`openrouter.reasoning`).

```java
ChatClient client = ChatClient.create(chatModel);
OpenRouterChatOptions.Builder options = OpenRouterChatOptions.builder()
	.reasoning(OpenRouterReasoningOptions.builder().effort(OpenRouterReasoningEffort.MEDIUM).build());
UserMessage question = new UserMessage("Plan a three-step migration.");
ChatResponse first = client.prompt().messages(question).options(options).call().chatResponse();
String thinking = OpenRouterMetadata.reasoning(first.getResult()).orElse("");
AssistantMessage reply = first.getResult().getOutput(); // keep this whole message
client.prompt().messages(question, reply, new UserMessage("Shorten step two.")).options(options)
	.call().chatResponse();
```

The request options combine with the model's defaults, so the model and other settings stay.

`OpenRouterReasoningEffort` holds the efforts OpenRouter documents: `none`, `minimal`, `low`, `medium`, `high`, `xhigh` and `max`. `effort(String)` also takes a value OpenRouter adds later. An undocumented effort is sent as written and logged once as a warning, so a typo such as `hgih` shows up in the log before the provider rejects or ignores it.

## Reasoning conversation state

The assistant message carries the reasoning state in its metadata. Keep it unchanged in history, tool results included, and the next request replays the reasoning in order.

![Turn 1's assistant message carries openrouter.reasoning and openrouter.reasoning_details in Chat Completions, or openrouter.responses.reasoning_items and openrouter.responses.output_items in Responses. Kept unchanged in history, it is replayed in order with the next request and each tool round. Editing the text, tool calls or refusal while keeping the reasoning is rejected in Responses mode; to redact, start a new conversation without the old reasoning or output snapshots.](images/reasoning-replay.svg)

Responses `reasoning_items` keep encrypted content and unknown fields. The [assistant media history restriction](multimodal.md#assistant-media-history) also applies to generated images retained in output snapshots, even if the message's media list was cleared.

In streams, encrypted and unknown detail types stay opaque items. The assistant metadata is cumulative per choice, so Spring AI message aggregation keeps the complete reasoning state.

## Explicit prompt-cache breakpoints

A cache breakpoint marks the end of a stable prompt prefix, such as reference material, that the provider can cache and reuse in later requests. For Chat Completions, attach a `List<OpenRouterCacheBreakpoint>` to a system or user message's metadata. For example (synthetic content):

```java
String reference = "Reference material to reuse.\n";
UserMessage message = UserMessage.builder()
    .text(reference + "Answer this turn's question.")
    .metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY,
        // for one hour: new OpenRouterCacheBreakpoint(reference.length(), Ttl.ONE_HOUR)
        List.of(new OpenRouterCacheBreakpoint(reference.length()))))
    .build();
chatModel.call(new Prompt(message));
```

![The text splits at endIndex 29, the exclusive end of the reference text in String.length() units. The first content block carries cache_control type ephemeral; the rest of the text follows unchanged. Ttl.FIVE_MINUTES is the default; Ttl.ONE_HOUR adds ttl 1h. Rules checked before sending: at most 4 per request, 1-hour boundaries before 5-minute ones, system and user text only, increasing offsets that do not split a surrogate pair. Assistant messages, tool results, tool definitions and images are not targets, and Responses mode rejects this metadata.](images/cache-breakpoints.svg)

- Calls and streams use the same boundaries, and image media keep their order after the split. There are no cache defaults or Boot properties, because placement belongs to individual messages.
- Keep the typed metadata and unchanged text in history, tool continuations included. A custom history store must restore a `List<OpenRouterCacheBreakpoint>`. Plain maps, for example after a JSON round trip, fail with `IllegalArgumentException`.
- Recompute offsets when you edit text.

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | This metadata is rejected. Its per-block `prompt_cache_breakpoint` is not implemented here, so only [request-level controls](#request-level-cache-controls) apply.                                                 |

Upstream providers enforce model eligibility and minimum prompt sizes; the library does not infer them (see [OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching)). Request-level automatic caching (a top-level `cache_control`) is not exposed. Cache usage accounting (cached token counts, see [Observability](observability.md)) is a separate capability.

## Request-level cache controls

Two chat options apply to a whole request, in Chat Completions and Responses mode, in calls and streams:

- `promptCacheKey` groups requests that share a prompt prefix, sent as `prompt_cache_key`. Without a `sessionId`, OpenRouter also uses it as the sticky routing key.
- `promptCacheOptions` holds OpenRouter's `prompt_cache_options`. OpenRouter applies them only to OpenAI models that support explicit prompt caching (GPT-5.6 and newer); other providers do not use them.

```java
OpenRouterChatOptions options = OpenRouterChatOptions.builder()
    .promptCacheKey("example-app")
    .promptCacheOptions(OpenRouterPromptCacheOptions.explicit(OpenRouterPromptCacheOptions.TTL_12_HOURS))
    .build();
```

Explicit mode, the only mode OpenRouter documents, turns off OpenAI's automatic breakpoints, so the provider caches only marked blocks. How it combines with the cache breakpoints above:

- In Chat Completions, `OpenRouterCacheBreakpoint` marks the blocks. OpenRouter converts the `cache_control` marker to OpenAI's `prompt_cache_breakpoint`.
- OpenRouter does not translate a breakpoint TTL for OpenAI, so `Ttl.ONE_HOUR` has no effect there. The `ttl` of `promptCacheOptions`, `30m` by default or `12h`, sets the minimum lifetime of every entry the request writes. The `12h` lifetime is billed at a higher cache-write rate.
- A request without breakpoints is not cached. OpenRouter documents this as the way to avoid the cache-write charge for a prompt that is sent once. Responses mode has no breakpoints here, so explicit mode always turns caching off in that mode.

The mode is required, because explicit mode turns off automatic caching: a Boot `prompt-cache-options.ttl` without `prompt-cache-options.mode=explicit` fails at startup. An undocumented mode or TTL is sent as written and logged once as a warning.
