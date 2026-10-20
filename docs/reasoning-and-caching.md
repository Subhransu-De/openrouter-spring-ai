# Reasoning and prompt caching

How reasoning state is kept across turns, and how to mark prompt-cache breakpoints.

## Reasoning conversation state

Assistant message metadata carries `openrouter.reasoning` (text) and `openrouter.reasoning_details` (an ordered list of opaque JSON nodes) in Chat Completions mode. Responses mode retains complete reasoning output items under `openrouter.responses.reasoning_items`, including encrypted content and unknown fields. The accompanying `openrouter.responses.output_items` snapshot preserves their positions relative to text and function calls during replay. Request mappers replay this state when the assistant message is included in conversation history. Keep the original assistant message and its metadata when adding tool results. Responses rejects history before sending a request if the assistant text, tool calls, or `openrouter.refusal` differ from the saved output snapshot, or reasoning metadata has no output snapshot. Custom history storage must retain both metadata fields. Editing or removing content while keeping the reasoning metadata is rejected. To redact history, start a new conversation with the edited messages and without the old reasoning state. Do not carry encrypted reasoning or output snapshots into that new conversation, since they may retain the original content. The assistant media history restriction also applies to generated images retained in output snapshots, even if the message's media list was cleared.

Streaming assembles consecutive text and summary detail fragments, while retaining encrypted and unknown detail types as opaque items. Assistant metadata contains cumulative snapshots per choice and subscription, so Spring AI message aggregation retains the complete reasoning state. Text content remains incremental. The existing generation metadata key `openrouter.reasoning` remains available for streamed reasoning deltas and synchronous reasoning text.

## Explicit prompt-cache breakpoints

For Chat Completions, attach a `List<OpenRouterCacheBreakpoint>` to a system or user message's metadata. Each exclusive `endIndex` splits the original text into a content block with `cache_control`; the remaining text and image media stay in their original order. Offsets use Java `String.length()` units, must increase, and cannot split a surrogate pair. For example (synthetic content):

```java
String reference = "Reference material to reuse.\n";
UserMessage message = UserMessage.builder()
    .text(reference + "Answer this turn's question.")
    .metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY,
        List.of(new OpenRouterCacheBreakpoint(reference.length()))))
    .build();
chatModel.call(new Prompt(message));
```

The default `Ttl.FIVE_MINUTES` emits `{"type":"ephemeral"}`; `Ttl.ONE_HOUR` adds `"ttl":"1h"`. Both calls and streams use these boundaries. There are no cache model defaults or Boot properties: placement belongs to individual messages, so there is no option precedence. Keep the typed metadata and unchanged text in conversation history, including tool continuations. Custom history stores must restore the typed breakpoints; generic maps are rejected. Recompute offsets when editing text.

The integration supports at most four breakpoints per request, with all one-hour boundaries before five-minute boundaries. Assistant messages, tool results, tool definitions, and image blocks are not supported breakpoint targets. Invalid metadata, offsets, ordering, or placement fail explicitly.

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | This metadata is rejected. Its per-block `prompt_cache_breakpoint` has different semantics and is not implemented here.                                                                                            |

Provider/model eligibility and minimum prompt sizes are enforced upstream; the library does not infer capabilities from model names or fallback routing. Consult [OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching) for current restrictions. This explicit content API is narrower than Spring AI's Anthropic caching strategies: it does not automatically select message or tool boundaries. Provider implicit caching remains available without these markers. Request-level automatic caching, `prompt_cache_key`, and cache usage accounting are separate capabilities.
