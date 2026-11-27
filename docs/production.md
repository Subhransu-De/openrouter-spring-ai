# Production

What the code does not tell you about retries, billing, timeouts, HTTP customization and monitoring.

## Retries and billing

The library classifies HTTP errors itself. Only the `spring.ai.retry.*` keys that build the `RetryTemplate` apply: `max-attempts` and the `backoff.*` keys. `on-http-codes`, `exclude-on-http-codes` and `on-client-errors` configure Spring AI's `ResponseErrorHandler`, which this library does not use. `max-attempts` counts retries after the first call, so `3` allows up to 4 calls.

Spring AI's `RetryTemplate` bean backs off when the application declares any `RetryTemplate`. A single application template, even one meant for other code, becomes the OpenRouter retry policy. With two or more and no `@Primary`, the models use Spring AI's built-in default and ignore `spring.ai.retry.*`. Declare the template you want for AI calls as `@Primary`.

An HTTP 5xx status that the library does not list, such as 501 or 520 to 523, is not retried, because the library does not guess whether an unlisted server error is temporary. To retry it, add your own rule on `getStatusCode()`.

Streams retry only an HTTP 429 that arrives before the first decoded event. Any later error, transient or not, fails the stream, because the request may already have run, and been billed, upstream. No output does not mean not billed. After a truncated response, earlier tool calls may already have had side effects.

![Timeline: attempt 1 gets an HTTP 429 that the RetryTemplate policy permits, waits without blocking, and attempt 2 is accepted. The first decoded event closes the retry window; a later error fails the stream and nothing is replayed. Default backoff starts at 2 seconds, multiplies by 5, caps at 3 minutes, for up to 10 retries; Retry-After can stretch a delay to 5 minutes. HTTP 5xx, connection failures, timeouts, auth or validation errors, and errors inside an accepted stream are not retried. No output does not mean not billed.](images/stream-retry.svg)

The library retries no `OpenRouterProtocolException`. `EMPTY_BODY` is the kind most likely to come from a flaky upstream, so it is the one worth a retry of your own.

A credit limit on the OpenRouter API key is the only control that stops a request before it is billed.

Error details redact the configured API key. A failed stream choice keeps at most 500 characters of partial output, cut before redaction, so a key that crosses the cut can leave its first characters.

## Timeouts

`spring.ai.openrouter.connection.timeout` sets the blocking read timeout and the stream idle guard. It does not inherit `spring.http.clients.read-timeout`, because a generation can take longer than a global timeout tuned for ordinary REST calls.

Streams still get Boot's `spring.http.clients.read-timeout` through their `WebClient.Builder` connector, and Reactor Netty uses it as the longest allowed gap between reads. A global read timeout shorter than `connection.timeout` therefore ends a slow stream first. Leave the global value unset, or at least as long as `connection.timeout`.

In a WebFlux application, Boot creates no `ClientHttpRequestFactoryBuilder` bean and ignores `spring.http.clients.imperative.*`, so blocking OpenRouter calls use `ClientHttpRequestFactoryBuilder.detect()` unless you define the bean yourself.

Reactor `timeout(duration)` on a returned `Flux` measures gaps between emitted items, not wire keepalives. It is not a total-stream deadline.

## HTTP customization

The application's `JsonMapper` settings apply to the wire format. A global naming strategy can rename wire fields that the DTOs do not name explicitly, such as `model`.

Rules for interceptors, filters and `OpenRouterRestClientCustomizer` or `OpenRouterWebClientCustomizer` beans:

- Pass per-call values for blocking calls through call context that you clear in `finally`. A thread-local does not propagate across reactive scheduler switches; for streams, read the Reactor Context at subscription time.
- Make them safe for concurrent use. Cloning a builder does not deep-copy their captured state.
- Customize a single call through call context, and leave shared builders, factories and default headers unchanged.
- Use only application-approved header names. Keep credentials and destinations fixed when you construct each client. Do not forward authorization through URI-rewriting filters or cross-origin redirects.
- For a header on one chat request, set `customHeaders` on `OpenRouterChatOptions` instead (see [Custom headers](chat.md#custom-headers)). It rejects the headers that the client sets itself, so a request cannot replace the key or the attribution. Error details redact only the configured key.

## Connection details

The auto-configured `OpenRouterApi` reads its base URL and API key from an `OpenRouterConnectionDetails` bean, a Spring Boot [`ConnectionDetails`](https://docs.spring.io/spring-boot/reference/features/dev-services.html). By default the bean comes from `spring.ai.openrouter.base-url` and `spring.ai.openrouter.api-key`. Define your own bean, for example in a test that points OpenRouter at a mock server, and the auto-configuration uses it instead of these two properties. The other `spring.ai.openrouter.*` properties still apply.

```java
@Bean
OpenRouterConnectionDetails openRouterConnectionDetails(WireMockServer server) {
  return new OpenRouterConnectionDetails() {
    public String getBaseUrl() { return server.baseUrl() + "/api/v1"; }
    public String getApiKey() { return "test-key"; }
  };
}
```

A missing or empty API key still fails startup. An application-defined `OpenRouterApi` bean does not read the connection details.

## Stream limits

The provider decides how finely it splits tool-call arguments. A chunk with OpenRouter's usual id, model and provider fields serializes to about 250 to 300 bytes, so the 1 MB `tool-call-aggregation.max-size` usually applies after 3,500 to 4,200 chunks, before `max-chunks`. If your tools expect larger arguments, raise both, for example `max-chunks=32768` with `max-size=4MB`.

When several choices buffer tool calls at once, a merged tool call leaves out usage older than what the stream already emitted, so its response can report no usage.

## Not supported

- Per-invocation timeout overrides for blocking calls.
- Automatic per-family client selection. Build each model with its own `OpenRouterApi` instead.
- Connection-pool metrics.
- Credential-provider abstractions.
- Rate limits in `ChatResponseMetadata`. OpenRouter sends no rate-limit headers on successful chat responses, so `getRateLimit()` stays empty. Read the key's limits and remaining credit from `GET /api/v1/key`.

## Cost and monitoring

- `OpenRouterUsage.getCost()` is the total that OpenRouter charged, in credits. `getCostDetails()` holds what the upstream provider charged.
- OpenRouter does not price reasoning separately: reasoning tokens are billed as completion tokens.
- A missing cost or count is `null`, which differs from an explicit zero. OpenRouter reports usage in Chat Completions only when `spring.ai.openrouter.chat.include-usage=true`.
- The starter chooses no monitoring backend. Metrics need a `MeterRegistry` and an exporter; traces need a Micrometer `Tracer` bridge and exporter.
- Prompt and completion logging (`spring.ai.chat.observations.log-prompt`, `log-completion`) may log sensitive data.
- The library does not copy the current Micrometer trace into OpenRouter's `trace`. To correlate OpenRouter's records with your traces, pass the span IDs per request:

```java
TraceContext context = tracer.currentSpan().context(); // null-check currentSpan() outside a span
String answer = client.prompt().user(question)
    .options(OpenRouterChatOptions.builder()
        .trace(OpenRouterTrace.builder().traceId(context.traceId()).parentSpanId(context.spanId()).build()))
    .call().content();
```
