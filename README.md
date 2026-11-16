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
- Tool calling through Spring AI's `ToolCallingAdvisor`, including streamed argument fragments and [strict function tools](docs/chat.md#strict-function-tools).
- [Reasoning options](docs/chat.md#reasoning), with reasoning text and opaque reasoning state replayed across tool rounds.
- Model fallback lists, provider routing, service tiers and attribution headers, plus [optional OpenRouter fields](docs/chat.md#extra-request-fields).
- Usage accounting with cost, cached and reasoning tokens, and Micrometer observations.
- Embeddings behind Spring AI's `EmbeddingModel`, and text and image documents behind `DocumentEmbeddingModel`. See [Embeddings](docs/media-and-embeddings.md).
- Image, PDF, audio and video inputs, image generation, and streamed chat audio output. See [Images, documents and audio](docs/media-and-embeddings.md).
- Text-to-speech behind Spring AI's `TextToSpeechModel`, with streamed audio, and transcription behind `TranscriptionModel`. See [Text-to-speech](docs/media-and-embeddings.md#text-to-speech) and [Transcription](docs/media-and-embeddings.md#transcription).
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

The examples follow `main` (0.2.0-SNAPSHOT). Release 0.1.0 lacks some of these types and names; see the [changelog](CHANGELOG.md). Each call is a billable OpenRouter request.

### Setup

Spring Boot reads the key from configuration and builds `OpenRouterApi`, `ChatClient.Builder` and the chat, embedding and image models for you. The speech, transcription and document embedding models are opt-in. The examples below use these injected beans: `ChatClient chatClient`, `EmbeddingModel embeddingModel`, `OpenRouterImageModel imageModel`, `TextToSpeechModel speechModel`, `TranscriptionModel transcriptionModel` and `DocumentEmbeddingModel documentEmbeddingModel`.

Set defaults with properties. Declare a model bean for a second model or without Boot; that bean ignores `spring.ai.openrouter.*`, and a chat bean owns the tool failure policy.

<details open>
<summary>Configuration, Chat client, Opt-in models, Chat model bean, Embedding model bean, Document embedding model bean, Image model bean, Speech model bean, Transcription model bean, OpenRouterApi bean</summary>

<table>
<tr>
<td><b>Configuration</b> · The key comes from the environment.</td>
<td align="right"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Chat client</b> · Boot provides the builder; build the client once.</td>
<td align="right"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
  return builder.build();
}
```

</td>
</tr>
<tr>
<td><b>Opt-in models</b> · Speech, transcription and document embeddings are off until selected.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-to-speech">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```yaml
spring:
  ai:
    model:
      embedding.multimodal: openrouter
      audio:
        speech: openrouter
        transcription: openrouter
    openrouter:
      embedding:
        model: mongodb/voyage-multimodal-3.5
      audio:
        speech:
          model: hexgrad/kokoro-82m
          voice: af_heart
        transcription:
          model: openai/whisper-1
```

</td>
</tr>
<tr>
<td><b>Chat model bean</b> · Replaces the auto-configured chat model.</td>
<td align="right"><a href="docs/getting-started.md#next-to-another-provider-starter">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterChatModel chatModel(OpenRouterApi openRouterApi) {
  return OpenRouterChatModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterChatOptions.builder().model("openai/gpt-oss-120b").build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>Embedding model bean</b> · Replaces the auto-configured embedding model.</td>
<td align="right"><a href="docs/getting-started.md#next-to-another-provider-starter">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterEmbeddingModel embeddingModel(OpenRouterApi openRouterApi) {
  return OpenRouterEmbeddingModel.builder()
      .openRouterApi(openRouterApi)
      .options(
          OpenRouterEmbeddingOptions.builder().model("openai/text-embedding-3-small").build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>Document embedding model bean</b> · Replaces the auto-configured document embedding model.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-and-image-documents">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterDocumentEmbeddingModel documentEmbeddingModel(OpenRouterApi openRouterApi) {
  return OpenRouterDocumentEmbeddingModel.builder()
      .openRouterApi(openRouterApi)
      .options(
          OpenRouterEmbeddingOptions.builder().model("mongodb/voyage-multimodal-3.5").build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>Image model bean</b> · Replaces the auto-configured image model.</td>
<td align="right"><a href="docs/getting-started.md#next-to-another-provider-starter">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterImageModel imageModel(OpenRouterApi openRouterApi) {
  return OpenRouterImageModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterImageOptions.builder().model("bytedance-seed/seedream-4.5").build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>Speech model bean</b> · Replaces the auto-configured speech model.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-to-speech">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterAudioSpeechModel speechModel(OpenRouterApi openRouterApi) {
  return OpenRouterAudioSpeechModel.builder()
      .openRouterApi(openRouterApi)
      .options(
          OpenRouterAudioSpeechOptions.builder()
              .model("hexgrad/kokoro-82m")
              .voice("af_heart")
              .build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>Transcription model bean</b> · Replaces the auto-configured transcription model.</td>
<td align="right"><a href="docs/media-and-embeddings.md#transcription">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
@Bean
OpenRouterAudioTranscriptionModel transcriptionModel(OpenRouterApi openRouterApi) {
  return OpenRouterAudioTranscriptionModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterAudioTranscriptionOptions.builder().model("openai/whisper-1").build())
      .build();
}
```

</td>
</tr>
<tr>
<td><b>OpenRouterApi bean</b> · Replaces the auto-configured client; start here without Boot.</td>
<td align="right"><a href="docs/api.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
</table>

</details>

### Text

<details open>
<summary>Text generation, Streaming, Per-request options, Structured output, Reasoning, Tool calling, Strict tools, Responses mode, Log probabilities, Predicted output</summary>

<table>
<tr>
<td><b>Text generation</b> · One blocking call.</td>
<td align="right"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String answer = chatClient.prompt().user("Say hello.").call().content();
```

</td>
</tr>
<tr>
<td><b>Streaming</b> · Text arrives as server-sent events.</td>
<td align="right"><a href="docs/getting-started.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
Flux<String> tokens = chatClient.prompt().user("Tell a short story.").stream().content();
```

</td>
</tr>
<tr>
<td><b>Per-request options</b> · Unset fields keep the defaults.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Structured output</b> · Typed records from a JSON schema.</td>
<td align="right"><a href="docs/chat.md#structured-output">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Reasoning</b> · Read the model's thinking separately.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Tool calling</b> · Spring AI runs the tool loop.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Strict tools</b> · Checks each tool schema before the request.</td>
<td align="right"><a href="docs/chat.md#strict-function-tools">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String answer =
    chatClient
        .prompt()
        .user("How warm is it in Oslo?")
        .tools(new Weather())
        .options(OpenRouterChatOptions.builder().toolStrict(true))
        .call()
        .content();
```

</td>
</tr>
<tr>
<td><b>Responses mode</b> <sup>experimental</sup> · Sends to the /responses endpoint.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Log probabilities</b> · Each token's log probability and its alternatives.</td>
<td align="right"><a href="docs/chat.md#log-probabilities-and-predicted-output">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
ChatResponse response =
    chatClient
        .prompt()
        .user("Answer yes or no.")
        .options(OpenRouterChatOptions.builder().logprobs(true).topLogprobs(3))
        .call()
        .chatResponse();
List<OpenRouterLogprobs.Token> tokens =
    OpenRouterLogprobs.from(response.getResult().getOutput())
        .map(OpenRouterLogprobs::content)
        .orElse(List.of()); // empty when the provider sent none
```

</td>
</tr>
<tr>
<td><b>Predicted output</b> · Faster edits when most of the answer is known.</td>
<td align="right"><a href="docs/chat.md#log-probabilities-and-predicted-output">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String code = "int count = 0;\ncount++;\n";
String edited =
    chatClient
        .prompt()
        .user("Rename the variable count to total:\n" + code)
        .options(OpenRouterChatOptions.builder().prediction(code))
        .call()
        .content();
```

</td>
</tr>
</table>

</details>

### Routing

<details>
<summary>Model fallbacks, Provider routing, Service tier, Sessions and traces, Attribution headers</summary>

<table>
<tr>
<td><b>Model fallbacks</b> · Tried in order when one fails.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String answer =
    chatClient
        .prompt()
        .user("Summarize this ticket.")
        .options(OpenRouterChatOptions.builder().models(List.of("openai/gpt-oss-20b")))
        .call()
        .content();
```

</td>
</tr>
<tr>
<td><b>Provider routing</b> · Pick, order and sort providers.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Service tier</b> · Trade speed for price.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Sessions and traces</b> · Keep a conversation on one provider.</td>
<td align="right"><a href="docs/chat.md#sessions">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Attribution headers</b> · Your app on OpenRouter rankings.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```yaml
spring:
  ai:
    openrouter:
      app:
        http-referer: https://example.com
        title: Example app
```

</td>
</tr>
</table>

</details>

### Inputs

<details>
<summary>Image input, PDF input, Audio input, Video input</summary>

<table>
<tr>
<td><b>Image input</b> · Needs a model that reads images.</td>
<td align="right"><a href="docs/media-and-embeddings.md#attachments">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>PDF input</b> · A URL, bytes or a data URL.</td>
<td align="right"><a href="docs/media-and-embeddings.md#attachments">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Audio input</b> · Bytes or a data URL; remote URLs are rejected.</td>
<td align="right"><a href="docs/media-and-embeddings.md#attachments">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Video input</b> · URL support depends on the provider.</td>
<td align="right"><a href="docs/media-and-embeddings.md#attachments">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
</table>

</details>

### Outputs

<details>
<summary>Image generation, Image streaming, Image to image, Images from a chat model, Audio output</summary>

<table>
<tr>
<td><b>Image generation</b> · Base64 images from the Image API.</td>
<td align="right"><a href="docs/media-and-embeddings.md#image-generation">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
ImageResponse response = imageModel.call(new ImagePrompt("a red panda wearing sunglasses"));
String base64 = response.getResult().getOutput().getB64Json();
```

</td>
</tr>
<tr>
<td><b>Image streaming</b> · Previews first, then the final image.</td>
<td align="right"><a href="docs/media-and-embeddings.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Image to image</b> · Start from a reference image.</td>
<td align="right"><a href="docs/media-and-embeddings.md#image-generation">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Images from a chat model</b> · Images next to text in a reply.</td>
<td align="right"><a href="docs/media-and-embeddings.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Audio output</b> · Streaming only.</td>
<td align="right"><a href="docs/media-and-embeddings.md#chat-audio-output">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
Flux<ChatResponse> stream =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(
            OpenRouterChatOptions.builder()
                .model("openai/gpt-audio-mini")
                .modalities(OpenRouterModality.TEXT, OpenRouterModality.AUDIO)
                .audio(new OpenRouterAudioOptions("alloy", "pcm16")))
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
                              byte[] audio = OpenRouterMedia.bytes(media);
                            })));
```

</td>
</tr>
</table>

</details>

### Speech

<details>
<summary>Text-to-speech, Speech streaming, Transcription</summary>

<table>
<tr>
<td><b>Text-to-speech</b> · Raw PCM by default; set the format to mp3 for a playable file.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-to-speech">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
byte[] audio = speechModel.call("Your car is ready for pickup.");
```

</td>
</tr>
<tr>
<td><b>Speech streaming</b> · Chunks arrive in order; keep the audio only when the stream completes.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-to-speech">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
Flux<byte[]> chunks = speechModel.stream("Your car is ready for pickup.");
```

</td>
</tr>
<tr>
<td><b>Transcription</b> · The format comes from the file extension.</td>
<td align="right"><a href="docs/media-and-embeddings.md#transcription">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String text = transcriptionModel.transcribe(new FileSystemResource("inspection-note.wav"));
```

</td>
</tr>
</table>

</details>

### Embeddings

<details>
<summary>Text embedding, Batch with options, Text and image documents</summary>

<table>
<tr>
<td><b>Text embedding</b> · Works with Spring AI vector stores.</td>
<td align="right"><a href="docs/media-and-embeddings.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

</td>
</tr>
<tr>
<td><b>Batch with options</b> · Options merge with the defaults.</td>
<td align="right"><a href="docs/media-and-embeddings.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
EmbeddingResponse response =
    embeddingModel.call(
        new EmbeddingRequest(
            List.of("first text", "second text"),
            OpenRouterEmbeddingOptions.builder().dimensions(256).build()));
```

</td>
</tr>
<tr>
<td><b>Text and image documents</b> · One vector space; needs a model that accepts images.</td>
<td align="right"><a href="docs/media-and-embeddings.md#text-and-image-documents">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
Document caption = new Document("A boardwalk through a green meadow");
Document photo =
    new Document(
        new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/boardwalk.png")),
        Map.of());
EmbeddingResponse response =
    documentEmbeddingModel.call(new DocumentEmbeddingRequest(caption, photo));
```

</td>
</tr>
</table>

</details>

### Caching, usage and low-level

<details>
<summary>Prompt cache breakpoint, Prompt cache options, Usage and cost, Extra request fields, Low-level request</summary>

<table>
<tr>
<td><b>Prompt cache breakpoint</b> · Cache a stable prompt prefix.</td>
<td align="right"><a href="docs/chat.md#prompt-caching">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Prompt cache options</b> · Explicit caching and a 12-hour lifetime on OpenAI models.</td>
<td align="right"><a href="docs/chat.md#request-level-cache-controls">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
String answer =
    chatClient
        .prompt()
        .messages(message) // carries an OpenRouterCacheBreakpoint
        .options(
            OpenRouterChatOptions.builder()
                .promptCacheKey("example-app")
                .promptCacheOptions(
                    OpenRouterPromptCacheOptions.explicit(OpenRouterPromptCacheOptions.TTL_12_HOURS)))
        .call()
        .content();
```

</td>
</tr>
<tr>
<td><b>Usage and cost</b> · Credits charged, tokens used.</td>
<td align="right"><a href="docs/production.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
ChatResponse response = chatClient.prompt().user("Say hello.").call().chatResponse();
OpenRouterUsage usage = OpenRouterUsage.from(response);
Double cost = usage.getCost(); // null when not reported
```

</td>
</tr>
<tr>
<td><b>Extra request fields</b> · Fields without a typed option.</td>
<td align="right"><a href="docs/chat.md">Docs</a></td>
</tr>
<tr>
<td colspan="2">

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
</tr>
<tr>
<td><b>Low-level request</b> · Inject OpenRouterApi; no Spring AI types.</td>
<td align="right"><a href="docs/api.md#compatibility-of-low-level-requests">Docs</a></td>
</tr>
<tr>
<td colspan="2">

```java
ChatCompletionRequest request =
    ChatCompletionRequest.builder()
        .model("openai/gpt-oss-120b")
        .messages(List.of(new ChatMessage("user", "Say hello.", null, null, null)))
        .build();
ChatCompletionResponse response = openRouterApi.chatCompletion(request);
```

</td>
</tr>
</table>

</details>

## Documentation

| Page                                                 | Covers                                                                        |
| ---------------------------------------------------- | ----------------------------------------------------------------------------- |
| [Getting started](docs/getting-started.md)           | Tested versions; pitfalls next to another provider starter.                   |
| [Chat](docs/chat.md)                                 | Request modes, sessions, structured output, tools, reasoning, prompt caching. |
| [Media and embeddings](docs/media-and-embeddings.md) | Attachments, images, chat audio, speech, transcription, embeddings.           |
| [Production](docs/production.md)                     | Retries and billing, timeouts, HTTP customization, stream limits, cost.       |
| [Supported API](docs/api.md)                         | Types applications can depend on.                                             |
| [The Garage demo](docs/garage.md)                    | The sample application and what its evidence proves.                          |

## Modules

Applications add `openrouter-spring-ai-starter`, which brings in the core library `openrouter-spring-ai` and its auto-configuration. `openrouter-spring-ai-samples` is the [Garage demo](docs/garage.md).

## License

Released under the [MIT License](LICENSE).
