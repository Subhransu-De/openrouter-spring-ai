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

| Media | MIME types                                           | Chat Completions                                     | Responses                                   | Source                                         |
| ----- | ---------------------------------------------------- | ---------------------------------------------------- | ------------------------------------------- | ---------------------------------------------- |
| PDF   | `application/pdf`                                    | `file` with `file_data`                              | `input_file` with `file_data` or `file_url` | HTTP(S) URL, bytes or base64 data URL          |
| Audio | `audio/wav`, `audio/mpeg`                            | `input_audio` with raw base64 and `wav`/`mp3` format | Same                                        | Bytes or base64 data URL; remote URLs rejected |
| Video | `video/mp4`, `video/mpeg`, `video/mov`, `video/webm` | `video_url` object                                   | `input_video` with string `video_url`       | HTTP(S) URL, bytes or base64 data URL          |

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
- Inline PDF, audio and video attachments must decode to 1 byte through 20 MiB. The library checks this before it encodes bytes or decodes a base64 data URL, and rejects empty and invalid base64. It does not size-check images.
- Provider limits (request size, file size, duration, attachment count) still apply, including to remote URLs.
- The library never fetches attachment URLs. Spring AI reads resources when it builds `Media`, before this library can enforce its limit. Bound resource reads yourself or pass bounded bytes.
- Reusing unchanged messages replays the same attachments in order, after the message text, including across tool continuations. A remote URL is replayed as a URL and may serve different content later.

Pick a model and provider that support the modality and format. OpenRouter returns provider errors, and the library keeps no model allowlist. Video URL support depends on the provider (for example, Gemini on AI Studio accepts YouTube URLs). See the [multimodal guide](https://openrouter.ai/docs/guides/overview/multimodal/overview) and the [endpoint schemas](https://openrouter.ai/openapi.json). For generated audio, see [Chat audio output](#chat-audio-output).

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

- Native options live on `OpenRouterImageOptions`: `resolution`, `aspect-ratio`, `quality`, `output-format`, `background`, `output-compression` and `seed`, plus `inputReferences` (image-to-image) and `providerOptions` (passthrough). `outputFormat` selects the image encoding, such as `webp`. It does not select URL or base64 delivery.
- Spring AI `ImageOptions` map `model` and `n` directly. A paired `width` and `height` becomes a pixel `size`. A lone dimension is rejected after defaults merge.
- `responseFormat` must be omitted or `b64_json`, because the Image API always returns base64. A non-null `style` is rejected because OpenRouter has no equivalent. Both throw `IllegalArgumentException` before HTTP, in `call` and `stream`.
- `OpenRouterImageOptions.fromOptions(...)` converts generic defaults. Generic per-request options override the mapped fields and keep unset native defaults such as `quality` and `providerOptions`.

### Streaming

`stream(ImagePrompt)` exposes OpenRouter's SSE image streaming. Partial previews arrive first, then completed images with usage and cost:

```java
imageModel.stream(prompt).subscribe(response -> response.getResults().forEach(result -> {
    var meta = (OpenRouterImageGenerationMetadata) result.getMetadata();
    boolean preview = meta.partialImageIndex() != null; // null for completed images
}));
```

- The stream ends at `[DONE]`, not at the first completed image.
- It fails with `OpenRouterTruncatedResponseException` if no completed image arrives, or if the connection closes without `[DONE]`, even after a completed image.
- Unknown event types are ignored. A missing, null or blank type fails with `OpenRouterProtocolException`.
- Cancellation stops the stream without a protocol failure.
- `n` is an upper bound. Providers may return fewer images. Multiple images and native streaming depend on the endpoint; see the [OpenRouter image API capabilities](https://openrouter.ai/docs/guides/overview/multimodal/image-generation).

### Response checks

- A JSON response must contain a `data` array. An empty array is a valid empty result for blocking calls. The streaming JSON fallback needs at least one image and raises `OpenRouterTruncatedResponseException` for an empty array.
- Each entry and each completed SSE event needs a nonblank `b64_json` or `url`. Otherwise `OpenRouterProtocolException` is raised.
- An HTTP 200 error envelope raises a structured `OpenRouterApiException`.

### Chat models with image output

Set `OpenRouterChatOptions.builder().modalities(List.of("image", "text"))`, optionally with `imageConfig`. Generated images arrive as `AssistantMessage` media, in sync calls and streams, in both request modes.

Attach generated media to a new `UserMessage` for image edits or follow-up questions. Responses base64 results are exposed as complete data URLs. A supplied `output_format` sets the MIME type (PNG by default). An existing data URL keeps its own MIME type.

### Assistant media history

Chat Completions keeps images when you replay an `AssistantMessage` in conversation history. Responses mode rejects assistant media history, in both synchronous and streaming requests. Attach the media to a `UserMessage` instead, and keep any assistant text and tool history separate. Image understanding still needs a model that accepts image input.

## Chat audio output

Use an [audio-output-capable OpenRouter model](https://openrouter.ai/docs/guides/overview/multimodal/audio) with streaming Chat Completions. Voices and supported encodings depend on the model:

```java
var options = OpenRouterChatOptions.builder()
    .model("your-audio-output-model")
    .modalities(List.of("text", "audio"))
    .audio(new OpenRouterAudioOptions("alloy", "pcm16"))
    .build();
chatModel.stream(new Prompt("Say hello.", options))
    .subscribe(response -> response.getResults().forEach(generation -> {
        AssistantMessage message = generation.getOutput();
        message.getMedia().forEach(media -> {
            byte[] completeAudio = media.getDataAsByteArray(); // decode per the requested format
        });
        if (message.getMetadata().get("openrouter.audio") instanceof Map<?, ?> audio) {
            Object transcript = audio.get("transcript");
        }
    }));
```

Boot equivalents are `spring.ai.openrouter.chat.modalities=text,audio`, `spring.ai.openrouter.chat.audio.voice=alloy` and `spring.ai.openrouter.chat.audio.format=pcm16`. Formats are `wav`, `mp3`, `flac`, `opus` and `pcm16`. PCM bytes are raw audio, not a WAV file. Sample rate and other playback parameters must match the provider. The library does no transcoding.

Only `stream()` supports audio output. `call()` fails with `IllegalArgumentException`. If the app also uses `call()`, set the audio modalities and options on each streaming request instead of in Boot properties.

The audio arrives as one complete Spring AI `Media` per choice, never as partial media:

![Streamed audio.data chunks are each decoded from base64 and appended per choice. Path A: a stop finish reason delivers one complete Media in that same response. Path B: the last delta has only expires_at and no finish reason, as with OpenAI audio models; the Media arrives in one extra response after [DONE] with empty text, no usage and no finish reason. A max_tokens cut-off looks the same, so leave enough room. The transcript goes once into the final assistant metadata as openrouter.audio, not into the text. call() and Responses mode reject output audio.](images/audio-output.svg)

The transcript is not part of the text, so `ChatClient` `.content()` does not return it. Read it from the assistant message metadata key `openrouter.audio`, a map with `transcript` and `format` and, when supplied, `id` and `expires_at`. Normal text stays incremental. Repeated terminal events for a completed audio choice do not emit a second transcript or media object. Wait for successful stream completion before you treat the request as successful, because a later transport or provider error still fails the stream.

A `max_tokens` cut-off shows as a non-`stop` finish reason and fails the stream. A provider that ends audio with only `expires_at` (path B) sends no finish reason, so a cut-off looks like a normal end. Leave enough `max_tokens`.

These cases fail the stream:

- `OpenRouterTruncatedResponseException`: the stream ends before `[DONE]`. A choice has a non-`stop` finish reason, or ends with neither a `stop` finish reason nor a final `expires_at` marker. Audio or text arrives after the `expires_at` marker: that clears the marker, so the choice then needs a `stop` finish reason or another `expires_at`-only marker.
- `OpenRouterProtocolException`: a fragment has invalid base64, or identifiers or formats conflict within a choice. Each data value must encode a complete byte fragment, and splitting inside a base64 value is unsupported. Audio mixed with tool calls in one choice and `message.audio` snapshots are also unsupported.

Retained decoded audio plus transcript and identifier characters (two bytes each) are limited to 16 MiB across unfinished choices per subscription, with at most 128 audio choices per stream. Exceeding either limit raises Spring AI's `NonTransientAiException`. Buffers are released on finish, cancellation or error.

Assistant audio replay is rejected in both request modes, including identifier-only metadata. To continue with only the transcript, create a new text-only assistant message. This discards audio continuation state. Audio IDs and expiry metadata are kept for you but not replayed to the provider.
