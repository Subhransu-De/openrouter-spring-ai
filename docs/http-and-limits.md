# HTTP, timeouts and limits

How to customize the HTTP clients, which timeouts apply, and the size limits for responses and streams.

## HTTP customization and timeout ownership

The supported transport extension points are Spring's `RestClient.Builder` request interceptors and `WebClient.Builder` filters, supplied to `OpenRouterApi.builder()`. Boot auto-configuration uses the application-provided builders and clones them before applying OpenRouter settings. Reusing an application singleton builder for an unrelated client retains its original factory, base URL, and headers; OpenRouter credentials and attribution are applied only to the OpenRouter client.

| Configuration                                     | Blocking calls (all model families)                                                                         | Streaming (chat, Responses, images)                                                      |
| ------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Programmatic `OpenRouterApi.Builder.timeout(...)` | Does not configure connect/read timeouts; supply a configured request factory via `restClientBuilder(...)`  | Guards response-header/body waits and idle gaps between SSE events, including keepalives |
| Boot `spring.ai.openrouter.connection.timeout`    | Composes connect/read timeouts with `HttpClientSettings` through the `ClientHttpRequestFactoryBuilder` bean | Applies the same duration as the API's streaming idle guard                              |

Boot installs its factory on the cloned builder, replacing any factory installed directly on that clone. Customize transport selection through `ClientHttpRequestFactoryBuilder` and other settings through `HttpClientSettings`. To own the factory completely, provide an `OpenRouterApi` bean; API auto-configuration backs off. Programmatic `timeout(null)` disables the streaming guard, without changing the supplied connector or blocking factory.

There are no header or timeout fields in model request options. For request-scoped headers, install an interceptor that reads application-owned call context during a blocking invocation, or a filter that reads Reactor Context at subscription time. For example, this filter sends a synthetic correlation header:

```java
WebClient.Builder transport = WebClient.builder()
    .filter((request, next) -> Mono.deferContextual(context -> {
        ClientRequest.Builder outgoing = ClientRequest.from(request);
        context.<String>getOrEmpty("correlation-id")
            .ifPresent(id -> outgoing.header("X-Correlation-ID", id));
        return next.exchange(outgoing.build());
    }));
OpenRouterApi api = OpenRouterApi.builder()
    .apiKey(apiKey)
    .webClientBuilder(transport)
    .build();
api.imagesStream(request)
    .contextWrite(context -> context.put("correlation-id", "synthetic-123"));
```

Use `org.springframework.web.reactive.function.client.ClientRequest` and `reactor.core.publisher.Mono` in this example. Blocking call context must be cleared in `finally`; a thread-local does not propagate across reactive scheduler switches. Keep interceptors/filters safe for concurrent use: cloning a builder does not deep-copy their captured state. Do not mutate shared builders, factories, or default headers to customize a single call. Restrict custom headers to application-approved names; keep credentials and destinations fixed when constructing each client, and do not forward authorization through URI-rewriting filters or cross-origin redirects.

Select a preconfigured API/model instance when calls need different connect/read or stream-idle policies. A caller can also apply Reactor `timeout(duration)` to a returned stream for a per-subscription signal deadline. That operator measures gaps in emitted items (not wire keepalives), cancels its subscription on expiry, and does not reconfigure transport timeouts or other subscriptions. It is not a total-stream deadline. Blocking per-invocation timeout overrides are not provided by this library's portable API.

Automatic per-family client selection is deferred. Applications needing independent chat, embedding, or image transports can construct those models with separate `OpenRouterApi` instances using their existing model builders; Boot otherwise shares one API. Connection-pool metrics, credential-provider abstractions, and typed successful rate-limit snapshots are also deferred. An interceptor/filter may inspect selected successful-response headers before returning the response unchanged; do not consume its body or log credentials. HTTP errors separately expose parsed `Retry-After` through `OpenRouterHttpException.getRetryAfter()`. Successful response headers are not copied into model metadata, and inspecting them does not alter retry behavior.

## Response buffering limits

`spring.ai.openrouter.connection.max-response-body-size` (default `64MB`) limits each blocking response, each SSE event, and the complete JSON fallback for streamed image requests. It does not cap the total size of an SSE stream. Streaming success decoding uses Spring's codec limits and reports `DataBufferLimitException` when exceeded (wrapped in `WebClientResponseException` for chat and Responses).

`spring.ai.openrouter.connection.max-error-body-size` (default `64KB`) bounds HTTP error body retention for both blocking and streaming requests. Oversized errors report `OpenRouterLimitExceededException` with the endpoint, HTTP status, bounded sanitized excerpt, and any recoverable error details. The observed size is a lower bound. Both settings accept 1 through 2147483646 bytes; invalid values fail property binding. Java callers can configure the same limits with `OpenRouterApi.Builder`.

## Chat Completions stream state limits

Chat Completions streaming has a separate per-subscription retained-metadata admission budget, including streams without tools. Configure `OpenRouterChatModel.Builder` with `streamingStateMaxBytes` and `streamingStateMaxChoices`, or Boot properties `spring.ai.openrouter.chat.streaming-state.max-size` and `spring.ai.openrouter.chat.streaming-state.max-choices`. Defaults are 1 MiB and 128 simultaneously active choices. Both values must be positive. These are model-wide settings, not per-request options; they do not affect synchronous calls or Responses.

Before retaining metadata, the mapper charges the UTF-8 JSON size of each nonempty incoming metadata map, including reasoning, reasoning details, refusals, annotations, logprobs, and opaque extensions. Repeated or replacement metadata is charged again. Each active choice also reserves 2,000 bytes for its 500-character diagnostic excerpt. A finishing choice releases all its charges after its final snapshot is built; response-level extension charges last until the subscription ends. This conservative admission budget bounds retained data, not exact JVM heap use. Ordinary text deltas consume no additional budget and remain incremental. Audio assembly, individual SSE events, and tool-call aggregation retain their separate limits. Exceeding the budget cancels upstream with `OpenRouterLimitExceededException`, using `CHAT_STATE_BYTES` or `CHAT_STATE_CHOICES`; replay metadata is never silently truncated. Earlier emitted snapshots retained by the caller are outside this budget.

## Responses stream state limits

Responses streaming reuses the model builder's `toolCallAggregationMaxBytes`, `toolCallAggregationMaxChunks`, and `toolCallAggregationMaxDuration` settings and the Boot properties under `spring.ai.openrouter.chat.tool-call-aggregation`:

| Property       | Default                 | Responses scope                                                                                               |
| -------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------- |
| `max-size`     | `1MB` (1,048,576 bytes) | Cumulative serialized UTF-8 JSON bytes admitted from the first state-bearing event through the terminal event |
| `max-chunks`   | `1024`                  | Events in that interval, and separately the number of retained output items or items in a response snapshot   |
| `max-duration` | `2m`                    | Absolute elapsed time from the first state-bearing event; activity and keepalives do not reset it             |

State-bearing events include output items, text and function-argument fragments, reasoning, refusals, and nonempty response output snapshots. Once started, the budget counts every event, including text, keepalives, and repeated item/terminal snapshots. Byte accounting is therefore conservative: it bounds admitted state, not exact JVM heap usage. Equality is allowed for byte, chunk, and item limits; the duration expires at its deadline. Limits apply independently per subscription to the single Responses output sequence and include all output indexes. They are model-level settings, not per-request options. Synchronous Responses and Chat Completions behavior are unchanged.

Exceeding a limit raises `OpenRouterLimitExceededException` with a `RESPONSES_STATE_*` limit and `/responses` endpoint before the violating event can release tools. State and the deadline subscription are released on completion, error, or cancellation. Normal completed rounds retain their reasoning replay order. These cumulative limits are separate from the connection's per-event codec size and idle timeout: small events or keepalives cannot evade them. Increase the aggregation settings explicitly for larger expected Responses rounds.
