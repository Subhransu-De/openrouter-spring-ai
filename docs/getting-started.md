# Getting started

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

Use Java 17 or later. See [release compatibility](#release-compatibility) for Spring Boot and Spring AI versions.

## Configure

Spring Boot reads the key from configuration and builds `OpenRouterApi`, `ChatClient.Builder` and the chat, embedding and image models. The speech, transcription and document embedding models are opt-in.

### Configuration

The key comes from the environment.

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

### Chat client

Boot provides the builder; build the client once.

```java
@Bean
ChatClient chatClient(ChatClient.Builder builder) {
  return builder.build();
}
```

### Opt-in models

Speech, transcription and document embeddings are off until selected.

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

## Model beans

Declare a model bean for a second model, or without Boot. That bean ignores `spring.ai.openrouter.*`, and a chat bean owns the tool failure policy.

### Chat model bean

Replaces the auto-configured chat model.

```java
@Bean
OpenRouterChatModel chatModel(OpenRouterApi openRouterApi) {
  return OpenRouterChatModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterChatOptions.builder().model("openai/gpt-oss-120b").build())
      .build();
}
```

### Embedding model bean

Replaces the auto-configured embedding model.

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

### Document embedding model bean

Replaces the auto-configured document embedding model.

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

### Image model bean

Replaces the auto-configured image model.

```java
@Bean
OpenRouterImageModel imageModel(OpenRouterApi openRouterApi) {
  return OpenRouterImageModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterImageOptions.builder().model("bytedance-seed/seedream-4.5").build())
      .build();
}
```

### Speech model bean

Replaces the auto-configured speech model.

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

### Transcription model bean

Replaces the auto-configured transcription model.

```java
@Bean
OpenRouterAudioTranscriptionModel transcriptionModel(OpenRouterApi openRouterApi) {
  return OpenRouterAudioTranscriptionModel.builder()
      .openRouterApi(openRouterApi)
      .options(OpenRouterAudioTranscriptionOptions.builder().model("openai/whisper-1").build())
      .build();
}
```

### OpenRouterApi bean

Replaces the auto-configured client; start here without Boot.

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

## Spring AI model interfaces

This library implements each model interface of Spring AI 2.0.2 that OpenRouter has an endpoint for:

| Spring AI interface                 | Class                               | Selector                               | Created by default | Page                                                 |
| ----------------------------------- | ----------------------------------- | -------------------------------------- | ------------------ | ---------------------------------------------------- |
| `ChatModel`, with streaming         | `OpenRouterChatModel`               | `spring.ai.model.chat`                 | Yes                | [Chat](chat.md)                                      |
| `EmbeddingModel`                    | `OpenRouterEmbeddingModel`          | `spring.ai.model.embedding`            | Yes                | [Embeddings](embeddings.md)                          |
| `DocumentEmbeddingModel`            | `OpenRouterDocumentEmbeddingModel`  | `spring.ai.model.embedding.multimodal` | No                 | [Embeddings](embeddings.md#text-and-image-documents) |
| `ImageModel`                        | `OpenRouterImageModel`              | `spring.ai.model.image`                | Yes                | [Images](images.md)                                  |
| `TextToSpeechModel`, with streaming | `OpenRouterAudioSpeechModel`        | `spring.ai.model.audio.speech`         | No                 | [Speech and transcription](audio.md)                 |
| `TranscriptionModel`                | `OpenRouterAudioTranscriptionModel` | `spring.ai.model.audio.transcription`  | No                 | [Speech and transcription](audio.md#transcription)   |

A model that is not created by default needs its selector set to `openrouter`.

Two parts of Spring AI have no OpenRouter counterpart:

- `ModerationModel` is not implemented, because OpenRouter has no moderation endpoint.
- `TranscriptionModel.stream(...)` fails with `UnsupportedOperationException`, because OpenRouter has no streaming transcription.

To move an application from another Spring AI provider, see [Switching from another Spring AI provider](switching.md).

## Release compatibility

| Source / artifact                                                                    | Java release target | Spring Boot | Spring AI |
| ------------------------------------------------------------------------------------ | ------------------- | ----------- | --------- |
| [0.1.0: `41786f9`](https://github.com/Subhransu-De/openrouter-spring-ai/tree/v0.1.0) | 17                  | 4.1.2       | 2.0.2     |
| [0.2.0: `759beba`](https://github.com/Subhransu-De/openrouter-spring-ai/tree/v0.2.0) | 17                  | 4.1.2       | 2.0.2     |
| Unreleased main (`0.3.0-SNAPSHOT`)                                                   | 17                  | 4.1.2       | 2.0.2     |

Each release was tested with Boot 4.1.2, and its starter brings in Spring AI 2.0.2, as the [published 0.2.0 starter POM](https://repo.maven.apache.org/maven2/de/subhransu/openrouter-spring-ai-starter/0.2.0/openrouter-spring-ai-starter-0.2.0.pom) shows. Other Boot and Spring AI versions are not covered. Release candidates are listed in the [changelog](../CHANGELOG.md).

## Next to another provider starter

- Set `spring.ai.model.chat`, `spring.ai.model.embedding` and `spring.ai.model.image` when more than one provider starter is present. With a selector unset, every provider starter creates a model of that type, and injecting `ChatClient.Builder` or a single `EmbeddingModel` fails.
- Set a selector to `none` for a model type that you do not use.
- Some providers select on narrower keys, such as `spring.ai.model.embedding.text` and `spring.ai.model.embedding.multimodal`. Set those as well when such a starter is present.
- The shared `OpenRouterApi` bean is created while any selector is `openrouter`, or while a chat, embedding or image selector is unset. It then requires `spring.ai.openrouter.api-key`.
- If you mark another provider's model `@Primary`, OpenRouter's tool failure policy still applies to that provider's tool calls.
