# Getting started

This page creates a new Spring Boot application that calls OpenRouter through Spring AI, lists the main properties, and explains how the starter picks models.

## Install and make a first call

Use the published `de.subhransu:openrouter-spring-ai-starter:0.1.0-RC3` from Maven Central. For a new application, create this `pom.xml`:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.1</version>
        <relativePath/>
    </parent>
    <groupId>example</groupId>
    <artifactId>hello-openrouter</artifactId>
    <version>1.0.0</version>
    <properties><java.version>17</java.version></properties>
    <dependencies>
        <dependency>
            <groupId>de.subhransu</groupId>
            <artifactId>openrouter-spring-ai-starter</artifactId>
            <version>0.1.0-RC3</version>
        </dependency>
    </dependencies>
    <build><plugins><plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
    </plugin></plugins></build>
</project>
```

Or create `build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}
repositories { mavenCentral() }
java { sourceCompatibility = JavaVersion.VERSION_17 }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("de.subhransu:openrouter-spring-ai-starter:0.1.0-RC3")
}
```

Set these properties in `src/main/resources/application.properties`. Put your key in the environment variable `OPENROUTER_API_KEY`, and an [OpenRouter model id](https://openrouter.ai/models) in `OPENROUTER_MODEL`, in `vendor/model` form, such as `openai/gpt-oss-120b`:

```properties
spring.ai.model.chat=openrouter
spring.ai.model.embedding=none
spring.ai.model.image=none
spring.ai.openrouter.api-key=${OPENROUTER_API_KEY}
spring.ai.openrouter.chat.model=${OPENROUTER_MODEL}
```

Then inject the `ChatClient.Builder`:

```java
package example;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner hello(ChatClient.Builder builder) {
        ChatClient client = builder.build();
        return args -> System.out.println(client.prompt().user("Say hello.").call().content());
    }
}
```

Save the class as `src/main/java/example/Application.java`. Run `mvn spring-boot:run` or `gradle bootRun` with JDK 17 or later. The call is a billable OpenRouter request.

`spring.ai.model.chat=openrouter` is the default. Setting it keeps OpenRouter selected if you add another provider starter. Without the two `none` lines, startup still succeeds, but the starter creates embedding and image models that have no default model.

For incremental text, stream the content. This prints each chunk and waits for the end:

```java
client.prompt().user("Say hello.").stream().content().doOnNext(System.out::print).blockLast();
```

In a web application, return the `Flux<String>` from the controller instead of blocking.

## Main properties

| Property                                                 | Default / meaning                                                                                                       |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `spring.ai.model.chat`, `.embedding`, `.image`           | Each defaults to `openrouter`; the example disables unused modalities.                                                  |
| `spring.ai.openrouter.api-key`                           | Required while an OpenRouter modality is enabled; no default credential.                                                |
| `spring.ai.openrouter.base-url`                          | `https://openrouter.ai/api/v1`                                                                                          |
| `spring.ai.openrouter.chat.model`                        | Unset; select a model explicitly.                                                                                       |
| `spring.ai.openrouter.chat.request-mode`                 | `openai-chat-completions`; `openai-responses` is experimental.                                                          |
| `spring.ai.openrouter.chat.include-usage`                | Unset. `true` asks OpenRouter for usage and cost in Chat Completions requests, calls and streams. Responses rejects it. |
| `spring.ai.openrouter.connection.timeout`                | `2m`                                                                                                                    |
| `spring.ai.openrouter.connection.max-response-body-size` | `64MB` per response / SSE event, not per stream.                                                                        |

Fallback models, provider routing, service tier and attribution headers are in [Routing, fallbacks and attribution](chat-options.md#routing-fallbacks-and-attribution).

## Release compatibility

| Source / artifact                                                                      | Java release target | Spring Boot | Spring AI |
| -------------------------------------------------------------------------------------- | ------------------- | ----------- | --------- |
| [RC3: `e336517`](https://github.com/Subhransu-De/openrouter-spring-ai/tree/v0.1.0-RC3) | 17                  | 4.1.1       | 2.0.1     |
| Unreleased main (`0.1.0-SNAPSHOT`)                                                     | 17                  | 4.1.2       | 2.0.2     |

The snippets on this page use Boot 4.1.1, the version RC3 was tested with, and RC3's starter brings in Spring AI 2.0.1. `main` targets Boot 4.1.2 and Spring AI 2.0.2. The versions come from the tagged and root POMs and the [published RC3 parent POM](https://repo.maven.apache.org/maven2/de/subhransu/openrouter-spring-ai-parent/0.1.0-RC3/openrouter-spring-ai-parent-0.1.0-RC3.pom). CI targets JDK 17, 21 and 25. Other Boot and Spring AI versions are not covered. Earlier release candidates are listed in the [changelog](../CHANGELOG.md).

## Model selection and replacement beans

![Each spring.ai.model selector picks none, another provider, or openrouter. With openrouter, a model bean that the application declares wins; otherwise the starter creates openRouterChatModel, openRouterEmbeddingModel or openRouterImageModel. The shared OpenRouterApi is created while any selector is openrouter, unless the application declares its own.](images/model-selection.svg)

To use several models of one modality, declare the model beans yourself and inject them with `@Qualifier`. The starter's default bean names are `openRouterChatModel`, `openRouterEmbeddingModel`, and `openRouterImageModel`. If you declare your own `ChatModel`, you also own the [tool failure policy](tool-calling.md#tool-failure-policy).
