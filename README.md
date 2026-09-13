# openrouter-spring-ai

[![Build](https://github.com/Subhransu-De/spring-ai-openrouter-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/Subhransu-De/spring-ai-openrouter-starter/actions/workflows/ci.yml)

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

### Request-mode option contract

Both modes map `outputSchema` and `responseFormat`: Chat Completions uses
`response_format`, and Responses uses `text.format`. An explicit `responseFormat`
takes precedence over `outputSchema`. Portable schemas leave `strict` unset;
`OpenRouterResponseFormat.jsonSchema(name, strict, schema)` preserves an explicit
`true` or `false`. Schema enforcement depends on the selected provider. Function tools
leave `strict` unset in both modes; this library does not currently expose a function-tool
strictness option or rewrite tool schemas to satisfy strict-mode requirements.

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
- [ ] Video, audio and PDF input modalities; text-to-speech and transcription

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

Register tools on the request options (or the `ChatClient`), not as model default
options: the advisor executes with the options it sees on the prompt. Tools declared only
by bean name are resolved through the `ToolCallbackResolver` configured on the
`ToolCallingManager` during execution.

### Reasoning conversation state

Assistant message metadata carries `openrouter.reasoning` (text) and
`openrouter.reasoning_details` (an ordered list of opaque JSON nodes) in Chat Completions
mode. Responses mode retains complete reasoning output items under
`openrouter.responses.reasoning_items`, including encrypted content and unknown fields.
The accompanying `openrouter.responses.output_items` snapshot preserves their positions
relative to text and function calls during replay.
Request mappers replay this state when the assistant message is included in conversation
history. Keep the original assistant message and its metadata when adding tool results.

Streaming assembles consecutive text and summary detail fragments, while retaining
encrypted and unknown detail types as opaque items. Assistant metadata contains
cumulative snapshots per choice and subscription,
so Spring AI message aggregation retains the complete reasoning state. Text content remains
incremental. The existing generation metadata key `openrouter.reasoning` remains available
for streamed reasoning deltas and synchronous reasoning text.

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

Non-image media (video, audio, PDF) is rejected explicitly for now rather than silently
dropped; those modalities are on the roadmap.

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
`OpenRouterImageModel.stream(ImagePrompt)` exposes OpenRouter's SSE image streaming:
partial previews arrive first (see `OpenRouterImageGenerationMetadata.partialImageIndex()`),
then the completed image with usage and cost.

Image-capable _chat_ models work too: set
`OpenRouterChatOptions.builder().modalities(List.of("image", "text"))` (optionally with
`imageConfig`) and generated images arrive as `AssistantMessage` media — in sync calls
and streams alike, in both chat-completions and responses request modes.

### Observability

The starter includes Spring AI's standard observation auto-configuration for chat,
embedding, and image models. OpenRouter model calls emit `gen_ai.client.operation`
observations with the `openrouter` provider, operation, request model, response model
where available, and token-usage context. When a `MeterRegistry` is present, chat and
embedding usage is also recorded as `gen_ai.client.token.usage`.

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

It doubles as the library's live test harness. Every run asserts its own structural
outcome (service record written, every required tool actually invoked, usage metadata present,
non-empty final answer, and streaming signals when requested) and fails loudly otherwise — these
assertions have caught real bugs that the model's confident prose hid, like tools being silently
dropped from requests or streamed images blowing the default SSE codec limit. Every live
finding becomes a replayable unit test.

```bash
mvn -pl openrouter-spring-ai-samples package
OPENROUTER_API_KEY=$(cat openrouter.key) java -jar openrouter-spring-ai-samples/target/*.jar \
    --topic="1987 diesel pickup, hard cold starts" --full
```

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

Models and spending settings are independent: use `--foreman-model`, `--specialist-model`,
`--embedding-model`, `--vision-model`, `--image-model`, and `--max-cost-usd` as appropriate.
The capability flags do not imply free models or a spending cap. Completion limits and
provider preferences are explicit options shown by `--help`. Cost limits are checked
after execution and do not prevent already-issued requests from charging.

The PR workflow selects a smaller text suite with a free model and a zero recorded-cost
threshold. Nightly uses `--text --embedding --vision` with a USD 0.002 threshold; weekly
uses `--image` with a USD 0.05 threshold and rotates the image interface. Scheduling and
model selection live in the workflows, not schedule-named application profiles.
`--auto` remains accepted as a deprecated no-op; failures always return a nonzero exit code.

Each scene run writes `capability-report.md`, `garage-run.json`, and a bundle `README.md`.

## Maven and Gradle builds

Maven and Gradle build the same three published thin library JARs. Versions and BOM
baselines come from the root Maven POM, and CI builds and tests both build systems across the
supported Java versions. The executable samples application is intentionally separate.
