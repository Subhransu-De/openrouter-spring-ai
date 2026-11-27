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

Chunks follow Spring AI's streaming contract for message parts: every chunk carries the response id, and reasoning, text and tool-call argument deltas are partial parts with a part index. Spring AI's `MessageAggregator` rebuilds the message that `call()` returns. A partial `ToolCallPart` only shows a call while the model writes it; it is not a tool call, and the complete call follows once, at the same index. Streamed media stays on its chunk, as in Spring AI's own models.

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

Reasoning is a `ReasoningPart` of the assistant message, in order with the text and tool calls.

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
String thinking =
    response.getResult().getOutput().getReasoning().stream()
        .map(ReasoningPart::text)
        .filter(Objects::nonNull)
        .collect(Collectors.joining());
```

The part's payload holds what the provider needs on the next turn: the `reasoning_details` in Chat Completions mode, signatures and encrypted entries included, or the reasoning output item in Responses mode. Send the assistant message back as it is, for example through Spring AI session memory, and the library replays the reasoning unchanged, which Anthropic and Gemini models need for tool loops.

- Reasoning that another provider signed is not replayed, as in Spring AI's OpenAI models. In Chat Completions mode, unsigned reasoning text from anywhere is sent as `reasoning`. Responses mode only replays reasoning that it produced.
- A message without reasoning parts, such as one stored by 0.3.0, replays the `openrouter.reasoning*` and `openrouter.responses.*` metadata keys. Those keys are still written in 0.4.0, but are deprecated.
- A refusal comes with an ordinary `stop` finish reason, so the library reports its finish reason category as `FILTERED`.

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

Each output item becomes one part, in order, and carries the item, so an assistant message replays its reasoning, messages and calls verbatim. Server-side items such as a web search call are kept as an `UnknownPart` and replayed too. A text or tool call part that you replace is rebuilt instead.

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
