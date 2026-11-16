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
  <a href="https://github.com/Subhransu-De/openrouter-spring-ai/actions/workflows/codeql.yml"><img src="https://img.shields.io/github/actions/workflow/status/Subhransu-De/openrouter-spring-ai/codeql.yml?branch=main&amp;label=CodeQL&amp;style=flat" alt="CodeQL status"></a>
</p>

> [!IMPORTANT]
> **Under active development.** This library has not reached 1.0.0, so public names and options can still change between releases. The [changelog](CHANGELOG.md) lists every change with upgrade notes. Please try it in your projects and [open an issue](https://github.com/Subhransu-De/openrouter-spring-ai/issues) when something breaks or is missing.

A native [Spring AI](https://spring.io/projects/spring-ai) integration for [OpenRouter](https://openrouter.ai), which routes each request to an upstream model provider. It adds typed model fallbacks, provider routing, reasoning replay, cost accounting and attribution headers under `spring.ai.openrouter.*`. Spring AI's OpenAI integration can also send OpenRouter fields through [`extraBody`](https://github.com/spring-projects/spring-ai/blob/v2.0.2/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java). This library adds typed support for them.

> **Community project:** This library is independently maintained and is not affiliated with, endorsed by, or an official project of OpenRouter or Spring AI.
>
> Install only one OpenRouter Spring AI starter in an application. Combining this starter with another OpenRouter starter can make provider selection and bean creation order-dependent.

[Getting started](docs/getting-started.md) · [Documentation](docs/README.md) · [Changelog](CHANGELOG.md)

![A call goes from your app through Spring AI's ChatClient into this library's model and API client, then to OpenRouter, which routes it to an upstream provider. The modules are the core library, its auto-configuration, and the starter that applications add.](docs/images/architecture.svg)

## What it does

- Chat through OpenRouter's Chat Completions endpoint, with synchronous calls and SSE streaming. The Responses endpoint is an experimental opt-in.
- Tool calling through Spring AI's `ToolCallingAdvisor`, including streamed argument fragments and [strict function tools](docs/tool-calling.md#strict-function-tools).
- [Reasoning options](docs/reasoning-and-caching.md), with reasoning text and opaque reasoning state replayed across tool rounds.
- [Model fallback lists, provider routing, service tiers and attribution headers](docs/chat-options.md#routing-fallbacks-and-attribution), plus [optional OpenRouter fields](docs/extensions.md).
- Usage accounting with cost, cached and reasoning tokens, and Micrometer observations.
- Embeddings behind Spring AI's `EmbeddingModel`, and text and image documents behind `DocumentEmbeddingModel`. See [Embeddings](docs/embeddings.md).
- Image, PDF, audio and video inputs, image generation, and streamed chat audio output. See [Images, documents and audio](docs/multimodal.md).
- Text-to-speech behind Spring AI's `TextToSpeechModel`, with streamed audio, and transcription behind `TranscriptionModel`. See [Text-to-speech](docs/multimodal.md#text-to-speech) and [Transcription](docs/multimodal.md#transcription).
- Spring Boot auto-configuration under `spring.ai.openrouter.*`.

Not supported yet: the models catalogue endpoint, OpenRouter server-side tools such as the web search plugin, voice cloning and multi-speaker speech, and multipart transcription uploads.

## Install

Add the starter from Maven Central:

```xml
<dependency>
    <groupId>de.subhransu</groupId>
    <artifactId>openrouter-spring-ai-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("de.subhransu:openrouter-spring-ai-starter:0.1.0")
```

Use Java 17 or later. See [release compatibility](docs/getting-started.md#release-compatibility) for Spring Boot and Spring AI versions.

## Examples

### Setup

Spring Boot reads the key from configuration and builds `OpenRouterApi`, `ChatClient.Builder` and the three models for you. The examples below use an injected `ChatClient` named `chatClient`, `EmbeddingModel embeddingModel` and `OpenRouterImageModel imageModel`. Declare a model bean only when you need different defaults.

<details open>
<summary>Configuration, Chat model bean, Embedding model bean, Image model bean, Without auto-configuration</summary>

<table>
<tr>
<td width="160" valign="top">

**Configuration**<br>
The key comes from the environment.

</td>
<td>

```yaml
spring:
  ai:
    model:
      chat: openrouter
      embedding: openrouter
      image: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      chat:
        model: openai/gpt-oss-120b
      embedding:
        model: openai/text-embedding-3-small
      image:
        model: bytedance-seed/seedream-4.5
```

</td>
<td valign="top"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Chat model bean**<br>
Replaces the auto-configured chat model.

</td>
<td>

```java
@Bean
OpenRouterChatModel chatModel(OpenRouterApi openRouterApi) {
  return OpenRouterChatModel.builder()
      .openRouterApi(openRouterApi)
      .defaultOptions(OpenRouterChatOptions.builder().model("openai/gpt-oss-120b").build())
      .build();
}
```

</td>
<td valign="top"><a href="docs/chat-options.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Embedding model bean**<br>
Replaces the auto-configured embedding model.

</td>
<td>

```java
@Bean
OpenRouterEmbeddingModel embeddingModel(OpenRouterApi openRouterApi) {
  return OpenRouterEmbeddingModel.builder()
      .openRouterApi(openRouterApi)
      .defaultOptions(
          OpenRouterEmbeddingOptions.builder().model("openai/text-embedding-3-small").build())
      .build();
}
```

</td>
<td valign="top"><a href="docs/embeddings.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Image model bean**<br>
Replaces the auto-configured image model.

</td>
<td>

```java
@Bean
OpenRouterImageModel imageModel(OpenRouterApi openRouterApi) {
  return OpenRouterImageModel.builder()
      .openRouterApi(openRouterApi)
      .defaultOptions(
          OpenRouterImageOptions.builder().model("bytedance-seed/seedream-4.5").build())
      .build();
}
```

</td>
<td valign="top"><a href="docs/multimodal.md#image-generation">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Without auto-configuration**<br>
Bind the key into a field.

</td>
<td>

```java
@Configuration
class OpenRouterConfig {

  @Value("${spring.ai.openrouter.api-key}")
  private String apiKey;

  @Bean
  OpenRouterApi openRouterApi() {
    return OpenRouterApi.builder().apiKey(apiKey).build();
  }
}
```

</td>
<td valign="top"><a href="docs/api.md">Docs</a></td>
</tr>
</table>

</details>

### Text

<details open>
<summary>Text generation, Streaming, Per-request options, Structured output, Reasoning, Tool calling, Responses mode</summary>

<table>
<tr>
<td width="160" valign="top">

**Text generation**<br>
One blocking call.

</td>
<td>

```java
String answer = chatClient.prompt().user("Say hello.").call().content();
```

</td>
<td valign="top"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Streaming**<br>
Text arrives as server-sent events.

</td>
<td>

```java
Flux<String> tokens = chatClient.prompt().user("Tell a short story.").stream().content();
```

</td>
<td valign="top"><a href="docs/request-modes.md#stream-contract">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Per-request options**<br>
Unset fields keep the defaults.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().temperature(0.0).maxCompletionTokens(200))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/chat-options.md#replacement-and-composition">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Structured output**<br>
Typed records from a JSON schema.

</td>
<td>

```java
record Answer(String answer) {}

Answer answer =
    chatClient
        .prompt()
        .user("What is the capital of Norway?")
        .options(
            OpenRouterChatOptions.builder()
                .responseFormat(
                    OpenRouterResponseFormat.jsonSchema(
                        """
                        {"type":"object",
                         "properties":{"answer":{"type":"string"}},
                         "required":["answer"],
                         "additionalProperties":false}\
                        """)))
        .call()
        .entity(Answer.class);
```

</td>
<td valign="top"><a href="docs/request-modes.md#structured-output">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Reasoning**<br>
Read the model's thinking separately.

</td>
<td>

```java
ChatResponse response =
    chatClient
        .prompt()
        .user("Plan a three-step migration.")
        .options(
            OpenRouterChatOptions.builder()
                .reasoning(
                    OpenRouterReasoningOptions.builder()
                        .effort(OpenRouterReasoningEffort.MEDIUM)
                        .build()))
        .call()
        .chatResponse();
String thinking = OpenRouterMetadata.reasoning(response.getResult()).orElse("");
```

</td>
<td valign="top"><a href="docs/reasoning-and-caching.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Tool calling**<br>
Spring AI runs the tool loop.

</td>
<td>

```java
class Weather {

  @Tool(description = "Current temperature in a city")
  String temperature(String city) {
    return "21 C in " + city;
  }
}

String answer =
    chatClient.prompt().user("How warm is it in Oslo?").tools(new Weather()).call().content();
```

</td>
<td valign="top"><a href="docs/tool-calling.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Responses mode** <sup>experimental</sup><br>
Sends to the /responses endpoint.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.RESPONSES))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/request-modes.md">Docs</a></td>
</tr>
</table>

</details>

### Routing

<details>
<summary>Model fallbacks, Provider routing, Service tier, Sessions and traces, Attribution headers</summary>

<table>
<tr>
<td width="160" valign="top">

**Model fallbacks**<br>
Tried in order when one fails.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Summarize this ticket.")
        .options(
            OpenRouterChatOptions.builder()
                .model("openai/gpt-oss-120b")
                .models(List.of("openai/gpt-oss-20b")))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/chat-options.md#routing-fallbacks-and-attribution">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Provider routing**<br>
Pick, order and sort providers.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(
            OpenRouterChatOptions.builder()
                .provider(
                    OpenRouterProviderPreferences.builder()
                        .sort(OpenRouterProviderSort.THROUGHPUT)
                        .build()))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/chat-options.md#routing-fallbacks-and-attribution">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Service tier**<br>
Trade speed for price.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().serviceTier(OpenRouterServiceTier.FLEX))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/chat-options.md#routing-fallbacks-and-attribution">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Sessions and traces**<br>
Keep a conversation on one provider.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user(question)
        .options(
            OpenRouterChatOptions.builder()
                .sessionId(conversationId)
                .trace(OpenRouterTrace.builder().traceId(runId).build()))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/chat-options.md#sessions-and-traces">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Attribution headers**<br>
Your app on OpenRouter rankings.

</td>
<td>

```yaml
spring:
  ai:
    openrouter:
      app:
        http-referer: https://example.com
        title: Example app
```

</td>
<td valign="top"><a href="docs/chat-options.md#routing-fallbacks-and-attribution">Docs</a></td>
</tr>
</table>

</details>

### Inputs

<details>
<summary>Image input, PDF input, Audio input, Video input</summary>

<table>
<tr>
<td width="160" valign="top">

**Image input**<br>
Needs a model that reads images.

</td>
<td>

```java
UserMessage message =
    UserMessage.builder()
        .text("What is in this image?")
        .media(
            Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(URI.create("https://example.com/cat.png"))
                .build())
        .build();
String answer =
    chatClient
        .prompt()
        .messages(message)
        .options(OpenRouterChatOptions.builder().model("google/gemini-2.5-flash"))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/multimodal.md#image-inputs">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**PDF input**<br>
A URL, bytes or a data URL.

</td>
<td>

```java
UserMessage message =
    UserMessage.builder()
        .text("Summarize the attached document.")
        .media(
            Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType("application/pdf"))
                .data(URI.create("https://example.com/report.pdf"))
                .build())
        .build();
String summary =
    chatClient
        .prompt()
        .messages(message)
        .options(OpenRouterChatOptions.builder().model("google/gemini-2.5-flash"))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/multimodal.md#pdf-audio-and-video-inputs">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Audio input**<br>
Bytes only; URLs are rejected.

</td>
<td>

```java
UserMessage message =
    UserMessage.builder()
        .text("Transcribe this clip.")
        .media(
            Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType("audio/wav"))
                .data(Files.readAllBytes(Path.of("clip.wav")))
                .build())
        .build();
String transcript =
    chatClient
        .prompt()
        .messages(message)
        .options(OpenRouterChatOptions.builder().model("google/gemini-2.5-flash"))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/multimodal.md#pdf-audio-and-video-inputs">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Video input**<br>
URL support depends on the provider.

</td>
<td>

```java
UserMessage message =
    UserMessage.builder()
        .text("What happens in this video?")
        .media(
            Media.builder()
                .mimeType(MimeTypeUtils.parseMimeType("video/mp4"))
                .data(URI.create("https://example.com/clip.mp4"))
                .build())
        .build();
String answer =
    chatClient
        .prompt()
        .messages(message)
        .options(OpenRouterChatOptions.builder().model("google/gemini-2.5-flash"))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/multimodal.md#pdf-audio-and-video-inputs">Docs</a></td>
</tr>
</table>

</details>

### Outputs

<details>
<summary>Image generation, Image streaming, Image to image, Images from a chat model, Audio output</summary>

<table>
<tr>
<td width="160" valign="top">

**Image generation**<br>
Base64 images from the Image API.

</td>
<td>

```java
ImageResponse response = imageModel.call(new ImagePrompt("a red panda wearing sunglasses"));
String base64 = response.getResult().getOutput().getB64Json();
```

</td>
<td valign="top"><a href="docs/multimodal.md#image-generation">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Image streaming**<br>
Previews first, then the final image.

</td>
<td>

```java
Flux<ImageResponse> events =
    imageModel.stream(new ImagePrompt("a red panda wearing sunglasses"));
events.subscribe(
    response ->
        response
            .getResults()
            .forEach(
                result -> {
                  boolean preview = OpenRouterImageGenerationMetadata.from(result).isPartial();
                }));
```

</td>
<td valign="top"><a href="docs/multimodal.md#streaming">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Image to image**<br>
Start from a reference image.

</td>
<td>

```java
ImageResponse response =
    imageModel.call(
        new ImagePrompt(
            "the same sketch as a watercolour",
            OpenRouterImageOptions.builder()
                .inputReferences(List.of("https://example.com/sketch.png"))
                .build()));
```

</td>
<td valign="top"><a href="docs/multimodal.md#image-options">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Images from a chat model**<br>
Images next to text in a reply.

</td>
<td>

```java
ChatResponse response =
    chatClient
        .prompt()
        .user("Draw a lighthouse at dusk.")
        .options(
            OpenRouterChatOptions.builder()
                .model("google/gemini-2.5-flash-image")
                .modalities(OpenRouterModality.IMAGE, OpenRouterModality.TEXT))
        .call()
        .chatResponse();
for (Media image : response.getResult().getOutput().getMedia()) {
  byte[] png = OpenRouterMedia.bytes(image);
}
```

</td>
<td valign="top"><a href="docs/multimodal.md#chat-models-with-image-output">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Audio output**<br>
Streaming only.

</td>
<td>

```java
Flux<ChatResponse> stream =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(
            OpenRouterChatOptions.builder()
                .model("openai/gpt-4o-audio-preview")
                .modalities(OpenRouterModality.TEXT, OpenRouterModality.AUDIO)
                .audio(new OpenRouterAudioOptions("alloy", "wav")))
        .stream()
        .chatResponse();
stream.subscribe(
    response ->
        response
            .getResults()
            .forEach(
                result ->
                    result
                        .getOutput()
                        .getMedia()
                        .forEach(
                            media -> {
                              byte[] wav = OpenRouterMedia.bytes(media);
                            })));
```

</td>
<td valign="top"><a href="docs/multimodal.md#chat-audio-output">Docs</a></td>
</tr>
</table>

</details>

### Embeddings

<details>
<summary>Text embedding, Batch with options</summary>

<table>
<tr>
<td width="160" valign="top">

**Text embedding**<br>
Works with Spring AI vector stores.

</td>
<td>

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

</td>
<td valign="top"><a href="docs/embeddings.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Batch with options**<br>
Options merge with the defaults.

</td>
<td>

```java
EmbeddingResponse response =
    embeddingModel.call(
        new EmbeddingRequest(
            List.of("first text", "second text"),
            OpenRouterEmbeddingOptions.builder().dimensions(256).build()));
```

</td>
<td valign="top"><a href="docs/embeddings.md">Docs</a></td>
</tr>
</table>

</details>

### Caching and usage

<details>
<summary>Prompt cache breakpoint, Usage and cost, Extra request fields, Low-level request</summary>

<table>
<tr>
<td width="160" valign="top">

**Prompt cache breakpoint**<br>
Cache a stable prompt prefix.

</td>
<td>

```java
String reference = "Reference material to reuse.\n";
UserMessage message =
    UserMessage.builder()
        .text(reference + "Answer this turn's question.")
        .metadata(
            Map.of(
                OpenRouterCacheBreakpoint.METADATA_KEY,
                List.of(new OpenRouterCacheBreakpoint(reference.length()))))
        .build();
String answer = chatClient.prompt().messages(message).call().content();
```

</td>
<td valign="top"><a href="docs/reasoning-and-caching.md#explicit-prompt-cache-breakpoints">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Usage and cost**<br>
Credits charged, tokens used.

</td>
<td>

```java
ChatResponse response = chatClient.prompt().user("Say hello.").call().chatResponse();
OpenRouterUsage usage = OpenRouterUsage.from(response);
Double cost = usage.getCost(); // null when not reported
```

</td>
<td valign="top"><a href="docs/observability.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Extra request fields**<br>
Fields without a typed option.

</td>
<td>

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().extraBody(Map.of("transforms", List.of())))
        .call()
        .content();
```

</td>
<td valign="top"><a href="docs/extensions.md">Docs</a></td>
</tr>
<tr>
<td width="160" valign="top">

**Low-level request**<br>
Inject OpenRouterApi; no Spring AI types.

</td>
<td>

```java
ChatCompletionRequest request =
    ChatCompletionRequest.builder()
        .model("openai/gpt-oss-120b")
        .messages(List.of(new ChatMessage("user", "Say hello.", null, null, null)))
        .build();
ChatCompletionResponse response = openRouterApi.chatCompletion(request);
```

</td>
<td valign="top"><a href="docs/api.md#build-low-level-requests">Docs</a></td>
</tr>
</table>

</details>

## Documentation

| Page                                         | Covers                                                                                                                          |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| [Getting started](docs/getting-started.md)   | Install, first call, properties, compatibility, model selection.                                                                |
| [Chat options](docs/chat-options.md)         | How runtime options combine with defaults; fallbacks, provider routing and attribution.                                         |
| [Request modes](docs/request-modes.md)       | Chat Completions and experimental Responses.                                                                                    |
| [Supported API and nullability](docs/api.md) | Types applications can depend on, null contracts.                                                                               |
| [Tool calling](docs/tool-calling.md)         | Tools, strict schemas, failure policy.                                                                                          |
| [More pages](docs/README.md)                 | Extensions, reasoning and caching, errors and retries, HTTP and limits, multimodal, embeddings, observability, the Garage demo. |

## Modules

Applications add `openrouter-spring-ai-starter`, which brings in the core library `openrouter-spring-ai` and its auto-configuration. `openrouter-spring-ai-samples` is the [Garage demo](docs/garage.md).

## License

Released under the [MIT License](LICENSE).
