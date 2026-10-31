# Images, documents and audio

Images, PDFs, audio and video as inputs, and generated images and audio as outputs.

## Image inputs

Attach image media to a `UserMessage`. It is sent as OpenRouter `image_url` content parts. Plain URLs pass through. Classpath and byte resources are inlined as base64 data URLs.

```java
UserMessage message = UserMessage.builder()
    .text("What is in this image?")
    .media(Media.builder()
        .mimeType(MimeTypeUtils.IMAGE_PNG)
        .data(URI.create("https://example.com/cat.png"))
        .build())
    .build();
```

## PDF, audio and video inputs

Synchronous and streaming calls accept these `UserMessage` attachments:

| Media | MIME types                                           | Source                                         |
| ----- | ---------------------------------------------------- | ---------------------------------------------- |
| PDF   | `application/pdf`                                    | HTTP(S) URL, bytes or base64 data URL          |
| Audio | `audio/wav`, `audio/mpeg`                            | Bytes or base64 data URL; remote URLs rejected |
| Video | `video/mp4`, `video/mpeg`, `video/mov`, `video/webm` | HTTP(S) URL, bytes or base64 data URL          |

```java
UserMessage message = UserMessage.builder()
    .text("Summarize the attached document")
    .media(Media.builder()
        .mimeType(MimeTypeUtils.parseMimeType("application/pdf"))
        .name("report.pdf")
        .data(URI.create("https://example.com/report.pdf"))
        .build())
    .build();
```

- PDF filenames come from `Media.getName()`. Set `.name(...)` to keep the original filename. Audio and video names are not sent.
- Other file types, audio formats, MIME parameters and mismatched data-URL MIME types are rejected. Uploaded file IDs and the Anthropic Messages format are not supported.
- Inline PDF, audio and video attachments must decode to 1 byte through 20 MiB. A violation, empty data or invalid base64 raises `IllegalArgumentException` before the request is sent. Images are not size-checked.
- Provider limits (request size, file size, duration, attachment count) still apply, including to remote URLs.
- The library never fetches attachment URLs. Spring AI reads resources when it builds `Media`, before the limit applies, so bound resource reads yourself.
- Unchanged messages replay the same attachments in order, after the message text, including across tool continuations. A remote URL is replayed as a URL and may serve different content later.

Pick a model and provider that support the modality and format. The library keeps no model allowlist, and OpenRouter returns provider errors. Video URL support depends on the provider. See the [multimodal guide](https://openrouter.ai/docs/guides/overview/multimodal/overview) and the [endpoint schemas](https://openrouter.ai/openapi.json). For generated audio, see [Chat audio output](#chat-audio-output).

## Image generation

Use `OpenRouterImageModel` for a dedicated image model. It implements Spring AI's `ImageModel` over OpenRouter's Image API (`POST /images`). Use a chat model when images belong in a conversation next to text; see [Chat models with image output](#chat-models-with-image-output).

```yaml
spring:
  ai:
    model:
      image: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      image:
        model: bytedance-seed/seedream-4.5
        aspect-ratio: "16:9"
        quality: high
        output-format: webp
```

```java
ImageResponse response = imageModel.call(new ImagePrompt("a red panda wearing sunglasses"));
String base64 = response.getResult().getOutput().getB64Json();
```

### Image options

- `OpenRouterImageOptions` holds `resolution`, `aspectRatio`, `quality`, `outputFormat`, `background`, `outputCompression`, `seed`, `inputReferences` (image-to-image) and `providerOptions` (passthrough). `outputFormat` selects the image encoding, such as `webp`, not URL or base64 delivery.
- Spring AI `ImageOptions` map `model` and `n` directly. A paired `width` and `height` becomes a pixel `size`. A lone dimension is rejected after defaults merge.
- `responseFormat` must be omitted or `b64_json`, because the Image API always returns base64. A non-null `style` is rejected because OpenRouter has no equivalent. Both throw `IllegalArgumentException` before HTTP, in `call` and `stream`.
- `OpenRouterImageOptions.fromOptions(...)` converts generic defaults. Generic per-request options override the mapped fields and keep unset native defaults such as `quality` and `providerOptions`.

### Streaming

`stream(ImagePrompt)` exposes OpenRouter's SSE image streaming. Partial previews arrive first, then completed images with usage and cost. This snippet blocks until the stream ends; in a web application, return the `Flux` instead:

```java
imageModel.stream(prompt).doOnNext(response -> response.getResults().forEach(result -> {
    var meta = (OpenRouterImageGenerationMetadata) result.getMetadata();
    boolean preview = meta.partialImageIndex() != null; // null for completed images
})).blockLast();
```

- The stream ends at `[DONE]`, not at the first completed image.
- It fails with `OpenRouterTruncatedResponseException` if no completed image arrives, or if the connection closes without `[DONE]`, even after a completed image.
- Unknown event types are ignored. A missing, null or blank type fails with `OpenRouterProtocolException`.
- Cancellation stops the stream without a protocol failure.
- If a provider answers a streamed image request with one JSON body instead of SSE, the library turns that body into completed events (the JSON fallback).
- `n` is an upper bound. Providers may return fewer images. Multiple images and native streaming depend on the endpoint; see the [OpenRouter image API capabilities](https://openrouter.ai/docs/guides/overview/multimodal/image-generation).

### Response checks

- A JSON response must contain a `data` array. An empty array is a valid empty result for blocking calls. The JSON fallback needs at least one image and raises `OpenRouterTruncatedResponseException` for an empty array.
- Each entry and each completed SSE event needs a nonblank `b64_json` or `url`. Otherwise `OpenRouterProtocolException` is raised.
- An HTTP 200 error envelope raises a structured `OpenRouterApiException`.

### Chat models with image output

Set `OpenRouterChatOptions.builder().modalities(List.of("image", "text"))`, optionally with `imageConfig`. Generated images arrive as `AssistantMessage` media, in sync calls and streams, in both request modes.

Attach generated media to a new `UserMessage` for image edits or follow-up questions. Responses base64 results are exposed as complete data URLs. A supplied `output_format` sets the MIME type (PNG by default). An existing data URL keeps its own MIME type.

### Assistant media history

Chat Completions keeps images when you replay an `AssistantMessage` in conversation history. Responses mode rejects assistant media history, in calls and streams. Attach the media to a `UserMessage` instead. That is not enough if the message still carries Responses output snapshots for [reasoning replay](reasoning-and-caching.md#reasoning-conversation-state) that contain the image. Those are rejected too; start a new conversation without that reasoning state. Image understanding needs a model that accepts image input.

## Chat audio output

Use an [audio-output-capable OpenRouter model](https://openrouter.ai/docs/guides/overview/multimodal/audio) with streaming Chat Completions. Voices and supported encodings depend on the model:

```java
var options = OpenRouterChatOptions.builder()
    .model("your-audio-output-model")
    .modalities(List.of("text", "audio"))
    .audio(new OpenRouterAudioOptions("alloy", "pcm16"))
    .build();
chatModel.stream(new Prompt("Say hello.", options))
    .doOnNext(response -> response.getResults().forEach(generation -> {
        AssistantMessage message = generation.getOutput();
        message.getMedia().forEach(media -> {
            byte[] completeAudio = media.getDataAsByteArray(); // decode per the requested format
        });
        if (message.getMetadata().get("openrouter.audio") instanceof Map<?, ?> audio) {
            Object transcript = audio.get("transcript");
        }
    }))
    .blockLast(); // waits for successful completion; in a web application, return the Flux
```

Boot equivalents are `spring.ai.openrouter.chat.modalities=text,audio`, `spring.ai.openrouter.chat.audio.voice=alloy` and `spring.ai.openrouter.chat.audio.format=pcm16`. Formats are `wav`, `mp3`, `flac`, `opus` and `pcm16`. PCM bytes are raw audio, not a WAV file. Sample rate and other playback parameters must match the provider. The library does no transcoding.

Only `stream()` supports audio output. `call()` fails with `IllegalArgumentException`. If the app also uses `call()`, set the audio modalities and options on each streaming request instead of in Boot properties.

The audio arrives as one complete Spring AI `Media` per choice, never as partial media:

![Streamed audio.data chunks are each decoded from base64 and appended per choice. Path A: a stop finish reason delivers one complete Media in that same response. Path B: the last delta has only expires_at and no finish reason, as with OpenAI audio models; the Media arrives in one extra response after [DONE] with empty text, no usage and no finish reason. A max_tokens cut-off looks the same, so leave enough room. The transcript goes once into the final assistant metadata as openrouter.audio, not into the text. call() and Responses mode reject output audio.](images/audio-output.svg)

The transcript is not part of the text, so `ChatClient` `.content()` does not return it. Read it from the assistant message metadata key `openrouter.audio`, a map with `transcript` and `format` and, when supplied, `id` and `expires_at`. Normal text stays incremental. Treat the request as successful only when the stream completes, because a later transport or provider error still fails it.

A `max_tokens` cut-off fails the stream when the provider reports a non-`stop` finish reason, but goes unnoticed on path B, which sends none. Leave enough `max_tokens`.

These cases fail the stream:

- `OpenRouterTruncatedResponseException`: the stream ends before `[DONE]`, or a choice ends without a `stop` finish reason or a final `expires_at` marker.
- `OpenRouterProtocolException`: a fragment has invalid base64, identifiers or formats conflict within a choice, or a choice mixes audio with tool calls. Each data value must encode a complete byte fragment, and `message.audio` snapshots are unsupported.
- `NonTransientAiException`: the audio buffer limits (16 MiB, 128 audio choices per stream) are exceeded; see [Size, time and state limits](http-and-limits.md#size-time-and-state-limits).

Assistant audio replay is rejected in both request modes, including identifier-only metadata. To continue with only the transcript, create a new text-only assistant message. This discards audio continuation state. Audio IDs and expiry metadata are kept for you but not replayed to the provider.
