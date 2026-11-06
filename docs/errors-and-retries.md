# Errors and retries

Which exceptions the library raises, which of them Spring AI retries, and how streams are retried.

## What to catch

| Situation                                                    | Exception                                                                                  | Modes                                | Retried by Spring AI                                             |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------ | ------------------------------------ | ---------------------------------------------------------------- |
| HTTP error status                                            | `OpenRouterTransientApiException` or `OpenRouterNonTransientApiException`                  | Any                                  | Transient only. Streams retry only a 429 before the first event. |
| Error in a successful (HTTP 200) body                        | The same two exceptions, with the status derived from the error and endpoint `/responses`  | Responses                            | Transient only, in synchronous calls.                            |
| Error in a successful (HTTP 200) body                        | `OpenRouterTransientApiException` if transient, otherwise `OpenRouterApiException`         | Chat Completions, embeddings, images | Transient only, in synchronous calls.                            |
| Error in one choice (`choices[n].error`)                     | `OpenRouterTransientChoiceException` or `OpenRouterNonTransientChoiceException`            | Chat Completions                     | Transient only, in synchronous calls.                            |
| Response breaks the wire contract                            | `OpenRouterProtocolException` (see [Protocol errors](#protocol-errors))                    | Any                                  | No.                                                              |
| Response or stream ends before the protocol completes        | `OpenRouterTruncatedResponseException`                                                     | Any                                  | No. Earlier tool calls may already have had side effects.        |
| Blocking-body, error-body or configurable stream-state limit | `OpenRouterLimitExceededException` (see [HTTP, timeouts and limits](http-and-limits.md))   | Any                                  | No.                                                              |
| One SSE event over the buffer limit                          | `DataBufferLimitException`, wrapped in `WebClientResponseException` for chat and Responses | Streams                              | No.                                                              |
| Chat audio limit                                             | `NonTransientAiException`                                                                  | Chat Completions                     | No.                                                              |
| Invalid option or input, including inline attachment limits  | `IllegalArgumentException`, before the request is sent                                     | Any                                  | No.                                                              |

`OpenRouterTransientApiException`, `OpenRouterNonTransientApiException` and `OpenRouterLimitExceededException` implement the sealed `OpenRouterHttpException`, which exposes sanitized error details, a category and `getRetryAfter()`. `OpenRouterApiException` is separate and has its own getters, so a `catch` for it does not see the transient case. The two choice exceptions implement `OpenRouterChoiceFailure`. Retained error bodies and diagnostic fields redact the configured API key, including its percent-encoded and JSON `\u`-escaped forms.

An error in an HTTP 200 body is retried only if it is transient: provider, rate-limit and timeout failures. Authentication, billing, invalid-request, refusal and unknown failures are not. In Chat Completions, embeddings and images, the error takes precedence over any data in the same body, and response mapping runs inside the retry boundary.

Chat `stream()` reports an invalid option as an error on subscription instead of throwing when called. Calls and streams both record it on the chat observation.

## Protocol errors

`OpenRouterProtocolException` is a `NonTransientAiException`. It applies in every request mode, for synchronous calls and streams. It covers undecodable JSON, missing required fields, blank tool names, conflicting stream fragments, embedding count, index or dimension mismatches, and invalid audio. In chat, Responses and image SSE streams, malformed JSON and non-object data payloads raise it and cancel the HTTP body, even after partial output.

## Retries

Synchronous calls retry transient failures under the model's `RetryTemplate`, which Boot builds from `spring.ai.retry.*`. It also retries connection errors (`ResourceAccessException`). `max-attempts` counts retries after the first call, so `3` allows up to 4 calls:

```properties
spring.ai.retry.max-attempts=3
spring.ai.retry.backoff.initial-interval=1s
```

The Boot defaults are 10 retries, a 2-second initial interval, `backoff.multiplier=5` and a 3-minute `backoff.max-interval`. Spring AI uses the same numbers when no template is supplied.

The library classifies HTTP errors itself, as the table above shows. So only the keys that build the `RetryTemplate` apply:

| Key                                                                                                      | Applies to OpenRouter models                                                            |
| -------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `spring.ai.retry.max-attempts`, `backoff.initial-interval`, `backoff.multiplier`, `backoff.max-interval` | Yes, through the `RetryTemplate`.                                                       |
| `spring.ai.retry.on-http-codes`, `exclude-on-http-codes`, `on-client-errors`                             | No. They configure Spring AI's `ResponseErrorHandler`, which this library does not use. |

Spring AI's `RetryTemplate` bean backs off when the application declares any `RetryTemplate`, so a single application `RetryTemplate`, even one meant for other code, becomes the OpenRouter retry policy. With two or more and no `@Primary`, the models use Spring AI's built-in default policy. In that case `spring.ai.retry.*` does not apply at all: Spring AI's own `RetryTemplate` bean, which reads those keys, is not created when the application declares templates, and the built-in default does not read them. Declare the template you want for AI calls as `@Primary`, or build the models yourself.

When `on-http-codes` or `exclude-on-http-codes` is non-empty, or `on-client-errors` is `true`, startup logs one message naming them. The keys can still apply to another provider in the same application, so the message never stops startup. It is an INFO message when the application also uses a Spring AI provider that reads these keys: DeepSeek chat, ElevenLabs, Mistral chat, embedding, moderation or OCR, or Ollama. That means the provider's starter is present and its `spring.ai.model.*` selector does not rule it out. The check cannot tell whether the application replaced that provider's client, so the INFO message says the keys may still configure another client. Otherwise it is a warning.

## Stream retries

Streams are stricter: chat, Responses and image streams retry only an HTTP 429 that arrives before the first decoded event. This avoids replaying requests that may already have run upstream.

![Timeline: attempt 1 gets an HTTP 429 that the RetryTemplate policy permits, waits without blocking, and attempt 2 is accepted. The first decoded event closes the retry window; a later error fails the stream and nothing is replayed. Default backoff starts at 2 seconds, multiplies by 5, caps at 3 minutes, for up to 10 retries; Retry-After can stretch a delay to 5 minutes. HTTP 5xx, connection failures, timeouts, auth or validation errors, and errors inside an accepted stream are not retried. No output does not mean not billed.](images/stream-retry.svg)

Streams reuse the model's `RetryTemplate` limit, backoff, predicate and timeout. The diagram shows the defaults above. Further rules:

- An invalid `Retry-After`, or one over 5 minutes, falls back to the configured backoff.
- A delay that does not fit in the remaining policy timeout ends retries with the original typed exception.
- Blocking `RetryListener` callbacks are not invoked. Use transport filters to count attempts.
- Direct `OpenRouterApi` streaming calls make one attempt.
