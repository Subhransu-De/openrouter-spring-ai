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

The starter includes Spring AI's ChatClient auto-configuration. Set these application properties in `src/main/resources/application.properties`, supply `OPENROUTER_API_KEY` and a suitable `OPENROUTER_MODEL` in the environment, and use the injected builder:

```properties
spring.ai.model.chat=openrouter
spring.ai.model.embedding=none
spring.ai.model.image=none
spring.ai.openrouter.api-key=${OPENROUTER_API_KEY}
spring.ai.openrouter.chat.model=${OPENROUTER_MODEL}
```

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

Save the class as `src/main/java/example/Application.java`. Run `mvn spring-boot:run` or `gradle bootRun` with JDK 17 or later. The first call uses the configured model and makes a billable OpenRouter request. No clone or local library installation is needed.

For incremental text, use `client.prompt().user("Say hello.").stream().content()` and subscribe to the returned `Flux<String>` for the application's lifetime.

| Property                                                 | Default / meaning                                                        |
| -------------------------------------------------------- | ------------------------------------------------------------------------ |
| `spring.ai.model.chat`, `.embedding`, `.image`           | Each defaults to `openrouter`; the example disables unused modalities.   |
| `spring.ai.openrouter.api-key`                           | Required while an OpenRouter modality is enabled; no default credential. |
| `spring.ai.openrouter.base-url`                          | `https://openrouter.ai/api/v1`                                           |
| `spring.ai.openrouter.chat.model`                        | Unset; select a model explicitly.                                        |
| `spring.ai.openrouter.chat.request-mode`                 | `openai-chat-completions`; `openai-responses` is experimental.           |
| `spring.ai.openrouter.chat.include-usage`                | Unset; do not configure it globally when composing Responses requests.   |
| `spring.ai.openrouter.connection.timeout`                | `2m`                                                                     |
| `spring.ai.openrouter.connection.max-response-body-size` | `64MB` per response / SSE event, not per stream.                         |

## Release compatibility

| Source / artifact                                                                      | Java release target | Spring Boot | Spring AI |
| -------------------------------------------------------------------------------------- | ------------------- | ----------- | --------- |
| [RC3: `e336517`](https://github.com/Subhransu-De/openrouter-spring-ai/tree/v0.1.0-RC3) | 17                  | 4.1.1       | 2.0.1     |
| Unreleased main (`0.1.0-SNAPSHOT`)                                                     | 17                  | 4.1.1       | 2.0.1     |

Baselines come from the tagged/root POMs and [published RC3 parent POM](https://repo.maven.apache.org/maven2/de/subhransu/openrouter-spring-ai-parent/0.1.0-RC3/openrouter-spring-ai-parent-0.1.0-RC3.pom). CI targets JDK 17, 21, and 25; this does not establish compatibility with arbitrary Boot or Spring AI versions. Use the matching BOM baseline in the consuming application. Earlier release candidates are listed in the [changelog](../CHANGELOG.md).

## Model selection and replacement beans

The starter intentionally backs off for any application-declared `ChatModel`, `EmbeddingModel`, or `ImageModel` of the corresponding modality. This includes another provider's model and a custom OpenRouter model. Setting `spring.ai.model.chat=openrouter` enables chat auto-configuration but does not override a replacement `ChatModel` bean. The same rule applies to `spring.ai.model.embedding` and `spring.ai.model.image`.

Each selector defaults independently to `openrouter`. Use `none` to disable a modality, or another provider's identifier to select its auto-configuration. Disabling chat alone leaves OpenRouter embeddings and images enabled and still requires an API key. Set all three selectors to `none` to disable the shared OpenRouter API as well. Replacement model beans alone do not disable that API.

When several provider starters are installed, set each selector explicitly. This policy does not arbitrate between competing auto-configurations that match missing selectors; their ordering must not be used to choose a provider. For multiple models of one modality, declare the model beans yourself and inject them with `@Qualifier` using their bean names. The starter's default bean names are `openRouterChatModel`, `openRouterEmbeddingModel`, and `openRouterImageModel`. Selection properties do not remove application-declared beans.

A replacement `ChatModel` also disables OpenRouter's automatic tool-failure processor and manager guard. Applications that declare their own chat models own the shared tool failure policy described in [Tool failure policy](tool-calling.md#tool-failure-policy).
