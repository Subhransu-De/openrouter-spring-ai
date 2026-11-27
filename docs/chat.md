# Chat

`OpenRouterChatModel` implements Spring AI's `ChatModel`, with streaming. Boot creates it when `spring.ai.model.chat` is `openrouter` or unset, and `ChatClient` uses it.

The examples use an injected `ChatClient chatClient`. [Getting started](getting-started.md#chat-client) shows how to build it.

## Text

### Text generation

One blocking call.

```java
String answer = chatClient.prompt().user("Say hello.").call().content();
```

### Streaming

Text arrives as server-sent events.

```java
Flux<String> tokens = chatClient.prompt().user("Tell a short story.").stream().content();
```

### Per-request options

Unset fields keep the defaults.

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().temperature(0.0).maxCompletionTokens(200))
        .call()
        .content();
```

### Structured output

Typed records from a JSON schema.

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

### Reasoning

Read the model's thinking separately.

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

## Tools

### Tool calling

Spring AI runs the tool loop.

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

### Strict tools

Checks each tool schema before the request.

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

## Request mode and sampling

### Responses mode (experimental)

Sends to the /responses endpoint.

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().requestMode(OpenRouterRequestMode.RESPONSES))
        .call()
        .content();
```

### Log probabilities

Each token's log probability and its alternatives.

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

### Predicted output

Faster edits when most of the answer is known.

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

## Routing

### Model fallbacks

Tried in order when one fails.

```java
String answer =
    chatClient
        .prompt()
        .user("Summarize this ticket.")
        .options(OpenRouterChatOptions.builder().models(List.of("openai/gpt-oss-20b")))
        .call()
        .content();
```

### Provider routing

Pick, order and sort providers.

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

### Service tier

Trade speed for price.

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().serviceTier(OpenRouterServiceTier.FLEX))
        .call()
        .content();
```

### Sessions and traces

Keep a conversation on one provider.

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

### Attribution headers

Your app on OpenRouter rankings.

```yaml
spring:
  ai:
    openrouter:
      app:
        http-referer: https://example.com
        title: Example app
```

### Custom headers

HTTP headers for chat requests, in both request modes, in calls and streams.

```yaml
spring:
  ai:
    openrouter:
      chat:
        custom-headers:
          X-Tenant: acme
```

```java
String answer = chatClient.prompt().user(question)
    .options(OpenRouterChatOptions.builder().customHeaders(Map.of("X-Request-Tag", runId)))
    .call()
    .content();
```

Request headers are merged into the default headers by name, ignoring case, and a request value replaces a default value. The headers that the client sets itself are rejected: `Authorization`, `Content-Type`, `Accept`, `HTTP-Referer`, `X-OpenRouter-Title`, its alias `X-Title`, and `X-OpenRouter-Categories`. Set the key and the attribution on the client instead. A name must be an HTTP token, and a value must be visible ASCII with spaces or tabs only between characters, so a value copied with a trailing newline fails when the options are built. When two property sources spell a name in different cases, the higher-priority source wins. HTTP error details redact the request's header values of eight or more characters, in case a gateway echoes them. The embedding, image and audio models take no custom headers; for headers on every request, use an `OpenRouterRestClientCustomizer` and an `OpenRouterWebClientCustomizer` (see [HTTP customization](production.md#http-customization)).

## Inputs

### Image input

Needs a model that reads images.

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

### PDF input

A URL, bytes or a data URL.

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

### Audio input

Bytes or a data URL; remote URLs are rejected.

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

### Video input

URL support depends on the provider.

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

## Image and audio output

### Images from a chat model

Images next to text in a reply.

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

### Audio output

Streaming only.

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

## Caching, usage and low-level

### Prompt cache breakpoint

Cache a stable prompt prefix.

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

### Prompt cache options

Explicit caching and a 12-hour lifetime on OpenAI models.

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

### Usage and cost

Credits charged, tokens used.

```java
ChatResponse response = chatClient.prompt().user("Say hello.").call().chatResponse();
OpenRouterUsage usage = OpenRouterUsage.from(response);
Double cost = usage.getCost(); // null when not reported
```

### Extra request fields

Fields without a typed option.

```java
String answer =
    chatClient
        .prompt()
        .user("Say hello.")
        .options(OpenRouterChatOptions.builder().extraBody(Map.of("transforms", List.of())))
        .call()
        .content();
```

### Low-level request

Inject OpenRouterApi; no Spring AI types.

```java
ChatCompletionRequest request =
    ChatCompletionRequest.builder()
        .model("openai/gpt-oss-120b")
        .messages(List.of(new ChatMessage("user", "Say hello.", null, null, null)))
        .build();
ChatCompletionResponse response = openRouterApi.chatCompletion(request);
```

## Good to know

- **Request modes:** use Chat Completions, the default. Responses is experimental, and an option that it does not support fails before the request.
- **Strict tools:** the library checks a strict schema and never rewrites it. Keep every parameter required, and write an optional value as a nullable type.
- **Tool failures:** a custom `ToolCallingManager` bean must be built with `OpenRouterToolCallingManagers.withFailurePolicy(...)`, or startup fails.
- **Prompt cache options:** only OpenAI models that support explicit prompt caching accept `promptCacheOptions`. Other OpenAI models reject the request with HTTP 400.
- **Audio output:** only streaming Chat Completions returns audio. PCM bytes are raw audio, not a WAV file.

Errors, retries, timeouts and cost are on the [Production](production.md) page.
