# Changelog

Notable changes in each release. The full notes for each release are on the [Releases page](https://github.com/Subhransu-De/openrouter-spring-ai/releases).

## Unreleased

### Upgrade notes

- Boot auto-configuration now looks up a `JsonMapper` bean, not any `ObjectMapper`. With Boot's Jackson auto-configuration, its `@Primary` `JsonMapper` was already the one used, so nothing changes. An application that defines its own `JsonMapper` and also has an `XmlMapper` or `CBORMapper` no longer fails at startup. A plain `ObjectMapper` bean is no longer picked up; declare it as a `JsonMapper`. See [HTTP customization](docs/http-and-limits.md#http-customization-and-timeout-ownership).
- When `spring.http.clients.connect-timeout` is set and `spring.ai.openrouter.connection.timeout` is not, OpenRouter blocking calls now connect with Boot's value, as streams already did, instead of the 2m default. An explicit `connection.timeout` still bounds blocking connects. The new `spring.ai.openrouter.connection.connect-timeout` overrides both. `connection.timeout` stays the blocking read timeout and the stream idle guard. `OpenRouterConnectionProperties.getTimeout()` never returns `null`: `setTimeout(null)` now restores the 2m default instead of turning the stream guard off. Use `OpenRouterApi.Builder.timeout(null)` for that. See [Timeouts](docs/http-and-limits.md#timeouts).
- `OpenRouterChatOptions.setOutputSchema`, `setToolCallbacks` and `setToolContext`, the five `OpenRouterEmbeddingOptions` setters, and `copy()` on the chat, embedding and image options are removed, as in Spring AI 2.0, whose options are immutable. RC code that calls them no longer compiles. Replace `options.setModel(m)` with `options = options.mutate().model(m).build()`, and `options.copy()` with `options.mutate().build()`. See [Option snapshots](docs/chat-options.md#option-snapshots).
- With two or more `RetryTemplate`, `ObservationRegistry` or `JsonMapper` beans and no `@Primary`, the auto-configuration now falls back to Spring AI's default retry policy, a no-op registry or a default `JsonMapper`, as the official Spring AI providers do, instead of failing at startup. When it skips an application `JsonMapper` or plain `ObjectMapper` this way, it logs a warning.
- An embedding call or `dimensions()` probe with no model now fails with `IllegalArgumentException` before any request, as image calls already did. OpenRouter requires a model for embeddings, so these requests could not succeed. Set `spring.ai.openrouter.embedding.model`, pass a model in the request options, or set `spring.ai.model.embedding=none`.
- A tool failure whose cause is not a `RuntimeException`, such as an `IOException` or an `Error`, now propagates from `OpenRouterToolExecutionExceptionProcessor` instead of becoming the fixed failure payload, and so does Spring Security's `ClientAuthorizationException` when it is on the classpath, as with Spring AI's default processor. Applications that relied on the payload for checked exceptions should catch them in the tool. See [Tool failure policy](docs/tool-calling.md#tool-failure-policy).
- The auto-configuration classes are `final`. They were never supported API.

### Added

- `OpenRouterReasoningOptions.builder()` and `OpenRouterProviderPreferences.builder()` set only the fields you name, instead of a positional constructor with `null` for the rest. The constructors stay. See [Routing](docs/chat-options.md#routing-fallbacks-and-attribution).
- `OpenRouterUsage.getCostDetails()` returns OpenRouter's cost breakdown (upstream total, prompt cost, completion cost and server-tool cost) for chat, embeddings and images in both request modes, and `getByok()` reports whether the request used your own provider key. See [Observability](docs/observability.md).
- `OpenRouterRestClientCustomizer` and `OpenRouterWebClientCustomizer` beans customize only the OpenRouter clients, after Boot's global customizers and before OpenRouter's credentials and base URL. See [HTTP customization](docs/http-and-limits.md#http-customization-and-timeout-ownership).
- `spring.ai.openrouter.connection.connect-timeout` sets an OpenRouter-only connect timeout for blocking calls.
- Startup warns when OpenRouter's tool failure policy also handles another provider's chat model, which happens next to another provider starter with `spring.ai.model.chat` unset.
- The core module registers its wire-type runtime hints in `META-INF/spring/aot.factories`, so native images get them without the auto-configuration.
- `OpenRouterChatModel.getOptions()` now declares `OpenRouterChatOptions`, so OpenRouter-only defaults need no cast, and `OpenRouterEmbeddingModel` and `OpenRouterImageModel` gain a typed `getOptions()`.
- Every `spring.ai.openrouter.*` key, nested provider, reasoning, audio and response-format keys included, has an IDE description, and the jar ships `META-INF/spring-autoconfigure-metadata.properties` so Boot can skip the auto-configurations by class condition without loading them.
- Startup reports `spring.ai.retry.on-http-codes`, `exclude-on-http-codes` or `on-client-errors` when set, because OpenRouter models do not use them. It is an INFO message when a Spring AI provider that reads those keys, such as Mistral or Ollama, is active, and a warning otherwise. See [Retries](docs/errors-and-retries.md#retries).
- [Getting started](docs/getting-started.md#install-and-make-a-first-call) explains what happens next to another provider starter: with a `spring.ai.model.*` selector unset, both providers create a model of that type, and consumers that take a single `ChatModel` or `EmbeddingModel` fail.

### Changed

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
