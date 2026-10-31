# Optional request and response extensions

Send optional OpenRouter request fields that have no typed option, and read unknown response fields.

`OpenRouterChatOptions.builder().extraBody(Map.of("logprobs", true, "top_logprobs", 0))` adds supported optional fields at the request root. This is a bounded extension API: unknown keys and standard fields such as `model`, `messages`, `input`, `stream`, `provider`, and `tools` are rejected, even when their typed option is unset.

Root keys go in `extraBody`; provider keys go in `providerExtraBody`.

| Extension keys                                                       | Chat Completions | Responses | Java / wire representation                                                   |
| -------------------------------------------------------------------- | ---------------- | --------- | ---------------------------------------------------------------------------- |
| `logit_bias`, `logprobs`                                             | Supported        | Rejected  | Token-ID map / boolean at request root                                       |
| `top_logprobs`, `prompt_cache_key`                                   | Supported        | Supported | Integer / nullable string at request root                                    |
| `verbosity`                                                          | Supported        | Rejected  | String at request root; Responses `text.verbosity` is not exposed            |
| `transforms`                                                         | Supported        | Rejected  | List of transform names at request root; `[]` disables middle-out            |
| Provider `only`, zero data retention (`zdr`), `max_price`            | Supported        | Supported | List / boolean / object inside `provider`; hard routing restrictions         |
| Provider `sort`, `preferred_min_throughput`, `preferred_max_latency` | Supported        | Supported | String or structured sort / number or percentile object; routing preferences |

The matrix follows the OpenRouter [parameter reference](https://openrouter.ai/docs/api_reference/parameters), [provider routing guide](https://openrouter.ai/docs/guides/routing/provider-selection), and [Responses schema](https://openrouter.ai/docs/api/api-reference/responses/create-a-response). Model/provider support still applies; `top_logprobs` on Chat Completions requires `logprobs=true`. The same mapping and validation apply to calls and streams.

OpenRouter applies its [middle-out message transform](https://openrouter.ai/docs/guides/features/message-transforms) by default to endpoints with a context of 8,192 tokens or less. A prompt longer than the context is then shortened and answered without an error. Send `extraBody(Map.of("transforms", List.of()))` to receive a context-length error instead. In Boot properties, `spring.ai.openrouter.chat.extra-body.[transforms]=` sends an empty list, and indexed entries such as `[transforms][0]=middle-out` send a list.

Provider extensions use `OpenRouterChatOptions.builder().providerExtraBody(...)`. An extension `sort` conflicts with a non-null typed `sort` and is rejected rather than overriding it. For example, the extension map can contain `Map.of("sort", Map.of("by", "latency", "partition", "none"))`.

Boot binds root extensions under `spring.ai.openrouter.chat.extra-body` and provider extensions under `spring.ai.openrouter.chat.provider-extra-body`. Preserve wire key spelling with bracket notation in properties:

```properties
spring.ai.openrouter.chat.extra-body.[logprobs]=true
spring.ai.openrouter.chat.extra-body.[top_logprobs]=0
spring.ai.openrouter.chat.provider-extra-body.[zdr]=true
spring.ai.openrouter.chat.provider-extra-body.[sort].by=latency
```

Merge rules:

- Root extension maps merge by key. A runtime entry replaces the default entry, including an explicit null.
- Nested values replace whole objects. They do not merge recursively.
- Typed provider options replace the default typed provider object. `providerExtraBody` merges by key on its own, so default routing restrictions stay unless you override them.
- Maps and lists are snapshotted recursively.
- `false`, `0` and explicit nulls stay on the wire. A null map inherits the defaults.
- Extensions do not enable `n`, `store`, cache breakpoints, web-search requests or server-tool configuration.

Unknown Chat Completions response fields appear in the assistant metadata as `openrouter.message.extensions`, `openrouter.choice.extensions` (including log probabilities) and `openrouter.tool_call.extensions` (keyed by call ID), and in the response metadata as `openrouter.response.extensions`. Streams append annotations and logprob token arrays; other fields keep their latest value. They are never replayed as request fields.

Responses output annotations and opaque tool fields remain available in the existing `openrouter.responses.output_items` snapshot through each item's `rawItem()` / `wireValue()`.
