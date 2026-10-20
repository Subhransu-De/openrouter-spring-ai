# Errors and retries

Which exceptions the library raises, which of them Spring AI retries, and how streams are retried.

## Responses failures

Synchronous Responses failures carried over HTTP 200 use `OpenRouterTransientApiException` or `OpenRouterNonTransientApiException`, replacing the legacy `OpenRouterApiException` for this request mode. Inspect their shared `OpenRouterHttpException` contract for sanitized error details and category; the status is derived from the in-band error, and the endpoint is `/responses`. Transient provider, rate-limit, and timeout failures qualify for Spring AI's default retry policy. Authentication, billing, invalid requests, refusals, and unknown failures do not.

## Protocol errors

A response that breaks the wire contract the library enforces raises `OpenRouterProtocolException`, a `NonTransientAiException`, in every request mode, whether the call is synchronous or streaming. This covers undecodable JSON, missing required fields, blank tool names, conflicting stream fragments, embedding count, index, or dimension mismatches, and invalid audio. Catch `OpenRouterProtocolException` to handle all of them. Invalid request options still raise `IllegalArgumentException` before the request is sent.

## Stream retries

Chat Completions, Responses, and image model streams retry only actual HTTP 429 responses classified as rate-limit rejections, and only when the model's configured `RetryTemplate` policy also permits the exception. HTTP 5xx responses, connection failures, timeouts, authentication and validation failures, and errors inside an accepted stream are not retried. No output does not prove that an upstream request was not executed or billed. This deliberately narrower policy avoids replaying ambiguous requests. Tool execution remains with `ToolCallingAdvisor`.

Streaming reuses the template's retry limit, backoff, exception predicate, and timeout, including the template supplied by Boot's `spring.ai.retry.*` configuration. Without a supplied template, Spring AI 2.0.1's default allows ten retries after the first attempt, starting at 2 seconds with a multiplier of 5 and a 3-minute delay cap. `Retry-After` can extend a delay up to 5 minutes; invalid or larger values fall back to configured backoff. A delay that cannot fit within the remaining policy timeout ends retries with the original typed exception. Delays are nonblocking, and cancellation cancels pending retries. Blocking `RetryListener` callbacks are not invoked. Use transport filters to count attempts; one model observation covers the whole subscription and records the final response or error once.

The first decoded event closes retry eligibility, even if it contains only usage or metadata or is later filtered by a mapper. Partially delivered streams are never replayed. Direct `OpenRouterApi` streaming calls remain single-attempt. Garage's configured template uses two retries with a 200 ms delay; the stricter HTTP 429 streaming eligibility rule still applies.

## Malformed stream events

Chat, Responses, and image SSE streams reject malformed JSON and non-object data payloads with `OpenRouterProtocolException` and cancel the HTTP body. This also applies after partial output. SSE comments and empty heartbeat events are ignored. Chat and image streams terminate at `[DONE]`; Responses requires its own protocol terminal event.
