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
- Tool calling through Spring AI's `ToolCallingAdvisor`, including streamed argument fragments and [strict function tools](docs/chat.md#strict-tools).
- [Reasoning options](docs/chat.md#reasoning), with reasoning text and opaque reasoning state replayed across tool rounds.
- Model fallback lists, provider routing, service tiers and attribution headers, plus [optional OpenRouter fields](docs/chat.md#extra-request-fields).
- Usage accounting with cost, cached and reasoning tokens, and Micrometer observations.
- Embeddings behind Spring AI's `EmbeddingModel`, and text and image documents behind `DocumentEmbeddingModel`. See [Embeddings](docs/embeddings.md).
- Image, PDF, audio and video inputs and streamed chat audio output. See [Chat](docs/chat.md#inputs).
- Image generation behind Spring AI's `ImageModel`. See [Images](docs/images.md).
- Text-to-speech behind Spring AI's `TextToSpeechModel`, with streamed audio, and transcription behind `TranscriptionModel`. See [Text-to-speech](docs/audio.md#text-to-speech) and [Transcription](docs/audio.md#transcription).
- Spring Boot auto-configuration under `spring.ai.openrouter.*`.

Not supported yet: the models catalogue endpoint, OpenRouter server-side tools such as the web search plugin, voice cloning and multi-speaker speech, and multipart transcription uploads.

## Install

Add the starter from Maven Central:

```xml
<dependency>
    <groupId>de.subhransu</groupId>
    <artifactId>openrouter-spring-ai-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

```kotlin
implementation("de.subhransu:openrouter-spring-ai-starter:0.2.0")
```

Use Java 17 or later. See [release compatibility](docs/getting-started.md#release-compatibility) for Spring Boot and Spring AI versions.

## Examples

One example for each model. Each page under Documentation has the rest. The examples need release 0.2.0; 0.1.0 lacks some of these types and names, as the [changelog](CHANGELOG.md) shows. Each call is a billable OpenRouter request.

<table>
<tr>
<td><b>Configuration</b> · The key comes from the environment.</td>
<td align="right"><a href="docs/getting-started.md#configure">More</a></td>
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
<td align="right"><a href="docs/getting-started.md#chat-client">More</a></td>
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
<td><b>Text generation</b> · One blocking call.</td>
<td align="right"><a href="docs/chat.md#text-generation">More</a></td>
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
<td align="right"><a href="docs/chat.md#streaming">More</a></td>
</tr>
<tr>
<td colspan="2">

```java
Flux<String> tokens = chatClient.prompt().user("Tell a short story.").stream().content();
```

</td>
</tr>
<tr>
<td><b>Tool calling</b> · Spring AI runs the tool loop.</td>
<td align="right"><a href="docs/chat.md#tool-calling">More</a></td>
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
<td><b>Structured output</b> · Typed records from a JSON schema.</td>
<td align="right"><a href="docs/chat.md#structured-output">More</a></td>
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
<td><b>Image input</b> · Needs a model that reads images.</td>
<td align="right"><a href="docs/chat.md#image-input">More</a></td>
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
<td><b>Image generation</b> · Base64 images from the Image API.</td>
<td align="right"><a href="docs/images.md#image-generation">More</a></td>
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
<td><b>Text embedding</b> · Works with Spring AI vector stores.</td>
<td align="right"><a href="docs/embeddings.md#text-embedding">More</a></td>
</tr>
<tr>
<td colspan="2">

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

</td>
</tr>
<tr>
<td><b>Text and image documents</b> · One vector space; needs a model that accepts images.</td>
<td align="right"><a href="docs/embeddings.md#text-and-image-documents">More</a></td>
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
<tr>
<td><b>Text-to-speech</b> · Raw PCM by default; set the format to mp3 for a playable file.</td>
<td align="right"><a href="docs/audio.md#text-to-speech">More</a></td>
</tr>
<tr>
<td colspan="2">

```java
byte[] audio = speechModel.call("Your car is ready for pickup.");
```

</td>
</tr>
<tr>
<td><b>Transcription</b> · The format comes from the file extension.</td>
<td align="right"><a href="docs/audio.md#transcription">More</a></td>
</tr>
<tr>
<td colspan="2">

```java
String text = transcriptionModel.transcribe(new FileSystemResource("inspection-note.wav"));
```

</td>
</tr>
</table>

## Documentation

| Page                                                           | Spring AI interface                        | Examples                                                                                                    |
| -------------------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| [Getting started](docs/getting-started.md)                     |                                            | Install, configuration, model beans, tested versions                                                        |
| [Chat](docs/chat.md)                                           | `ChatModel`                                | Text, streaming, structured output, reasoning, tools, routing, file inputs, image and audio output, caching |
| [Images](docs/images.md)                                       | `ImageModel`                               | Generation, streaming, image to image                                                                       |
| [Embeddings](docs/embeddings.md)                               | `EmbeddingModel`, `DocumentEmbeddingModel` | Text, batches, text and image documents                                                                     |
| [Speech and transcription](docs/audio.md)                      | `TextToSpeechModel`, `TranscriptionModel`  | Speech, streamed speech, transcription                                                                      |
| [Production](docs/production.md)                               |                                            | Retries, timeouts, limits, cost                                                                             |
| [Supported API](docs/api.md)                                   |                                            | Types that applications can depend on                                                                       |
| [Switching from another Spring AI provider](docs/switching.md) |                                            | What to change and what differs                                                                             |

## Modules

Applications add `openrouter-spring-ai-starter`, which brings in the core library `openrouter-spring-ai` and its auto-configuration.

## License

Released under the [MIT License](LICENSE).
