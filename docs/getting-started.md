# Getting started

The [README](../README.md#install) shows the install and a first call. This page holds what the code does not tell you: which versions are tested, and what goes wrong next to another provider starter.

## Release compatibility

| Source / artifact                                                                    | Java release target | Spring Boot | Spring AI |
| ------------------------------------------------------------------------------------ | ------------------- | ----------- | --------- |
| [0.1.0: `41786f9`](https://github.com/Subhransu-De/openrouter-spring-ai/tree/v0.1.0) | 17                  | 4.1.2       | 2.0.2     |
| Unreleased main (`0.2.0-SNAPSHOT`)                                                   | 17                  | 4.1.2       | 2.0.2     |

0.1.0 was tested with Boot 4.1.2, and its starter brings in Spring AI 2.0.2, as the [published 0.1.0 parent POM](https://repo.maven.apache.org/maven2/de/subhransu/openrouter-spring-ai-parent/0.1.0/openrouter-spring-ai-parent-0.1.0.pom) shows. Other Boot and Spring AI versions are not covered. Release candidates are listed in the [changelog](../CHANGELOG.md).

## Next to another provider starter

Set `spring.ai.model.chat`, `spring.ai.model.embedding` and `spring.ai.model.image` whenever more than one provider starter is present. Each one defaults to `openrouter`, so the README example sets the unused ones to `none`.

The audio models and the text-and-image `DocumentEmbeddingModel` are the exception: they are opt-in through `spring.ai.model.audio.speech=openrouter`, `spring.ai.model.audio.transcription=openrouter` and `spring.ai.model.embedding.multimodal=openrouter`. Spring AI's other providers of these model types also create their models when the selector is unset, so a default of `openrouter` would give an application that already uses one of them two beans of the same type.

Spring AI's provider auto-configurations, such as OpenAI's or Anthropic's, step aside only for a model of their own type. With a selector unset, every provider starter on the classpath creates a model of that type. Injecting `ChatClient.Builder` then fails, because it takes a single `ChatModel` and neither is `@Primary`. A consumer that injects a single `EmbeddingModel`, such as a vector store, fails the same way.

Some providers select on narrower keys. Google GenAI and Vertex AI text embeddings use `spring.ai.model.embedding.text`, and Vertex AI multimodal embeddings use `spring.ai.model.embedding.multimodal`. Set those as well when such a starter is present.

If you mark the other provider's model `@Primary` instead, OpenRouter's [tool failure policy](chat.md#tool-failure-policy) still applies to that provider's tool calls.

The shared `OpenRouterApi` bean is created while any selector is `openrouter`, or a chat, embedding or image selector is unset, even when you declare every model yourself, and it then requires `spring.ai.openrouter.api-key`. If your own models do not use it, set the selectors to `none` or to the provider you use.
