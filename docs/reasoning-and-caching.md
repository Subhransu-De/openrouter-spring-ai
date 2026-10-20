# Reasoning and prompt caching

How reasoning state is kept across turns, and how to mark prompt-cache breakpoints.

## Reasoning conversation state

The assistant message carries the reasoning state in its metadata. Keep that message unchanged in conversation history, including when you add tool results, and the next request replays the reasoning in its original order.

![Turn 1's assistant message carries openrouter.reasoning and openrouter.reasoning_details in Chat Completions, or openrouter.responses.reasoning_items and openrouter.responses.output_items in Responses. Kept unchanged in history, it is replayed in order with the next request and each tool round. Editing the text, tool calls or refusal while keeping the reasoning is rejected in Responses mode; to redact, start a new conversation without the old reasoning or output snapshots.](images/reasoning-replay.svg)

Responses `reasoning_items` keep encrypted content and unknown fields. The assistant media history restriction also applies to generated images retained in output snapshots, even if the message's media list was cleared.

Streaming keeps encrypted and unknown detail types as opaque items, and assistant metadata holds cumulative snapshots per choice and subscription, so Spring AI message aggregation retains the complete reasoning state. Text content remains incremental. The generation metadata key `openrouter.reasoning` carries streamed reasoning deltas and synchronous reasoning text.

## Explicit prompt-cache breakpoints

For Chat Completions, attach a `List<OpenRouterCacheBreakpoint>` to a system or user message's metadata. For example (synthetic content):

```java
String reference = "Reference material to reuse.\n";
UserMessage message = UserMessage.builder()
    .text(reference + "Answer this turn's question.")
    .metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY,
        List.of(new OpenRouterCacheBreakpoint(reference.length()))))
    .build();
chatModel.call(new Prompt(message));
```

![The text splits at endIndex 29, the exclusive end of the reference text in String.length() units. The first content block carries cache_control type ephemeral; the rest of the text follows unchanged. Ttl.FIVE_MINUTES is the default; Ttl.ONE_HOUR adds ttl 1h. Rules checked before sending: at most 4 per request, 1-hour boundaries before 5-minute ones, system and user text only, increasing offsets that do not split a surrogate pair. Assistant messages, tool results, tool definitions and images are not targets, and Responses mode rejects this metadata.](images/cache-breakpoints.svg)

Image media stay in their original order after the split. Both calls and streams use these boundaries. There are no cache model defaults or Boot properties: placement belongs to individual messages, so there is no option precedence. Keep the typed metadata and unchanged text in conversation history, including tool continuations. Custom history stores must restore the typed breakpoints; generic maps are rejected. Recompute offsets when editing text.

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | This metadata is rejected. Its per-block `prompt_cache_breakpoint` has different semantics and is not implemented here.                                                                                            |

Provider/model eligibility and minimum prompt sizes are enforced upstream; the library does not infer capabilities from model names or fallback routing. Consult [OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching) for current restrictions. This explicit content API is narrower than Spring AI's Anthropic caching strategies: it does not automatically select message or tool boundaries. Provider implicit caching remains available without these markers. Request-level automatic caching, `prompt_cache_key`, and cache usage accounting are separate capabilities.
