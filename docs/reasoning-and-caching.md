# Reasoning and prompt caching

Reasoning models can think before they answer. Turn it on with `reasoning(...)` on `OpenRouterChatOptions`, or with `spring.ai.openrouter.chat.reasoning.*` (`effort` or `max-tokens`, not both, plus `exclude` and `enabled`). Read the text from the `openrouter.reasoning` key in the generation metadata. Replay across turns needs no action if your history keeps the full `AssistantMessage`, not just its `.content()`.

```java
OpenRouterChatOptions options = OpenRouterChatOptions.builder()
	.reasoning(new OpenRouterReasoningOptions("medium", null, null, null))
	.build();
UserMessage question = new UserMessage("Plan a three-step migration.");
ChatResponse first = chatModel.call(new Prompt(question, options));
String thinking = first.getResult().getMetadata().get("openrouter.reasoning");
AssistantMessage reply = first.getResult().getOutput(); // keep this whole message
chatModel.call(new Prompt(List.of(question, reply, new UserMessage("Shorten step two.")), options));
```

`openrouter.reasoning` holds the whole text for a synchronous call and the new delta for each chunk in a stream. The same key on the assistant message is cumulative in streams.

## Reasoning conversation state

The assistant message also carries the reasoning state in its metadata. Keep that message unchanged in conversation history, including when you add tool results, and the next request replays the reasoning in its original order.

![Turn 1's assistant message carries openrouter.reasoning and openrouter.reasoning_details in Chat Completions, or openrouter.responses.reasoning_items and openrouter.responses.output_items in Responses. Kept unchanged in history, it is replayed in order with the next request and each tool round. Editing the text, tool calls or refusal while keeping the reasoning is rejected in Responses mode; to redact, start a new conversation without the old reasoning or output snapshots.](images/reasoning-replay.svg)

Responses `reasoning_items` keep encrypted content and unknown fields. The [assistant media history restriction](multimodal.md#assistant-media-history) also applies to generated images retained in output snapshots, even if the message's media list was cleared.

Streaming keeps encrypted and unknown detail types as opaque items. Assistant metadata holds cumulative snapshots per choice and subscription, so Spring AI message aggregation retains the complete reasoning state. Text content stays incremental.

## Explicit prompt-cache breakpoints

A cache breakpoint marks the end of a stable prompt prefix, such as reference material, that the provider can cache and reuse in later requests. For Chat Completions, attach a `List<OpenRouterCacheBreakpoint>` to a system or user message's metadata. For example (synthetic content):

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

Image media keep their original order after the split. Calls and streams use the same boundaries. There are no cache model defaults or Boot properties, because placement belongs to individual messages. Keep the typed metadata and unchanged text in conversation history, including tool continuations. A custom history store must restore the breakpoints as a `List<OpenRouterCacheBreakpoint>`. If it returns plain maps, for example after a JSON round trip, the request fails with `IllegalArgumentException`. Recompute offsets when you edit text.

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | This metadata is rejected. Its per-block `prompt_cache_breakpoint` has different semantics and is not implemented here.                                                                                            |

Upstream providers enforce model eligibility and minimum prompt sizes; the library does not infer them from model names or fallback routing. See [OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching) for current restrictions. This API is narrower than Spring AI's Anthropic caching strategies: it does not pick message or tool boundaries for you. Provider implicit caching still works without these markers. Request-level automatic caching, [`prompt_cache_key`](extensions.md), and cache usage accounting (cached token counts, see [Observability](observability.md)) are separate capabilities.
