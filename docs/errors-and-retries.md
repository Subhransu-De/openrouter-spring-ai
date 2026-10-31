# Errors and retries

Which exceptions the library raises, which of them Spring AI retries, and how streams are retried.

## What to catch

| Situation                                             | Exception                                                                                 | Modes                    | Retried by Spring AI                                             |
| ----------------------------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------ | ---------------------------------------------------------------- |
| HTTP error status                                     | `OpenRouterTransientApiException` or `OpenRouterNonTransientApiException`                 | Any                      | Transient only. Streams retry only a 429 before the first event. |
| Error in a successful (HTTP 200) body                 | The same two exceptions, with the status derived from the error and endpoint `/responses` | Responses                | Transient only, in synchronous calls.                            |
| Error in a successful (HTTP 200) body                 | `OpenRouterApiException`                                                                  | Chat Completions, images | No.                                                              |
| Error in one choice (`choices[n].error`)              | `OpenRouterTransientChoiceException` or `OpenRouterNonTransientChoiceException`           | Chat Completions         | Transient only, in synchronous calls.                            |
| Response breaks the wire contract                     | `OpenRouterProtocolException`                                                             | Any                      | No.                                                              |
| Response or stream ends before the protocol completes | `OpenRouterTruncatedResponseException`                                                    | Any                      | No. Earlier tool calls may already have had side effects.        |
| Local size, count or time limit exceeded              | `OpenRouterLimitExceededException` (see [HTTP, timeouts and limits](http-and-limits.md))  | Any                      | No.                                                              |
| Invalid option or input                               | `IllegalArgumentException`, before the request is sent                                    | Any                      | No.                                                              |

The API exceptions share `OpenRouterHttpException`, which exposes sanitized error details and a category.

## In-band Responses errors

A Responses call can report a failure inside an HTTP 200 body. Transient provider, rate-limit and timeout failures qualify for Spring AI's default retry policy. Authentication, billing, invalid-request, refusal and unknown failures do not.

## Protocol errors

A response that breaks the wire contract the library enforces raises `OpenRouterProtocolException`, a `NonTransientAiException`. It applies in every request mode, for synchronous calls and streams. It covers undecodable JSON, missing required fields, blank tool names, conflicting stream fragments, embedding count, index or dimension mismatches, and invalid audio. Catch it to handle all of them.

## Retries

Synchronous calls retry transient failures under the model's `RetryTemplate`. Boot builds that template from `spring.ai.retry.*`, and it also retries connection errors (`ResourceAccessException`). For example:

```properties
spring.ai.retry.max-attempts=3
spring.ai.retry.backoff.initial-interval=1s
```

`spring.ai.retry.backoff.multiplier` and `spring.ai.retry.backoff.max-interval` tune the backoff. Spring AI 2.0.2 passes `max-attempts` to the policy as the maximum number of retries.

## Stream retries

Streams are stricter than synchronous calls. Chat Completions, Responses and image model streams retry only an HTTP 429 rate-limit rejection that arrives before the first decoded event. This narrow rule avoids replaying requests that may already have run upstream.

![Timeline: attempt 1 gets an HTTP 429 that the RetryTemplate policy permits, waits without blocking, and attempt 2 is accepted. The first decoded event closes the retry window; a later error fails the stream and nothing is replayed. Default backoff starts at 2 seconds, multiplies by 5, caps at 3 minutes, for up to 10 retries; Retry-After can stretch a delay to 5 minutes. HTTP 5xx, connection failures, timeouts, auth or validation errors, and errors inside an accepted stream are not retried. No output does not mean not billed.](images/stream-retry.svg)

Streams reuse the model's `RetryTemplate` limit, backoff, exception predicate and timeout, including the template Boot builds from `spring.ai.retry.*`. The diagram shows Spring AI 2.0.2's defaults, which apply when no template is supplied. Further rules:

- An invalid `Retry-After`, or one over 5 minutes, falls back to the configured backoff.
- A delay that does not fit in the remaining policy timeout ends retries with the original typed exception.
- Cancellation cancels pending retries.
- Blocking `RetryListener` callbacks are not invoked. Use transport filters to count attempts.
- Direct `OpenRouterApi` streaming calls make one attempt.

## Malformed stream events

Chat, Responses and image SSE streams reject malformed JSON and non-object data payloads with `OpenRouterProtocolException` and cancel the HTTP body. This also applies after partial output. SSE comments and empty heartbeat events are ignored. Chat and image streams end at `[DONE]`. Responses requires its own protocol terminal event.
