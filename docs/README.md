# OpenRouter Spring AI documentation

Each page shows code examples for one part of the library. For every option, default and exception, read the Javadoc and the `spring.ai.openrouter.*` property metadata. For Spring AI itself, read the [Spring AI reference](https://docs.spring.io/spring-ai/reference/).

![A call goes from your app through Spring AI's ChatClient into this library's model and API client, then to OpenRouter, which routes it to an upstream provider.](images/architecture.svg)

| Page                                                      | Spring AI interface                        | Examples                                                                                                    |
| --------------------------------------------------------- | ------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| [Getting started](getting-started.md)                     |                                            | Install, configuration, model beans, tested versions                                                        |
| [Chat](chat.md)                                           | `ChatModel`                                | Text, streaming, structured output, reasoning, tools, routing, file inputs, image and audio output, caching |
| [Images](images.md)                                       | `ImageModel`                               | Generation, streaming, image to image                                                                       |
| [Embeddings](embeddings.md)                               | `EmbeddingModel`, `DocumentEmbeddingModel` | Text, batches, text and image documents                                                                     |
| [Speech and transcription](audio.md)                      | `TextToSpeechModel`, `TranscriptionModel`  | Speech, streamed speech, transcription                                                                      |
| [Production](production.md)                               |                                            | Retries, timeouts, limits, cost                                                                             |
| [Supported API](api.md)                                   |                                            | Types that applications can depend on                                                                       |
| [Switching from another Spring AI provider](switching.md) |                                            | What to change and what differs                                                                             |

Release history is in [CHANGELOG.md](../CHANGELOG.md).
