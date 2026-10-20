# Errors and retries

Which exceptions the library raises, which of them Spring AI retries, and how streams are retried.

## Responses failures

Synchronous Responses failures carried over HTTP 200 use `OpenRouterTransientApiException` or `OpenRouterNonTransientApiException`, replacing the legacy `OpenRouterApiException` for this request mode. Inspect their shared `OpenRouterHttpException` contract for sanitized error details and category; the status is derived from the in-band error, and the endpoint is `/responses`. Transient provider, rate-limit, and timeout failures qualify for Spring AI's default retry policy. Authentication, billing, invalid requests, refusals, and unknown failures do not.

## Protocol errors

A response that breaks the wire contract the library enforces raises `OpenRouterProtocolException`, a `NonTransientAiException`, in every request mode, whether the call is synchronous or streaming. This covers undecodable JSON, missing required fields, blank tool names, conflicting stream fragments, embedding count, index, or dimension mismatches, and invalid audio. Catch `OpenRouterProtocolException` to handle all of them. Invalid request options still raise `IllegalArgumentException` before the request is sent.

## Stream retries

Chat Completions, Responses, and image model streams retry only an HTTP 429 rate-limit rejection that arrives before the first decoded event. This deliberately narrow policy avoids replaying requests that may already have run upstream.

![Timeline: attempt 1 gets an HTTP 429 that the RetryTemplate policy permits, waits without blocking, and attempt 2 is accepted. The first decoded event closes the retry window; a later error fails the stream and nothing is replayed. Default backoff starts at 2 seconds, multiplies by 5, caps at 3 minutes, for up to 10 retries; Retry-After can stretch a delay to 5 minutes. HTTP 5xx, connection failures, timeouts, auth or validation errors, and errors inside an accepted stream are not retried. No output does not mean not billed.](images/stream-retry.svg)

Streaming reuses the model's `RetryTemplate` limit, backoff, exception predicate, and timeout, including the template supplied by Boot's `spring.ai.retry.*` configuration. The defaults in the diagram are Spring AI 2.0.1's, used when no template is supplied. An invalid `Retry-After`, or one over 5 minutes, falls back to the configured backoff. A delay that cannot fit within the remaining policy timeout ends retries with the original typed exception. Cancellation cancels pending retries. Blocking `RetryListener` callbacks are not invoked; use transport filters to count attempts. Direct `OpenRouterApi` streaming calls make one attempt. Garage's configured template uses two retries with a 200 ms delay.

## Malformed stream events

Chat, Responses, and image SSE streams reject malformed JSON and non-object data payloads with `OpenRouterProtocolException` and cancel the HTTP body. This also applies after partial output. SSE comments and empty heartbeat events are ignored. Chat and image streams terminate at `[DONE]`; Responses requires its own protocol terminal event.
