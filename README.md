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

A native [Spring AI](https://spring.io/projects/spring-ai) integration for
[OpenRouter](https://openrouter.ai), with typed model fallbacks, provider routing,
reasoning replay, cost accounting, and attribution headers under `spring.ai.openrouter.*`.
Spring AI's OpenAI integration can also send OpenRouter-specific fields through
[`extraBody`](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatOptions.java).
This library adds typed routing and OpenRouter-specific response and reasoning handling;
it does not promise universal provider parity. It owns its `RestClient`/`WebClient`
transport and wire mapping, unlike the vendor SDK cores used by Spring AI 2.0.1's
OpenAI and Anthropic integrations.

> **Community project:** This library is independently maintained and is not affiliated with,
> endorsed by, or an official project of OpenRouter or Spring AI.
>
> Install only one OpenRouter Spring AI starter in an application. Combining this starter with
> another OpenRouter starter can make provider selection and bean creation order-dependent.

Chat Completions is the production default. The optional Responses request mode is experimental;
applications should opt into it explicitly.

[Getting started](docs/getting-started.md) · [Documentation](docs/README.md) · [Changelog](CHANGELOG.md) · [Contributing](CONTRIBUTING.md)

## What it does

- Chat through OpenRouter's Chat Completions endpoint, with synchronous calls and SSE streaming. The Responses endpoint is an experimental opt-in.
- Tool calling through Spring AI's `ToolCallingAdvisor`, including streamed argument fragments and [strict function tools](docs/tool-calling.md#strict-function-tools).
- Reasoning options, with reasoning text and opaque reasoning state replayed across tool rounds.
- Model fallback lists, provider routing preferences, service tiers and [optional OpenRouter fields](docs/extensions.md).
- Usage accounting with cost, cached and reasoning tokens, and Micrometer observations.
- Embeddings behind Spring AI's `EmbeddingModel`.
- Image, PDF, audio and video inputs, image generation, and streamed chat audio output. See [Images, documents and audio](docs/multimodal.md).
- Spring Boot auto-configuration under `spring.ai.openrouter.*`.

Not supported yet: the models catalogue endpoint, OpenRouter server-side tools such as the web search plugin, and standalone text-to-speech or transcription.

## Install

Add the starter from Maven Central:

```xml
<dependency>
    <groupId>de.subhransu</groupId>
    <artifactId>openrouter-spring-ai-starter</artifactId>
    <version>0.1.0-RC3</version>
</dependency>
```

```kotlin
implementation("de.subhransu:openrouter-spring-ai-starter:0.1.0-RC3")
```

Use Java 17 or later with the Spring Boot 4.1.1 and Spring AI 2.0.1 baseline. [Getting started](docs/getting-started.md) has complete Maven and Gradle builds.

## Make a first call

Set the API key and model, and turn off the modalities you do not use:

```properties
spring.ai.model.chat=openrouter
spring.ai.model.embedding=none
spring.ai.model.image=none
spring.ai.openrouter.api-key=${OPENROUTER_API_KEY}
spring.ai.openrouter.chat.model=${OPENROUTER_MODEL}
```

Then use the auto-configured `ChatClient.Builder`:

```java
@Bean
CommandLineRunner hello(ChatClient.Builder builder) {
    ChatClient client = builder.build();
    return args -> System.out.println(client.prompt().user("Say hello.").call().content());
}
```

Each call is a billable OpenRouter request.

## Documentation

| Page                                         | Covers                                                                                                                          |
| -------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| [Getting started](docs/getting-started.md)   | Install, first call, properties, compatibility, model selection.                                                                |
| [Chat options](docs/chat-options.md)         | How runtime options combine with defaults.                                                                                      |
| [Request modes](docs/request-modes.md)       | Chat Completions and experimental Responses.                                                                                    |
| [Supported API and nullability](docs/api.md) | Types applications can depend on, null contracts.                                                                               |
| [Tool calling](docs/tool-calling.md)         | Tools, strict schemas, failure policy.                                                                                          |
| [More pages](docs/README.md)                 | Extensions, reasoning and caching, errors and retries, HTTP and limits, multimodal, embeddings, observability, the Garage demo. |

## Modules

| Module                               | What it is                                                                    |
| ------------------------------------ | ----------------------------------------------------------------------------- |
| `openrouter-spring-ai`               | Core: API client, wire DTOs, mappers, chat/embedding/image models and options |
| `openrouter-spring-ai-autoconfigure` | Spring Boot auto-configuration and `spring.ai.openrouter.*` binding           |
| `openrouter-spring-ai-starter`       | The starter — the one dependency applications add                             |
| `openrouter-spring-ai-samples`       | The [Garage demo](docs/garage.md) application                                 |

Module names, property prefixes, and layering deliberately mirror Spring AI's official providers.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for builds, tests and quality checks.

## License

Released under the [MIT License](LICENSE).
