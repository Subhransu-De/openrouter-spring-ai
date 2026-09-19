<p align="center">
  <img src="assets/soft-weave-transparent.png" alt="OpenRouter Spring AI tree logo" width="200">
</p>

<h1 align="center">OpenRouter Spring AI</h1>

<p align="center">Native OpenRouter integration for Spring AI</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/de.subhransu/openrouter-spring-ai-starter"><img src="https://img.shields.io/maven-central/v/de.subhransu/openrouter-spring-ai-starter?label=Maven%20Central&amp;style=flat" alt="Maven Central"></a>
  <img src="https://img.shields.io/badge/Java-17%20%7C%2021%20%7C%2025-orange?style=flat" alt="Java 17, 21, and 25">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/Subhransu-De/openrouter-spring-ai?label=License&amp;style=flat" alt="MIT License"></a>
  <br>
  <a href="https://github.com/Subhransu-De/openrouter-spring-ai/actions/workflows/ci.yml"><img src="https://img.shields.io/github/actions/workflow/status/Subhransu-De/openrouter-spring-ai/ci.yml?branch=main&amp;label=Build&amp;style=flat" alt="Build status"></a>
  <a href="https://github.com/Subhransu-De/openrouter-spring-ai/actions/workflows/garage-nightly.yml"><img src="https://img.shields.io/github/actions/workflow/status/Subhransu-De/openrouter-spring-ai/garage-nightly.yml?branch=main&amp;label=Nightly%20check&amp;style=flat" alt="Nightly compatibility test status"></a>
  <a href="https://github.com/Subhransu-De/openrouter-spring-ai/actions/workflows/codeql.yml"><img src="https://img.shields.io/github/actions/workflow/status/Subhransu-De/openrouter-spring-ai/codeql.yml?branch=main&amp;label=CodeQL&amp;style=flat" alt="CodeQL status"></a>
</p>

A native [Spring AI](https://spring.io/projects/spring-ai) chat provider for
[OpenRouter](https://openrouter.ai). Instead of pointing the OpenAI client at OpenRouter's URL
and losing everything that makes OpenRouter distinct, this library models the OpenRouter surface
directly: model fallbacks, provider routing, cross-provider reasoning tokens, cost accounting,
and attribution headers — all behind the standard Spring AI `ChatModel` contract and
`spring.ai.openrouter.*` properties, built the same way Spring AI builds its official providers.

> **Community project:** This library is independently maintained and is not affiliated with,
> endorsed by, or an official project of OpenRouter or Spring AI.
>
> Install only one OpenRouter Spring AI starter in an application. Combining this starter with
> another OpenRouter starter can make provider selection and bean creation order-dependent.

Chat Completions is the production default. The optional Responses request mode is experimental;
applications should opt into it explicitly.

The repository is now `Subhransu-De/openrouter-spring-ai`; Maven coordinates and Java
packages are unchanged. Published `0.1.0-RC1` metadata retains its historical broken
module backlinks and cannot be changed in place. The corrected links will ship in the
next release; use that release once it is published.

### Supported API and nullability

The supported consumer API consists of the following types under
`de.subhransu.openrouter.springai`. Existing public visibility is unchanged.

| API                                                                                  | Supported use                                                                                                                                                                           |
| ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `chat`, `embedding`, `image`                                                         | Models and their builders, options and option builders, routing/reasoning/format records, usage and generated-image metadata. Use the Spring AI model interfaces for calls and streams. |
| `OpenRouterIdentifiers`                                                              | Provider identifiers used to select this integration.                                                                                                                                   |
| `api.OpenRouterApi`, `api.OpenRouterRequestMode`                                     | Direct HTTP calls and client construction, including `RestClient.Builder`, `WebClient.Builder`, Jackson, attribution, timeouts, and response limits.                                    |
| `api.dto`                                                                            | Low-level request and response records used by `OpenRouterApi`. Consumers may construct requests and inspect responses; wire fields can be absent. Responses mode remains experimental. |
| `chat.OpenRouterToolFailurePolicy`, `chat.OpenRouterToolExecutionExceptionProcessor` | Custom tool-manager failure policy and failure rendering. The policy accessor must return the actual, non-null processor. Tool execution belongs to Spring AI's advisor.                |
| `errors`, `chat.errors`, `api.errors.OpenRouterApiException`                         | Exception types, diagnostic records, category enums, and inspection interfaces. Missing diagnostics are nullable.                                                                       |
| `autoconfigure.*Properties` and `spring.ai.openrouter.*`                             | Boot property binding. Custom model, API, and tool beans use the existing auto-configuration backoff rules.                                                                             |

Mapper packages, `internal`, `support.OptionSnapshots`, error factories/classifiers,
`OpenRouterExceptionMessage`, `OpenRouterErrorResponse`, deserializers, runtime hints, and auto-configuration
implementation methods are implementation details even where Java visibility is public.
They are not supported extension points and may change between releases. Prefer the
consumer types above. This policy does not move classes or remove existing methods.

Consumer packages declare JSpecify `@NullMarked`; `@Nullable` marks optional values.
A nullable list or map does not imply nullable elements. Request lists and Spring AI
tool callback/context entries retain their non-null element contracts. JSON metadata
maps allow null values, and response arrays can contain null entries. Handle missing
response fields before dereferencing them, including usage, cost, and media metadata.
Implementation helpers with unaudited contracts remain explicitly `@NullUnmarked`
or outside marked packages. These annotations do not add runtime validation.

Java method descriptors and the Java 17 baseline are unchanged. Existing Java callers
continue to compile. Kotlin with strict JSpecify checking and null-aware Java analyzers
may now reject unchecked dereferences of values that were already nullable at runtime.
Use null checks or Kotlin safe calls. The consumer compilation tests cover optional
options, response fields and elements, Spring AI option types, and a custom failure policy.

A null runtime option means "inherit the configured default", not "clear it". For
example, if defaults include `seed(7)`, merging a runtime option with
`requestMode(OPENAI_RESPONSES).seed(null)` retains seed 7 and fails Responses validation.
Use defaults without that Chat Completions-only option when switching modes. Calling
`defaults.mutate().seed(null).build()` creates a separate defaults object with no seed;
it does not clear a seed when that object is merged back into the original defaults.

Builders, copies, merges, and model defaults snapshot option containers. JSON maps and
lists are copied recursively; opaque callbacks and application objects retain identity.
This does not promise arbitrary deep immutability or general thread safety. Existing
option setters and Boot property beans remain mutable. Do not mutate shared options
concurrently with calls. The ownership regressions for #18 remain in
`OptionSnapshotTests` and `OpenRouterChatModelSnapshotTests`.

### Request-mode option contract

Both modes map `outputSchema` and `responseFormat`: Chat Completions uses
`response_format`, and Responses uses `text.format`. An explicit `responseFormat`
takes precedence over `outputSchema`. Portable schemas leave `strict` unset;
`OpenRouterResponseFormat.jsonSchema(name, strict, schema)` preserves an explicit
`true` or `false`. Schema enforcement depends on the selected provider. Function-tool strictness is
controlled independently with `toolStrict`, described below.

Configure structured-output defaults under the flattened chat namespace (no
`options` segment):

```yaml
spring:
  ai:
    openrouter:
      chat:
        response-format:
          type: json-schema
          name: answer
          strict: true
          schema: |
            {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
```

`response-format.type` also accepts `text` and `json-object`. For a portable
schema, set `spring.ai.openrouter.chat.output-schema` to the JSON document instead;
it uses the schema name `response` and leaves strict unset. An explicit
`response-format` wins when both are configured. Omit `strict` to leave it unset,
or set it to `true` or `false` explicitly. With neither option configured, no
format is sent. JSON-schema formats require a schema; malformed JSON fails before
the request is sent. These properties use the same mapping as Java options for
calls and streams in both request modes.

Responses rejects explicitly set `stopSequences`, `seed`, `repetitionPenalty`, `minP`,
`topA`, and `includeUsage` with `IllegalArgumentException` before sending a request.
Unset these options or use Chat Completions. Responses usage is read from the response
without an opt-in toggle. `maxCompletionTokens` takes precedence over `maxTokens` and
maps to `max_output_tokens`. Other sampling, routing, metadata, image, and tool controls
map to their corresponding wire fields. `requestMode` selects the endpoint;
`toolCallbacks` supply tool definitions and `toolContext` stays on the client.

`toolChoice` accepts `"auto"`, `"none"`, `"required"`, or a named function object:
`{type: "function", function: {name: "lookup"}}` or
`{type: "function", name: "lookup"}`. Both named shapes are converted to the selected
endpoint's form. Legacy `{type: "auto"}`, `{type: "none"}`, and
`{type: "required"}` objects are normalized to strings. Other shapes are rejected. These rules apply to calls and streams.

### Optional request and response extensions

`OpenRouterChatOptions.builder().extraBody(Map.of("logprobs", true, "top_logprobs", 0))`
adds supported optional fields at the request root. This is a bounded extension API:
unknown keys and standard fields such as `model`, `messages`, `input`, `stream`,
`provider`, and `tools` are rejected, even when their typed option is unset.

| Extension keys                                                       | Chat Completions | Responses | Java / wire representation                                                   |
| -------------------------------------------------------------------- | ---------------- | --------- | ---------------------------------------------------------------------------- |
| `logit_bias`, `logprobs`                                             | Supported        | Rejected  | Token-ID map / boolean at request root                                       |
| `top_logprobs`, `prompt_cache_key`                                   | Supported        | Supported | Integer / nullable string at request root                                    |
| `verbosity`                                                          | Supported        | Rejected  | String at request root; Responses `text.verbosity` is not exposed            |
| Provider `only`, `zdr`, `max_price`                                  | Supported        | Supported | List / boolean / object inside `provider`; hard routing restrictions         |
| Provider `sort`, `preferred_min_throughput`, `preferred_max_latency` | Supported        | Supported | String or structured sort / number or percentile object; routing preferences |

The matrix follows the OpenRouter [parameter reference](https://openrouter.ai/docs/api_reference/parameters),
[provider routing guide](https://openrouter.ai/docs/guides/routing/provider-selection),
and [Responses schema](https://openrouter.ai/docs/api/api-reference/responses/create-a-response).
Model/provider support still applies; `top_logprobs` on Chat Completions requires
`logprobs=true`. The same mapping and validation apply to calls and streams.
`OpenRouterExtensionTests` covers serialization, endpoint rejection, routing, and
response preservation; `OpenRouterExtensionPropertiesTests` covers Boot binding.

Provider extensions use `OpenRouterChatOptions.builder().providerExtraBody(...)`.
`OpenRouterProviderPreferences` retains its existing constructor and string `sort` accessor. An extension
`sort` conflicts with a non-null typed `sort` and is rejected rather than overriding it.
For example, the extension map can contain `Map.of("sort", Map.of("by", "latency", "partition", "none"))`.

Boot binds root extensions under `spring.ai.openrouter.chat.extra-body` and provider
extensions under `spring.ai.openrouter.chat.provider-extra-body`. Preserve wire key
spelling with bracket notation in properties:

```properties
spring.ai.openrouter.chat.extra-body.[logprobs]=true
spring.ai.openrouter.chat.extra-body.[top_logprobs]=0
spring.ai.openrouter.chat.provider-extra-body.[zdr]=true
spring.ai.openrouter.chat.provider-extra-body.[sort].by=latency
```

Root extension maps merge by key: runtime entries replace default entries, including
explicit null values. Nested values replace whole objects; they do not merge recursively.
Typed provider options replace the default typed provider object, as before;
`providerExtraBody` merges independently by key, retaining default routing restrictions unless explicitly overridden. Maps and lists
are snapshotted recursively. Boolean false, numeric zero, and explicit nulls remain on
the wire; a null map inherits defaults. Extensions do not enable `n`, `store`, cache
breakpoints, web-search requests, or server-tool configuration.

Chat response DTOs retain unknown fields in `extensions()`, flattened on serialization.
Spring AI assistant metadata exposes `openrouter.message.extensions`,
`openrouter.choice.extensions` (including log probabilities), and
`openrouter.tool_call.extensions` (keyed by call ID). Response metadata exposes
`openrouter.response.extensions`. Streamed message annotations append in arrival order;
Choice `logprobs.content` and `logprobs.refusal` token arrays also accumulate;
other opaque fields use the latest value per key. Tool fields survive argument-fragment
aggregation, and assistant extension metadata is cumulative per choice and subscription.
These fields are inspection-only and are not automatically replayed as request fields.

Responses output annotations and opaque tool fields remain available in the existing
`openrouter.responses.output_items` snapshot through each item's `rawItem()` / `wireValue()`.
The existing ordered reasoning replay policy is unchanged. Citation data is preserved
when returned; this does not add web-search or server-tool execution support.

### Strict function tools

Set `OpenRouterChatOptions.builder().toolStrict(true)` or
`spring.ai.openrouter.chat.tool-strict=true` to request strict schemas for every
function tool. This applies to calls and streams in both modes: Chat Completions
sends `tools[].function.strict`; experimental Responses sends `tools[].strict`.
There are no per-tool overrides. During option composition, an omitted/null option
inherits the default; a non-null runtime option overrides it, including `false`. With no default, null
omits the wire flag, preserving provider defaults; `false` explicitly disables
strictness. Copying and composing options preserve these distinctions.

The library validates rather than adapts schemas when `true`. Supply an object
root, `additionalProperties: false` on every object, and a `required` array
containing every property exactly once (empty objects use empty `properties` and
`required`). Optional values must explicitly allow null **and** remain required,
for example `"note": {"type": ["string", "null"]}` with `"note"` in `required`.
The validator follows nested properties, array items, `anyOf`, definitions, and
local JSON-pointer `$ref`s, including recursion. External/unresolved references,
root `anyOf`, and unsupported composition such as `allOf`/`oneOf` are rejected
before HTTP. Schemas are never rewritten; the callback retains its original schema.
Omitted/false strictness preserves existing schema handling.

Use a routed model/provider that supports strict function tools; this option does
not discover capabilities or guarantee support across OpenRouter. Validation checks
the common [strict function contract](https://developers.openai.com/api/docs/guides/function-calling#strict-mode),
not every provider-specific schema keyword, size limit, or model restriction.
Provider rejections propagate through normal API errors, without silently retrying
as non-strict. `ToolCallingAdvisor` still owns execution; response-format strictness
is unaffected.

### Retries and Responses failures

Synchronous Responses failures carried over HTTP 200 use
`OpenRouterTransientApiException` or `OpenRouterNonTransientApiException`, replacing
the legacy `OpenRouterApiException` for this request mode. Inspect their shared
`OpenRouterHttpException` contract for sanitized error details and category;
the status is derived from the in-band error, and the endpoint is `/responses`.
Transient provider, rate-limit, and timeout failures qualify for Spring AI's
default retry policy. Authentication, billing, invalid requests, refusals, and
unknown failures do not.

Responses streams expose the same exception types but are never automatically
retried, including after partial output. Tool execution belongs to
`ToolCallingAdvisor`; the model's retry scope covers only the provider request
and response mapping. Garage uses two retries with a 200 ms delay only for
`TransientAiException` and transport `ResourceAccessException` failures.

### Response buffering limits

`spring.ai.openrouter.connection.max-response-body-size` (default `64MB`) limits each
blocking response, each SSE event, and the complete JSON fallback for streamed image
requests. It does not cap the total size of an SSE stream. Streaming success decoding
uses Spring's codec limits and reports `DataBufferLimitException` when exceeded
(wrapped in `WebClientResponseException` for chat and Responses).

`spring.ai.openrouter.connection.max-error-body-size` (default `64KB`) bounds HTTP error
body retention for both blocking and streaming requests. Oversized errors report
`OpenRouterLimitExceededException` with the endpoint, HTTP status, bounded sanitized
excerpt, and any recoverable error details. The observed size is a lower bound.
Both settings accept 1 through 2147483646 bytes; invalid values fail property binding.
Java callers can configure the same limits with `OpenRouterApi.Builder`.

## Status

Done and live-verified:

- [x] Chat via OpenRouter's OpenAI-compatible `POST /chat/completions` endpoint
- [x] Synchronous calls and SSE streaming
- [x] Tool calling via Spring AI 2.0's `ToolCallingAdvisor` on `ChatClient` (see below)
- [x] Streamed tool-call argument fragments (split by `index` across chunks) merged into
      complete tool calls
- [x] Reasoning options, reasoning text, and opaque reasoning state preserved across tool continuations
- [x] Usage accounting including cost, cached and reasoning tokens
- [x] Model fallback lists, provider routing preferences, service tiers
- [x] Spring Boot auto-configuration with full property binding
- [x] Observability: Micrometer `gen_ai.client.operation` observations for calls and streams
- [x] Embeddings via `POST /embeddings` behind Spring AI's `EmbeddingModel`
      (`spring.ai.openrouter.embedding.*` properties, provider routing included)
- [x] Image inputs: `UserMessage` media becomes `image_url` content parts (URLs pass
      through, byte-backed media is sent as base64 data URLs), in both chat-completions
      and responses mode
- [x] Image generation via OpenRouter's unified Image API (`POST /images`) behind Spring
      AI's `ImageModel`, including reference images, SSE partial-image streaming, and
      `spring.ai.openrouter.image.*` properties
- [x] Image generation via chat completions and responses mode
      (`modalities: ["image", "text"]` + `image_config`), with generated images surfaced as
      `AssistantMessage` media in sync and streaming responses

Planned:

- [ ] Typed DTO fields for currently skipped response data (`reasoning_details`, `logprobs`, …)
- [ ] The models catalogue endpoint
- [ ] OpenRouter server-side tools (web search plugin) and citation annotations
- [x] PDF, WAV/MP3 audio and video chat inputs (Chat Completions and Responses)
- [ ] Text-to-speech and transcription

## Modules

| Module                               | What it is                                                                                       |
| ------------------------------------ | ------------------------------------------------------------------------------------------------ |
| `openrouter-spring-ai`               | Core: API client, wire DTOs, mappers, `OpenRouterChatModel`, `OpenRouterEmbeddingModel`, options |
| `openrouter-spring-ai-autoconfigure` | Spring Boot auto-configuration and `spring.ai.openrouter.*` binding                              |
| `openrouter-spring-ai-starter`       | The starter — the one dependency applications add                                                |
| `openrouter-spring-ai-samples`       | The Garage demo application (see below)                                                          |

Module names, property prefixes, and layering deliberately mirror Spring AI's official
providers.

### Tool calling

Spring AI 2.0 moved the tool-execution loop out of the chat models and into
`ToolCallingAdvisor`, which runs in the `ChatClient` advisor chain. This library follows
that design exactly (as do Spring AI's own OpenAI and Anthropic models):

- **The model advertises and surfaces, never executes.** `OpenRouterChatModel` puts the
  tool definitions from your `ToolCallback`s on the wire and returns tool-call responses
  as-is (with a `TOOL_CALLS` finish reason), for both `call()` and `stream()`, in both
  chat-completions and responses mode. Your callbacks are never invoked by the model.
- **`ChatClient` + `ToolCallingAdvisor` runs the loop.** The advisor invokes your
  `ToolCallback`s, appends the results to the conversation, and calls the provider again
  until a final answer arrives — for calls and streams alike. `ChatClient`
  auto-registers the advisor by default, so `ChatClient.builder(chatModel).build()` is
  enough; opt out per request with
  `advisors(AdvisorParams.toolCallingAdvisorAutoRegister(false))` to surface tool calls
  without executing them.

In Chat Completions streams, a tool's function name must arrive as a complete name.
Missing or blank names are ignored until a nonblank name arrives; identical repeated
names are accepted. Differing nonblank names (including split-name fragments) and
completed calls without a usable name fail with `IllegalStateException` before the
call is emitted. JSON argument fragments are still concatenated by tool index, and
calls require a tool-call finish reason before they can be emitted.

Option builders, copies, and model defaults own detached collection snapshots. Provider
routing lists, image input references, and nested JSON maps/lists (metadata, image
configuration, provider options, and tool choice) are read-only through getters. Use
`mutate()` to derive changed options. Tool-context maps/lists are also snapshotted;
callbacks and other opaque application objects remain shared by identity. Use JSON
maps/lists for container isolation; arbitrary mutable objects are not cloned. Null
values and collection order are preserved. Embedding builders return independent
results; explicit setters change only the option instance on which they are called.

Register tools on the request options (or the `ChatClient`), not as model default
options: the advisor executes with the options it sees on the prompt. Tools declared only
by bean name are resolved through the `ToolCallbackResolver` configured on the
`ToolCallingManager` during execution.

#### Model selection and replacement beans

The starter intentionally backs off for any application-declared `ChatModel`,
`EmbeddingModel`, or `ImageModel` of the corresponding modality. This includes another
provider's model and a custom OpenRouter model. Setting `spring.ai.model.chat=openrouter`
enables chat auto-configuration but does not override a replacement `ChatModel` bean.
The same rule applies to `spring.ai.model.embedding` and `spring.ai.model.image`.
This preserves existing replacement-bean behavior and requires no migration.

Each selector defaults independently to `openrouter`. Use `none` to disable a modality,
or another provider's identifier to select its auto-configuration. Disabling chat alone
leaves OpenRouter embeddings and images enabled and still requires an API key. Set all
three selectors to `none` to disable the shared OpenRouter API as well. Replacement model
beans alone do not disable that API.

When several provider starters are installed, set each selector explicitly. This policy
does not arbitrate between competing auto-configurations that match missing selectors;
their ordering must not be used to choose a provider. For multiple models of one modality,
declare the model beans yourself and inject them with `@Qualifier` using their bean names.
The starter's default bean names are `openRouterChatModel`, `openRouterEmbeddingModel`,
and `openRouterImageModel`. Selection properties do not remove application-declared beans.

A replacement `ChatModel` also disables OpenRouter's automatic tool-failure processor
and manager guard. Applications that declare their own chat models own the shared tool
failure policy described below.

#### Tool failure policy

When OpenRouter is selected and no replacement `ChatModel` is declared, the starter
supplies `OpenRouterToolExecutionExceptionProcessor` before Spring AI creates its
shared manager. It returns a stable sanitized error result. This processor also applies
to other providers using that shared manager. A declared `ToolExecutionExceptionProcessor`
bean takes precedence; `spring.ai.tools.throw-exception-on-error=true` retains Spring AI's
throwing policy instead.

Custom and delegating `ToolCallingManager` implementations can implement
`de.subhransu.openrouter.springai.chat.OpenRouterToolFailurePolicy`. Return the processor
actually used by execution from `toolExecutionExceptionProcessor()`: an application-declared
processor bean, `OpenRouterToolExecutionExceptionProcessor`, or Spring AI's default processor
configured with `alwaysThrow(true)`. Delegates must use that same processor. The contract
applies to both calls and streams; it declares application responsibility and cannot prove
that a custom implementation honors its declaration.

Existing `DefaultToolCallingManager` beans remain supported through a private-field
compatibility adapter tested against Spring AI **2.0.1**, the dependency baseline. Other
versions are not verified. The public contract with an application-declared processor avoids
this adapter entirely. If upstream internals change, inspection fails with migration guidance;
missing fields no longer fail class initialization or native hint registration.

Managers without a verifiable policy still fail validation. The existing
`spring.ai.openrouter.chat.allow-unsafe-tool-failure-results=true` opt-out skips manager
validation and transfers failure-result redaction responsibility to the application; it does
not disable the automatically supplied processor. Declaring an unrelated processor bean
does not approve a manager that uses a different processor.

### Explicit prompt-cache breakpoints

For Chat Completions, attach a `List<OpenRouterCacheBreakpoint>` to a system or user
message's metadata. Each exclusive `endIndex` splits the original text into a content
block with `cache_control`; the remaining text and image media stay in their original
order. Offsets use Java `String.length()` units, must increase, and cannot split a
surrogate pair. For example (synthetic content):

```java
String reference = "Reference material to reuse.\n";
UserMessage message = UserMessage.builder()
    .text(reference + "Answer this turn's question.")
    .metadata(Map.of(OpenRouterCacheBreakpoint.METADATA_KEY,
        List.of(new OpenRouterCacheBreakpoint(reference.length()))))
    .build();
chatModel.call(new Prompt(message));
```

The default `Ttl.FIVE_MINUTES` emits `{"type":"ephemeral"}`; `Ttl.ONE_HOUR`
adds `"ttl":"1h"`. Both calls and streams use these boundaries. There are no cache
model defaults or Boot properties: placement belongs to individual messages, so there
is no option precedence. Keep the typed metadata and unchanged text in conversation
history, including tool continuations. Custom history stores must restore the typed
breakpoints; generic maps are rejected. Recompute offsets when editing text.

The integration supports at most four breakpoints per request, with all one-hour
boundaries before five-minute boundaries. Assistant messages, tool results, tool
definitions, and image blocks are not supported breakpoint targets. Invalid metadata,
offsets, ordering, or placement fail explicitly.

| Request protocol / provider                    | Supported placement and lifetime                                                                                                                                                                                   |
| ---------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Chat Completions / Claude-compatible providers | System and user text blocks; five minutes or one hour.                                                                                                                                                             |
| Chat Completions / supported Alibaba models    | Text blocks; use five minutes. Model and endpoint support varies.                                                                                                                                                  |
| Chat Completions / supported Gemini models     | Text blocks; use five minutes. Only the final boundary is used; a boundary in the first system message caches the normalized system prompt, including its trailing text. Put dynamic text in a later user message. |
| Chat Completions / other routes                | Provider-dependent; no guarantee of explicit caching or TTL preservation. Select a supporting route.                                                                                                               |
| Responses                                      | This metadata is rejected. Its per-block `prompt_cache_breakpoint` has different semantics and is not implemented here.                                                                                            |

Provider/model eligibility and minimum prompt sizes are enforced upstream; the library
does not infer capabilities from model names or fallback routing. Consult
[OpenRouter's prompt caching guide](https://openrouter.ai/docs/guides/best-practices/prompt-caching)
for current restrictions. This explicit content API is narrower than Spring AI's
Anthropic caching strategies: it does not automatically select message or tool
boundaries. Provider implicit caching remains available without these markers.
Request-level automatic caching, `prompt_cache_key`, and cache usage accounting are
separate capabilities.

### Reasoning conversation state

Assistant message metadata carries `openrouter.reasoning` (text) and
`openrouter.reasoning_details` (an ordered list of opaque JSON nodes) in Chat Completions
mode. Responses mode retains complete reasoning output items under
`openrouter.responses.reasoning_items`, including encrypted content and unknown fields.
The accompanying `openrouter.responses.output_items` snapshot preserves their positions
relative to text and function calls during replay.
Request mappers replay this state when the assistant message is included in conversation
history. Keep the original assistant message and its metadata when adding tool results.
Responses rejects history before sending a request if the assistant text, tool calls,
or `openrouter.refusal` differ from the saved output snapshot, or reasoning metadata has no
output snapshot. Custom history storage must retain both metadata fields. Editing or removing
content while keeping the reasoning metadata is rejected. To redact history, start a new
conversation with the edited messages and without the old reasoning state. Do not carry
encrypted reasoning or output snapshots into that new conversation, since they may retain
the original content. The assistant media history restriction also applies to generated images
retained in output snapshots, even if the message's media list was cleared.

Streaming assembles consecutive text and summary detail fragments, while retaining
encrypted and unknown detail types as opaque items. Assistant metadata contains
cumulative snapshots per choice and subscription,
so Spring AI message aggregation retains the complete reasoning state. Text content remains
incremental. The existing generation metadata key `openrouter.reasoning` remains available
for streamed reasoning deltas and synchronous reasoning text.

### Refusals and incomplete output

Both request modes expose provider refusal explanations under `openrouter.refusal` in
assistant message and generation metadata, separately from answer text. Check this key
to distinguish a refusal from ordinary empty output; a refusal can still have `STOP`
as its finish reason. Streaming metadata contains cumulative refusal text, with repeated
done events and terminal snapshots reconciled rather than concatenated.
Keep the original assistant message and its metadata in conversation history so follow-up
requests replay the refusal explanation, including refusal-only assistant turns.

Responses normalizes `max_output_tokens` to `LENGTH` and `content_filter` to
`CONTENT_FILTER` for both synchronous and streaming calls. Unknown incomplete reasons
pass through unchanged; absent reasons fall back to the response status. Generation
metadata retains the unnormalized reason as `openrouter.native_finish_reason`, the
status as `openrouter.responses.status`, and the typed incomplete details as
`openrouter.responses.incomplete_details`. Responses text preserves whitespace-only
parts and messages; streaming text remains incremental without repeating terminal text.
If a completed stream delivers no text deltas, its saved output snapshot supplies the text.

Responses streams require `response.completed`, `response.incomplete`, `response.failed`,
or a terminal error event. `[DONE]` or EOF alone raises `OpenRouterTruncatedResponseException`,
even for an empty or text-only stream. The legacy `response.done` shape shown in the
[OpenRouter basic-usage guide](https://openrouter.ai/docs/api_reference/responses/basic-usage)
is explicitly unsupported and raises `OpenRouterProtocolException`; it does not map final
metadata. Chat Completions and image streams continue to accept `[DONE]`.

Responses streaming requires a response snapshot before releasing buffered tool calls
and rejects malformed response payloads. Optional fields may be absent and unknown fields
are ignored, but invalid typed fields fail decoding rather than discarding response
status or output.

### Embeddings

`OpenRouterEmbeddingModel` is auto-configured next to the chat model and implements Spring
AI's `EmbeddingModel`, so it plugs into vector stores and RAG advisors unchanged:

```yaml
spring:
  ai:
    model:
      embedding: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      embedding:
        model: openai/text-embedding-3-small
        dimensions: 256 # optional, model-dependent
```

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

Provider routing (`spring.ai.openrouter.embedding.provider.*`) works the same way as for
chat. Only `float` embeddings are decoded; requesting `encoding_format: base64` fails fast.

Document embedding keeps raw text by default (`metadata-mode: none`). To include metadata,
set `spring.ai.openrouter.embedding.metadata-mode: embed`, or use
`OpenRouterEmbeddingModel.builder().metadataMode(MetadataMode.EMBED)` in Java. Both
`embed(Document)` and batched document embedding use the document's content formatter;
`EMBED` honors its excluded embedding metadata keys. `ALL` and `INFERENCE` are also supported.
`NONE` bypasses formatting and preserves the original text. Changing the policy changes
embedding input, so existing vector collections may need re-embedding.

`dimensions()` returns positive configured dimensions without an API call. Otherwise it
caches the first successful discovery per model instance, with concurrent callers sharing
that discovery. Failed or invalid responses are not cached. The cache belongs to the
model's copied default configuration (including model, dimensions and provider routing);
per-request overrides never update it. Build a new model for a different default
configuration; modifying the options originally supplied to the builder does not change
an existing model or its cached dimensions.

### Image inputs

Attach image media to a `UserMessage` and it is sent as OpenRouter `image_url` content
parts. Plain URLs pass through; classpath/byte resources are inlined as base64 data URLs:

```java
UserMessage message = UserMessage.builder()
    .text("What is in this image?")
    .media(Media.builder()
        .mimeType(MimeTypeUtils.IMAGE_PNG)
        .data(URI.create("https://example.com/cat.png"))
        .build())
    .build();
```

### PDF, audio and video inputs

Both synchronous and streaming calls accept these `UserMessage` attachments:

| Media | MIME types                                           | Chat Completions                                     | Responses                                   | Source                                         |
| ----- | ---------------------------------------------------- | ---------------------------------------------------- | ------------------------------------------- | ---------------------------------------------- |
| PDF   | `application/pdf`                                    | `file` with `file_data`                              | `input_file` with `file_data` or `file_url` | HTTP(S) URL, bytes or base64 data URL          |
| Audio | `audio/wav`, `audio/mpeg`                            | `input_audio` with raw base64 and `wav`/`mp3` format | Same                                        | Bytes or base64 data URL; remote URLs rejected |
| Video | `video/mp4`, `video/mpeg`, `video/mov`, `video/webm` | `video_url` object                                   | `input_video` with string `video_url`       | HTTP(S) URL, bytes or base64 data URL          |

```java
UserMessage message = UserMessage.builder()
    .text("Summarize the attached document")
    .media(Media.builder()
        .mimeType(MimeTypeUtils.parseMimeType("application/pdf"))
        .name("report.pdf")
        .data(URI.create("https://example.com/report.pdf"))
        .build())
    .build();
```

PDF filenames come from `Media.getName()`; set `.name(...)` to retain an original
filename. Audio/video names are not sent. Other file types, audio formats, MIME
parameters and mismatched data-URL MIME types are rejected explicitly. This does
not add Anthropic Messages support, uploaded file IDs or audio output.

Each new inline attachment must contain 1 byte through 20 MiB of decoded data.
The limit is checked before encoding bytes or decoding a base64 data URL. Empty
and invalid base64 content is rejected. Provider request, file, duration and
attachment-count limits still apply, including to remote URLs; the local limit
is not a guarantee of provider acceptance. Existing image behavior is unchanged.

The integration never fetches attachment URLs. Spring AI materializes resources
when constructing `Media`, before this integration can enforce its limit; callers
must bound resource reads themselves or supply bounded bytes. Mapping does not
modify caller-owned bytes or messages. Reusing unchanged messages replays the same
attachments in order, after the message text, including across tool continuations.
A remote URL is replayed as a URL and may serve different content later.

Select a model/provider supporting the modality and format. OpenRouter validates
routing compatibility and returns provider errors; the library does not maintain
a static model allowlist. Video URL support is provider-specific (for example,
Gemini on AI Studio accepts YouTube URLs). See the [multimodal guide](https://openrouter.ai/docs/guides/overview/multimodal/overview)
and [endpoint schemas](https://openrouter.ai/openapi.json). No new Boot properties
or options are required; attachments use the existing message API.

### Image generation

`OpenRouterImageModel` implements Spring AI's `ImageModel` over OpenRouter's unified
Image API (`POST /images`):

```yaml
spring:
  ai:
    model:
      image: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      image:
        model: bytedance-seed/seedream-4.5
        aspect-ratio: "16:9"
        quality: high
        output-format: webp
```

```java
ImageResponse response = imageModel.call(new ImagePrompt("a red panda wearing sunglasses"));
String base64 = response.getResult().getOutput().getB64Json();
```

The OpenRouter-native knobs (`resolution`, `aspect-ratio`, `quality`, `output-format`,
`background`, `output-compression`, `seed`) are available on `OpenRouterImageOptions`,
along with `inputReferences` for image-to-image work and `providerOptions` passthrough.
Generic Spring AI `ImageOptions` map `model` and `n` directly and translate paired
`width`/`height` into pixel `size` (a lone dimension is rejected after merging defaults).
`responseFormat` must be omitted or exactly `b64_json`; any other non-null format,
including `url`, and any non-null `style` fail with `IllegalArgumentException` before
HTTP transport in both `call` and `stream`. These portable settings have no wire fields.
Use `OpenRouterImageOptions.fromOptions(...)` to convert generic defaults; generic
per-request options override mapped fields while retaining unset native defaults,
including `quality`, `outputFormat`, and `providerOptions`. Native `outputFormat`
selects image encoding (for example, `webp`), not URL versus base64 delivery.

`OpenRouterImageModel.stream(ImagePrompt)` exposes OpenRouter's SSE image streaming:
partial previews arrive first (see `OpenRouterImageGenerationMetadata.partialImageIndex()`),
then completed images with usage and cost. The stream ends at `[DONE]`, not at the
first completed image. An SSE connection that closes without `[DONE]` fails as a
truncated response, even if it delivered a completed image. `n` is an upper bound;
providers may return fewer images, and support for multiple images and native streaming
depends on the endpoint. Check the [OpenRouter image API capabilities](https://openrouter.ai/docs/guides/overview/multimodal/image-generation)
before combining them.

Image JSON responses must contain a `data` array; an empty array remains a valid
empty result. Each entry and each completed SSE event must contain nonblank
`b64_json` or `url`. The JSON streaming fallback preserves both forms. Missing
required content raises `OpenRouterProtocolException`; HTTP 200 error envelopes
raise structured `OpenRouterApiException` errors before success conversion.
Synchronous Chat Completions choices require a `message` object, but its text may be
empty (including tool, refusal, and media messages). Usage-only chat stream chunks
and image partial previews remain supported.

Image-capable _chat_ models work too: set
`OpenRouterChatOptions.builder().modalities(List.of("image", "text"))` (optionally with
`imageConfig`) and generated images arrive as `AssistantMessage` media — in sync calls
and streams alike, in both chat-completions and responses request modes.

Generated media can be attached directly to a new `UserMessage` for image edits or
follow-up questions. Responses base64 results are exposed as complete data URLs;
`output_format`, when supplied, determines the MIME type (PNG by default), while an
existing data URL retains its own MIME type.

Chat Completions preserves images when an `AssistantMessage` is replayed in conversation
history. Responses mode rejects assistant media history explicitly: attach the media to
a `UserMessage` instead, retaining any assistant text/tool history separately. This applies
to both synchronous and streaming requests. Image understanding still requires a model
that supports image input.

### Observability

The starter includes Spring AI's standard observation auto-configuration for chat,
embedding, and image models. OpenRouter model calls emit `gen_ai.client.operation`
observations with the `openrouter` provider, operation, request model, response model
where available, and token-usage context. When a `MeterRegistry` is present, chat and
embedding usage is also recorded as `gen_ai.client.token.usage`.

Chat and embedding response usage is available as `OpenRouterUsage`; image responses
expose it in metadata under `openrouter.usage`. It retains provider cost, cached and
reasoning token counts, and the native usage DTO. Cached tokens are also available
through Spring AI's portable `Usage.getCacheReadInputTokens()`. Missing detail counts
remain `null`, distinct from an explicit zero; unavailable cache-write counts remain
`null`. Streaming chat model observations retain the latest provider usage snapshot
in both request modes, including cost and detailed counts.

Chat and image streams record request-mapping and transport errors on their observation.
Each subscription stops its observation on completion, error, or cancellation.

The starter provides the Spring AI handlers, but it does not choose monitoring backends
for the application:

- Metrics require a `MeterRegistry`. Spring Boot Actuator supplies the Boot observation
  wiring and a registry; add the registry/exporter for your monitoring system to publish
  them externally.
- Traces require a Micrometer `Tracer` bridge and tracing exporter.
- Prompt, completion, image-prompt, and error logging are opt-in through Spring AI's
  standard properties. Content logging does not require an exporter; when a `Tracer` is
  present its handlers become tracing-aware, and error logging requires that tracer.

```yaml
spring:
  ai:
    chat:
      observations:
        log-prompt: false
        log-completion: false
        include-error-logging: false
    image:
      observations:
        log-prompt: false
```

Enabling content logging can expose sensitive prompt or model output data. Applications
may supply their own model observation handlers or
`ChatModelObservationConvention`, `EmbeddingModelObservationConvention`, and
`ImageModelObservationConvention` beans. Boot backs off a standard handler when the
application supplies the same handler type, and the OpenRouter models use the custom
conventions.

## The Garage demo

[`openrouter-spring-ai-samples`](openrouter-spring-ai-samples) is a small story: a garage
foreman model inspects a customer's car, delegates one job to a specialist model, and writes a
service record. A normal run stays easy to read; `--full` turns it into a capability tour across
OpenRouter's OpenAI-compatible chat-completions format. Together they exercise system and user
messages, sync chat, streaming, tool calling with mixed parameter schemas, real file I/O side
effects, model-to-model delegation, model fallback lists, provider routing preferences, service
tier, reasoning options, usage and cost metadata, and request metadata. Its modality bays cover
the non-chat surfaces: an embeddings triage matcher, a digital inspection bay that reads a
bundled dashboard photo (image input, both request modes), and a paint bay that generates
images through the Image API (sync and streaming) and chat-completions modalities.

The modality bays correlate completed chat, embedding, and image observations with the
scene operation. Evidence includes timing, errors, modality-specific counts and sizes,
and available usage and cost, with the same sanitization applied to telemetry snapshots
and persisted reports. Image streams publish observation evidence after completion,
error, or cancellation. Calls and stream subscriptions must start inside the sample's
operation scope. These checks use synthetic model responses in the sample test suite.

It doubles as the library's live test harness. Every run asserts its own structural
outcome (service record written, every required tool actually invoked, usage metadata present,
non-empty final answer, and streaming signals when requested) and fails loudly otherwise — these
assertions have caught real bugs that the model's confident prose hid, like tools being silently
dropped from requests or streamed images blowing the default SSE codec limit. Every live
finding becomes a replayable unit test.

The service-story reasoning check accepts reasoning text or positive reasoning-token usage
from any Foreman tool-loop round; the final answer need not repeat that evidence.

```bash
mvn -pl openrouter-spring-ai-samples package
OPENROUTER_API_KEY=$(cat openrouter.key) java -jar openrouter-spring-ai-samples/target/*.jar \
    --topic="1987 diesel pickup, hard cold starts" --full
```

Garage diagnostic JSON, Markdown, and sweep files retain only allowlisted fields and fixed
labels, numeric measurements, booleans, and generated operation identifiers. Free-form text,
model/provider names, paths, tool payloads, exception messages, and unknown objects are omitted
or redacted; raw diagnostic payload retention is not supported. Sweep results preserve input
order, so match each result to the corresponding command-line entry. Authored service records
and generated media are separate application outputs and may contain customer or model content.
These report protections do not sanitize console logs.

Tool-loop evidence requires model-selected tools and correlated tool results sent in a
follow-up request. The service story must complete inspection, specialist delegation,
priority scoring, and service-record tools; a text-only answer cannot certify it.
Structured-output probes test `responseFormat` and `outputSchema` independently.
Feature reports retain each selected request mode's status. Mixed outcomes are `partial`,
and a failed, missing, or incomplete required mode fails the run. Unsupported modes remain
explicit; embeddings and image generation are checked once because those APIs are mode-independent.

Select capabilities independently of the pipeline schedule:

```bash
java -jar garage.jar --text
java -jar garage.jar --embedding
java -jar garage.jar --text --embedding --vision
java -jar garage.jar --image --image-surface=sync
```

Here `garage.jar` stands for the packaged samples JAR. `--vision` checks image input;
`--image` generates images (sync by default; `streaming`, `chat`, and `all` are also
available). `--text` selects text, tools, streaming, and offline text contracts in both
request modes; `--request-mode=chat` narrows it. `--full` additionally includes routing
and all modalities. With no selection flags, the original service-story demo runs.
`--scene=<ids>` can narrow a capability suite; selected modality flags require
`modality-bays` in that list. `--offline-contracts` runs only local contracts.

Boot arguments such as `--spring.profiles.active=coverage` and
`--spring.main.banner-mode=off` work alongside Garage flags. Namespaced properties
use `--key=value`; Boot's `--debug` and `--trace` flags are also accepted.
Unknown `garage.*` properties fail during binding before any scene runs.
Garage CLI overrides take precedence over bound properties, including
`--specialist-model`. With no explicit selection, `garage.stream=true` adds
`streaming-dispatch` to the service-story demo. Explicit scene, capability, full,
offline, or sweep selections take precedence over that property default.
The `--stream` flag explicitly adds streaming to the current scene selection.

Models are selected independently: use `--foreman-model`, `--specialist-model`,
`--embedding-model`, `--vision-model`, and `--image-model` as appropriate.
The capability flags do not imply free models. Completion limits and provider
preferences are explicit options shown by `--help`. Garage records what a run cost and
reports it, but enforces no ceiling of its own; cap spending with a credit limit on the
OpenRouter API key instead, which is the only control that can stop a request before it
is billed.

The PR workflow selects a smaller text suite with a free model. Nightly uses
`--text --embedding --vision`; weekly uses `--image` and rotates the image interface.
Scheduling and model selection live in the workflows, not schedule-named application
profiles. Nightly allows 900 completion tokens per Foreman request to leave room for
reasoning, tool arguments, and final output.
Garage enables usage reporting per Chat Completions request rather than as a global
chat default, so `ChatClient` can also use Responses mode. Structured-output probes
require providers to support all requested parameters, including the JSON schema.
`--auto` and `--max-cost-usd` remain accepted as deprecated no-ops; failures always return
a nonzero exit code.

Each scene run writes `capability-report.md`, `garage-run.json`, and a bundle `README.md`.

## Maven and Gradle builds

Maven and Gradle build the same three published thin library JARs. Versions and BOM
baselines come from the root Maven POM, and CI builds and tests both build systems across the
supported Java versions. The executable samples application is intentionally separate.

The standalone `native-smoke-tests` consumer uses this checkout through a Gradle composite
build. With GraalVM 25 and `native-image` installed, run:

```bash
gradle --no-daemon -p native-smoke-tests nativeCompile
native-smoke-tests/build/native/nativeCompile/native-smoke
```

On Windows the executable has an `.exe` suffix. `gradle --no-daemon -p native-smoke-tests bootRun`
runs the same fixtures on the JVM. CI compiles and executes the native consumer; compilation
alone is not the gate. The checks use only a loopback HTTP server and synthetic data, without
external credentials. They cover chat, Responses, embeddings, images, HTTP and in-band errors,
SSE text/tool fragments, terminal metadata, image JSON fallback, and cancellation. Chat
model auto-configuration is disabled in this consumer to verify that API serialization hints
remain available independently of tool-manager hints. This verifies the provider's wire
contracts; it does not certify arbitrary application DTOs or live upstream providers.

## Build quality checks

The library and samples compile with `--release 17`. Run `mvn -B verify` and
`gradle --no-daemon check` for tests and static analysis. Checkstyle runs on JDK 21+
only. Library sources use Spring formatting; samples retain their existing layout
and enforce `EqualsHashCode`, `FallThrough`, `EmptyStatement`, and
`StringLiteralEquality` through `config/checkstyle/checkstyle-samples.xml`.
Both builds apply the shared PMD rules to production and test sources, including
samples. Gradle excludes generated Spring AOT source sets from these checks,
matching Maven's maintained-source scope. Three sample classes suppress only
duplicate literals to keep registry rows and test inputs explicit. Sample CPD remains deferred because scenario and
fixture duplication is intentional; Gradle has no CPD task. Sample formatter and
Enforcer exclusions are unchanged.

On JDK 25, `mvn -B -Pmodernizer-java25 verify` also runs Modernizer 3.4.0 with an
explicit Java 25 analysis target on every module's production and test bytecode.
The profile activates automatically on JDK 25+. The JDK 25 Maven CI leg is the
shared Modernizer enforcement point for both build systems; Gradle `check` does
not invoke it. Modernizer checks its known API catalog, not every newer Java
feature. Compiler release checking still protects Java 17 compatibility.

The Java 17-compatible recursive list snapshot and content joining simplifications
are applied. `Math.clamp`, `List.getFirst`/`getLast`, and pattern switches in
assistant content and terminal-event handling remain deferred until the minimum
Java version changes. Existing null handling, collection guards, and terminal
error semantics remain in place.

Protocol coverage is measured by JaCoCo reports in each module's
`target/site/jacoco` or `build/reports/jacoco/test` directory. A numeric branch gate
is deliberately deferred. The 2026-09-17 core baseline covers 99/110 branches in
`OpenRouterApi`, 112/140 across the tool aggregator and its nested classes,
84/108 in the Responses stream mapper, and 42/55 in reasoning merging.
A percentage cannot establish correct fragment ordering, cancellation, or terminal
error handling. Those requirements remain enforced by the synthetic streaming,
reasoning replay, and tool aggregation contract tests. Reports are evidence of
coverage, not a coverage gate.
