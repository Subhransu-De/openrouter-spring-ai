# Changelog

Notable changes in each release. The full notes for each release are on the [Releases page](https://github.com/Subhransu-De/openrouter-spring-ai/releases).

## Unreleased

### Upgrade notes

- Public names now follow OpenRouter's terms and the library's own patterns. Where an item does not say that the old name still works, code that uses the old name no longer compiles, and a Boot key or value with the old name no longer binds:
  - `OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS` and `OPENAI_RESPONSES` are `CHAT_COMPLETIONS` and `RESPONSES`, because the requests go to OpenRouter. Set `spring.ai.openrouter.chat.request-mode` to `chat-completions` or `responses`. Validation messages name the modes `CHAT_COMPLETIONS` and `RESPONSES`, so tests or alerts that match the old message text need updating.
  - The sealed interface `OpenRouterHttpException` is `OpenRouterHttpFailure`, as with `OpenRouterFailure`, because `catch` cannot name an interface. The record that was `OpenRouterHttpFailure` is `OpenRouterHttpFailureDetails`, and `getFailure()` is `getFailureDetails()`.
  - `OpenRouterImageOptions.Builder.providerOptions(...)` and `getProviderOptions()` are `provider(...)` and `getProvider()`, and `spring.ai.openrouter.image.provider-options.*` is `spring.ai.openrouter.image.provider.*`. They still work in this release but are deprecated for removal. Unlike the typed `provider` on chat and embedding options, the image `provider` is a raw map that is sent as the wire `provider` object, with wire field names; settings for one upstream provider go under `options.<provider-slug>`. Boot binds only string values faithfully into this map, so set lists and booleans in Java.
  - `OpenRouterApi.Builder.applicationTitle(...)` and `applicationCategories(...)` are `appTitle(...)` and `appCategories(...)`, as in `spring.ai.openrouter.app.*`. The old methods still work in this release but are deprecated for removal.
  - The image response metadata keys `openrouter.usage` and `openrouter.event_type` are `openrouter.image.usage` and `openrouter.image.event_type`. Read them with `OpenRouterUsage.from(imageResponse)` or the `OpenRouterMetadata.IMAGE_USAGE` and `IMAGE_EVENT_TYPE` constants.
  - In `api.dto`, `StreamError` is `ResponseError`, because non-stream responses use it too, `Function` is `FunctionDefinition`, so it no longer clashes with `java.util.function.Function`, and `ImagesStreamEvent.ERROR_EVENT` is `ERROR`, like `PARTIAL_IMAGE` and `COMPLETED`.
  - `OpenRouterToolExecutionExceptionProcessor.DEFAULT_FAILURE_PAYLOAD` is `DEFAULT_FAILURE_RESULT`. Its value is unchanged.
  - `api.errors.OpenRouterApiException` is `OpenRouterInBandErrorException`, because it is not the parent of `OpenRouterTransientApiException` and `OpenRouterNonTransientApiException` and catches neither. It is still the error inside an HTTP 200 Chat Completions or image body or stream, and its behavior is unchanged.
  - `OpenRouterChatModel`, `OpenRouterEmbeddingModel` and `OpenRouterImageModel` builders take `options(...)`, as Spring AI's own model builders do. `defaultOptions(...)` still works in this release but is deprecated for removal.
  - The old `spring.ai.openrouter.image.provider-options` key still binds, logs a warning, and fails startup when `spring.ai.openrouter.image.provider` is set too. The old `request-mode` values fail at startup with a binding error that names the key.

- Boot auto-configuration now looks up a `JsonMapper` bean, not any `ObjectMapper`. With Boot's Jackson auto-configuration, its `@Primary` `JsonMapper` was already the one used, so nothing changes. An application that defines its own `JsonMapper` and also has an `XmlMapper` or `CBORMapper` no longer fails at startup. A plain `ObjectMapper` bean is no longer picked up; declare it as a `JsonMapper`. See [HTTP customization](docs/http-and-limits.md#http-customization-and-timeout-ownership).
- When `spring.http.clients.connect-timeout` is set and `spring.ai.openrouter.connection.timeout` is not, OpenRouter blocking calls now connect with Boot's value, as streams already did, instead of the 2m default. An explicit `connection.timeout` still bounds blocking connects. The new `spring.ai.openrouter.connection.connect-timeout` overrides both. `connection.timeout` stays the blocking read timeout and the stream idle guard. `OpenRouterConnectionProperties.getTimeout()` never returns `null`: `setTimeout(null)` now restores the 2m default instead of turning the stream guard off. Use `OpenRouterApi.Builder.timeout(null)` for that. See [Timeouts](docs/http-and-limits.md#timeouts).
- `OpenRouterChatOptions.setOutputSchema`, `setToolCallbacks` and `setToolContext`, the five `OpenRouterEmbeddingOptions` setters, and `copy()` on the chat, embedding and image options are removed, as in Spring AI 2.0, whose options are immutable. RC code that calls them no longer compiles. Replace `options.setModel(m)` with `options = options.mutate().model(m).build()`, and `options.copy()` with `options.mutate().build()`. See [Option snapshots](docs/chat-options.md#option-snapshots).
- With two or more `RetryTemplate`, `ObservationRegistry` or `JsonMapper` beans and no `@Primary`, the auto-configuration now falls back to Spring AI's default retry policy, a no-op registry or a default `JsonMapper`, as the official Spring AI providers do, instead of failing at startup. When it skips an application `JsonMapper` or plain `ObjectMapper` this way, it logs a warning.
- An embedding call or `dimensions()` probe with no model now fails with `IllegalArgumentException` before any request, as image calls already did. OpenRouter requires a model for embeddings, so these requests could not succeed. Set `spring.ai.openrouter.embedding.model`, pass a model in the request options, or set `spring.ai.model.embedding=none`.
- A tool failure whose cause is not a `RuntimeException`, such as an `IOException` or an `Error`, now propagates from `OpenRouterToolExecutionExceptionProcessor` instead of becoming the fixed failure result, and so does Spring Security's `ClientAuthorizationException` when it is on the classpath, as with Spring AI's default processor. Applications that relied on that result for checked exceptions should catch them in the tool. See [Tool failure policy](docs/tool-calling.md#tool-failure-policy).
- The auto-configuration classes are `final`. They were never supported API.
- When `outputSchema` and `responseFormat` are both set, the output schema is now sent and a warning names both options. Before, `responseFormat` won without a word, so a default `spring.ai.openrouter.chat.response-format` silently discarded the schema that `ChatClient.entity(...)` supplies. A request that sets either option now replaces both defaults, as in Spring AI's OpenAI options, where both write one field. Configurations that set both in the same place and relied on `responseFormat` should drop `output-schema`. See [Structured output](docs/request-modes.md#structured-output).
- `toolChoice` `required` or a named function now fails before the request when the request has no tools, and a named function must name one of the request's tools. `auto` and `none` are left out of a request without tools instead of being sent. A Boot default `tool-choice=required` therefore fails each request that has no tools. See [Tool choice](docs/request-modes.md#tool-choice).
- Some invalid options now fail when the options are built, so a Boot default fails at startup instead of at the first request: a blank `response-format.schema`, a `tool-choice` string other than `auto`, `none` or `required`, and a null element in `models`, `stopSequences`, `modalities`, provider `order`, `ignore` or `quantizations`, or image `inputReferences`.
- `modalities` are lower-cased when the options are built, so `Image` is sent as `image` and the audio-output checks recognize `Audio`.
- Option error messages now name the option, echo the value and list what is allowed. Tests that match the old texts, such as `Unsupported or reserved extraBody key`, `Unsupported verbosity value` or `Unsupported input media MIME type`, need updating. A non-numeric `top_logprobs`, `preferred_min_throughput`, `preferred_max_latency` or `max_price` entry is now an `IllegalArgumentException` that names the key, not a bare `NumberFormatException`.
- The new enum overloads, such as `effort(OpenRouterReasoningEffort)` and `modalities(OpenRouterModality...)`, make a literal `null` argument ambiguous: `builder.effort(null)` or `modalities(null)` no longer compiles. Cast it, as in `effort((String) null)`, or leave the call out.
- Timeouts, connection failures and connections that close mid-response now raise `OpenRouterTransportException` (kind `TIMEOUT`, `CONNECT` or `IO`) instead of `ResourceAccessException` for blocking calls, and instead of Reactor's `TimeoutException`, `WebClientRequestException` or a `WebClientResponseException` wrapping an I/O error for streams. The original exception is the cause, so code that walks the cause chain still finds it, but a `catch (ResourceAccessException ex)` or a Reactor `onErrorResume(TimeoutException.class, ...)` no longer matches. Retries do not change: the model's retry policy still decides on the original exception, so Spring AI's default policy retries a blocking transport failure as before, a policy without `ResourceAccessException` still does not, and streams still do not retry these failures. A `RetryListener` now receives the `OpenRouterTransportException`. See [Errors and retries](docs/errors-and-retries.md).
- An SSE event or streamed image JSON body over `max-response-body-size` now raises `OpenRouterLimitExceededException` with the new `STREAMING_EVENT_BYTES` limit instead of `DataBufferLimitException` (or a `WebClientResponseException` wrapping it). The chat audio limits now raise `OpenRouterLimitExceededException` with the new `CHAT_AUDIO_BYTES` and `CHAT_AUDIO_CHOICES` limits instead of a plain `NonTransientAiException`. Both are still non-transient and not retried. An exhaustive `switch` over `OpenRouterLimitExceededException.Limit` without a `default` no longer compiles, and `Limit.getProperty()` is now nullable: it returns `null` for the fixed audio limits.
- Exception messages now include the category, and in-band and choice failures also the endpoint, the derived status and the choice index, for example `OpenRouter /chat/completions request failed with status 503 SERVICE_UNAVAILABLE, category PROVIDER_UNAVAILABLE`. Code or log alerts that match the full message text need updating. Provider text is still kept out of messages.
- `getErrorDetails()` on `OpenRouterHttpFailure`, `OpenRouterInBandErrorException`, `OpenRouterChoiceFailure` and the choice exceptions, and `OpenRouterErrorDetails.metadata()`, never return `null`. Passing `null` details to a constructor stores empty details with the `UNKNOWN` category, and absent metadata is a `MissingNode`, which Jackson still writes as `null`. Existing null checks become dead code. `OpenRouterChoiceFailure` is not sealed, so an implementation outside the library must not return `null` either.

### Added

- `OpenRouterFailure`, implemented by every library failure (HTTP, in-band, choice, transport, protocol, truncated and limit exceptions), exposes `getCategory()` and `isTransient()`, so one `catch (RuntimeException ex)` and one `instanceof` handle them all. `isTransient()` reports whether the failure is temporary, also for an `OpenRouterInBandErrorException` raised inside a stream, which is never a `TransientAiException`. See [Errors and retries](docs/errors-and-retries.md#what-to-catch).
- `OpenRouterHttpFailure.getRetryDelay()` returns the `Retry-After` delay without the two-step null check.
- `OpenRouterLimitExceededException.isStreamStateLimit()` tells a limit on stream state the library assembles from a limit on an HTTP body or SSE event, instead of testing `getStatusCode() == null`.
- `OpenRouterProtocolException.getKind()` (`EMPTY_BODY`, `DECODE`, `MISSING_FIELD` or `CONTRACT`) and `getEndpoint()` tell an empty body from a broken contract without matching message text. The existing constructors report `CONTRACT` with no endpoint. See [Protocol errors](docs/errors-and-retries.md#protocol-errors).
- `OpenRouterErrorDetails.empty()` and `OpenRouterChoiceErrorDetails.empty()` return details that carry nothing.
- [Errors and retries](docs/errors-and-retries.md#what-to-catch) documents errors inside an accepted stream, transport failures, and that an unlisted 5xx status, such as 501 or 520 to 523, has the `UNKNOWN` category and is not retried. That policy is unchanged.
- `OpenRouterReasoningOptions.builder()` and `OpenRouterProviderPreferences.builder()` set only the fields you name, instead of a positional constructor with `null` for the rest. The constructors stay. See [Routing](docs/chat-options.md#routing-fallbacks-and-attribution).
- `OpenRouterReasoningEffort`, `OpenRouterProviderSort`, `OpenRouterDataCollection`, `OpenRouterQuantization` and `OpenRouterModality` hold the values OpenRouter documents, with builder overloads that take them. The String setters stay for values OpenRouter adds later. An undocumented value is still sent, and each distinct value is logged once as a warning. IDE hints list the values for the matching `spring.ai.openrouter.*` keys. See [Routing](docs/chat-options.md#routing-fallbacks-and-attribution).
- `OpenRouterUsage.getCostDetails()` returns OpenRouter's cost breakdown (upstream total, prompt cost, completion cost and server-tool cost) for chat, embeddings and images in both request modes, and `getByok()` reports whether the request used your own provider key. See [Observability](docs/observability.md).
- Typed readers replace `instanceof` checks, casts and string keys when reading responses. Apart from the two image keys renamed in the upgrade notes, every metadata key keeps its name and value shape.
  - `OpenRouterUsage.from(...)` reads the usage of a `ChatResponse`, `EmbeddingResponse` or `ImageResponse` and never returns `null`. Without provider usage, token counts are `0` and other values `null`. See [Observability](docs/observability.md).
  - `OpenRouterMetadata` makes every metadata key public (including `IMAGE_USAGE` for `openrouter.image.usage`) and reads reasoning, refusals, the native finish reason, the Responses status and the streamed choice index (`openrouter.choice_index`, `0` when absent) without casts. See [Request modes](docs/request-modes.md#response-metadata).
  - `OpenRouterFinishReason.from(generation)` reads the upper-case mapped and lower-case provider spellings of a finish reason; `getFinishReason()` is unchanged.
  - `OpenRouterAudioOutput.from(message)` reads the output audio transcript, format, id and expiry as a record. See [Chat audio output](docs/multimodal.md#chat-audio-output).
  - `OpenRouterImageGenerationMetadata.from(generation)` reads image metadata without a cast and `isPartial()` tells a streamed preview from a completed image. `OpenRouterMedia.bytes(media)` returns the bytes of generated images (base64 data URLs) and output audio (raw bytes).
- `OpenRouterRestClientCustomizer` and `OpenRouterWebClientCustomizer` beans customize only the OpenRouter clients, after Boot's global customizers and before OpenRouter's credentials and base URL. See [HTTP customization](docs/http-and-limits.md#http-customization-and-timeout-ownership).
- `spring.ai.openrouter.connection.connect-timeout` sets an OpenRouter-only connect timeout for blocking calls.
- Startup warns when OpenRouter's tool failure policy also handles another provider's chat model, which happens next to another provider starter with `spring.ai.model.chat` unset.
- The core module registers its wire-type runtime hints in `META-INF/spring/aot.factories`, so native images get them without the auto-configuration.
- `OpenRouterChatModel.getOptions()` now declares `OpenRouterChatOptions`, so OpenRouter-only defaults need no cast, and `OpenRouterEmbeddingModel` and `OpenRouterImageModel` gain a typed `getOptions()`.
- Every `spring.ai.openrouter.*` key, nested provider, reasoning, audio and response-format keys included, has an IDE description, and the jar ships `META-INF/spring-autoconfigure-metadata.properties` so Boot can skip the auto-configurations by class condition without loading them.
- Startup reports `spring.ai.retry.on-http-codes`, `exclude-on-http-codes` or `on-client-errors` when set, because OpenRouter models do not use them. It is an INFO message when a Spring AI provider that reads those keys, such as Mistral or Ollama, is active, and a warning otherwise. See [Retries](docs/errors-and-retries.md#retries).
- [Getting started](docs/getting-started.md#install-and-make-a-first-call) explains what happens next to another provider starter: with a `spring.ai.model.*` selector unset, both providers create a model of that type, and consumers that take a single `ChatModel` or `EmbeddingModel` fail.

### Changed

- In Responses mode, setting both `maxTokens` and `maxCompletionTokens` to different values logs a warning. `maxCompletionTokens` is still sent as `max_output_tokens`; it is not rejected, because options merge field by field and a default of one with a request value of the other is a valid combination.
- The `imageConfig` property description now matches its behavior: `image_config` is sent whenever it is set, not only with the image modality.
- Embedding dimension discovery no longer holds a monitor during its HTTP call, so a virtual thread that runs it no longer pins its carrier thread on JDK 21 to 23.
- The starter no longer re-registers Spring AI's `SpringAiRetryAutoConfiguration` in its own imports file; Spring AI's retry module registers it.

### Fixed

- Retained error bodies, diagnostics and metadata redact the configured API key and its percent-encoded and JSON `\u`-escaped forms. This now includes errors inside a successful response, a stream event or a choice, which before relied on generic credential patterns only and missed encoded forms and keys without an `sk-` prefix.

## [0.1.0](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0) - 2026-11-04

The first stable release. Moves the baseline to Spring Boot 4.1.2 and Spring AI 2.0.2, retries transient errors inside successful responses, reports every wire-contract violation as `OpenRouterProtocolException`, and allocates less on streaming and request paths.

### Upgrade notes

- A transient error inside an HTTP 200 response from Chat Completions, embeddings or images now raises `OpenRouterTransientApiException` instead of `OpenRouterApiException`, and the blocking retry policy retries it. Each retry sends the request again. To limit retries, set `spring.ai.retry.max-attempts` or supply a `RetryTemplate` bean. See [Errors and retries](docs/errors-and-retries.md).
- A child context now checks `ToolCallingManager` beans it inherits from a parent context without validation of its own. Startup fails if such a parent declares a manager without an OpenRouter failure policy, or a custom manager that is not a singleton. The parent and sibling contexts are not changed. See [Parent contexts](docs/tool-calling.md#parent-contexts).
- The `ObjectMapper` passed to `OpenRouterApi.Builder.objectMapper(...)` now serializes every request body, bypassing JSON converters and codecs registered on the `RestClient.Builder` and `WebClient.Builder`. Boot auto-configuration always passes one (the application's `ObjectMapper`, or a default mapper), so this applies to every Boot application. See [HTTP customization](docs/http-and-limits.md#http-customization-and-timeout-ownership).
- Chat `stream()` with invalid options returns a `Flux` that fails on subscription instead of throwing when called.

### Changed

- The default `spring.ai.openrouter.chat.tool-call-aggregation.max-chunks` is 8192 instead of 1024, for Chat Completions and Responses. The 1 MB byte limit is unchanged. See [Tool-call assembly limits](docs/http-and-limits.md#tool-call-assembly-limits).
- Streaming and request paths allocate less memory, most for long tool-call streams.
- A response that breaks the wire contract now raises `OpenRouterProtocolException` in every request mode, for calls and streams. This covers undecodable JSON, blank or conflicting tool names, embedding count, index or dimension mismatches, and invalid audio fragments. RC3 raised `IllegalStateException` or `IllegalArgumentException` for some of these. Catch `OpenRouterProtocolException` instead. [#211](https://github.com/Subhransu-De/openrouter-spring-ai/pull/211)
- `extraBody` accepts `transforms`. Send an empty list to turn off OpenRouter's middle-out transform. [#211](https://github.com/Subhransu-De/openrouter-spring-ai/pull/211)
- The library is built and tested with the Jackson version that Spring Boot manages, instead of forcing Jackson 3.2.3. [#210](https://github.com/Subhransu-De/openrouter-spring-ai/pull/210)
- The baseline is Spring Boot 4.1.2 and Spring AI 2.0.2. [#228](https://github.com/Subhransu-De/openrouter-spring-ai/pull/228), [#229](https://github.com/Subhransu-De/openrouter-spring-ai/pull/229)

### Fixed

- Embedding error envelopes take precedence over vectors, and fractional embedding indexes fail.
- A Responses event without a `type`, or an output item that is not a JSON object, fails as a protocol error.
- `cache_write_tokens` maps to `Usage.getCacheWriteInputTokens()` in both request modes.
- A buffered tool call no longer replaces newer usage, and stream observations keep each choice and its metadata.
- Nested provider and reasoning properties bind in native images and appear in IDE metadata.
- A blank structured-output schema fails before the request is sent, and a named `tool_choice` serializes with `type` first.
- Encoded echoes of the API key are redacted from retained error bodies.

### Documentation

- The README is now a short landing page. Usage guides are in [`docs/`](docs/README.md), build and quality-check guidance is in [CONTRIBUTING.md](CONTRIBUTING.md), and release history is in this file.
- Diagrams replace long explanations of the architecture, model selection, option composition, request modes, tool calling, reasoning replay, cache breakpoints, stream retries, limits, chat audio output and the Garage demo.

## [0.1.0-RC3](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC3) - 2026-10-02

Adds strict function tools, explicit cache breakpoints, optional request and response extensions, embedding metadata and dimension discovery, cumulative Responses state limits, PDF, audio and video inputs, and streaming chat audio output.

### Upgrade notes

- RC2 code that calls a `ChatCompletionRequest` or `ResponsesRequest` constructor no longer compiles. Build the requests with `builder()` instead. See [Supported API and nullability](docs/api.md#build-low-level-requests).
- The [RC2 README](https://github.com/Subhransu-De/openrouter-spring-ai/blob/v0.1.0-RC2/README.md) describes the RC2 API.

## [0.1.0-RC2](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC2) - 2026-09-19

Focuses on response correctness, streaming reliability, and clearer integration contracts, and adds structured-output properties and explicit tool-failure policies.

- The repository is now `Subhransu-De/openrouter-spring-ai`. Maven coordinates and Java packages are unchanged. RC2 corrects the module backlinks in the published metadata.

## [0.1.0-RC1](https://github.com/Subhransu-De/openrouter-spring-ai/releases/tag/v0.1.0-RC1) - 2026-09-12

The first release candidate: native OpenRouter chat, embedding, and image support for Spring AI, with Chat Completions as the default and Responses as an experimental opt-in.

- The published RC1 metadata keeps broken module backlinks from before the repository rename. Published metadata cannot be changed in place.
