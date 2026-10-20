# Images, documents and audio

Images, PDFs, audio and video as inputs, and generated images and audio as outputs.

## Image inputs

Attach image media to a `UserMessage` and it is sent as OpenRouter `image_url` content parts. Plain URLs pass through; classpath/byte resources are inlined as base64 data URLs:

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

Both synchronous and streaming calls accept these `UserMessage` attachments:

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

PDF filenames come from `Media.getName()`; set `.name(...)` to retain an original filename. Audio/video names are not sent. Other file types, audio formats, MIME parameters and mismatched data-URL MIME types are rejected explicitly. This does not add Anthropic Messages support or uploaded file IDs. For generated audio, see [Chat audio output](#chat-audio-output).

Each new inline attachment must contain 1 byte through 20 MiB of decoded data. The limit is checked before encoding bytes or decoding a base64 data URL. Empty and invalid base64 content is rejected. Provider request, file, duration and attachment-count limits still apply, including to remote URLs; the local limit is not a guarantee of provider acceptance. Existing image behavior is unchanged.

The integration never fetches attachment URLs. Spring AI materializes resources when constructing `Media`, before this integration can enforce its limit; callers must bound resource reads themselves or supply bounded bytes. Mapping does not modify caller-owned bytes or messages. Reusing unchanged messages replays the same attachments in order, after the message text, including across tool continuations. A remote URL is replayed as a URL and may serve different content later.

Select a model/provider supporting the modality and format. OpenRouter validates routing compatibility and returns provider errors; the library does not maintain a static model allowlist. Video URL support is provider-specific (for example, Gemini on AI Studio accepts YouTube URLs). See the [multimodal guide](https://openrouter.ai/docs/guides/overview/multimodal/overview) and [endpoint schemas](https://openrouter.ai/openapi.json). No new Boot properties or options are required; attachments use the existing message API.

## Image generation

`OpenRouterImageModel` implements Spring AI's `ImageModel` over OpenRouter's unified Image API (`POST /images`):

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

The OpenRouter-native knobs (`resolution`, `aspect-ratio`, `quality`, `output-format`, `background`, `output-compression`, `seed`) are available on `OpenRouterImageOptions`, along with `inputReferences` for image-to-image work and `providerOptions` passthrough. Generic Spring AI `ImageOptions` map `model` and `n` directly and translate paired `width`/`height` into pixel `size` (a lone dimension is rejected after merging defaults). `responseFormat` must be omitted or exactly `b64_json`; any other non-null format, including `url`, and any non-null `style` fail with `IllegalArgumentException` before HTTP transport in both `call` and `stream`. These portable settings have no wire fields. Use `OpenRouterImageOptions.fromOptions(...)` to convert generic defaults; generic per-request options override mapped fields while retaining unset native defaults, including `quality`, `outputFormat`, and `providerOptions`. Native `outputFormat` selects image encoding (for example, `webp`), not URL versus base64 delivery.

`OpenRouterImageModel.stream(ImagePrompt)` exposes OpenRouter's SSE image streaming: partial previews arrive first (see `OpenRouterImageGenerationMetadata.partialImageIndex()`), then completed images with usage and cost. The stream ends at `[DONE]`, not at the first completed image. A stream must deliver at least one completed image; previews followed by `[DONE]`, or `[DONE]` alone, fail with `OpenRouterTruncatedResponseException`. Unknown event types are ignored for forward compatibility, while missing, null, or blank types fail with `OpenRouterProtocolException`. Unknown events never produce images or satisfy completion. Cancellation stops the stream without recording a protocol failure. An SSE connection that closes without `[DONE]` fails as a truncated response, even if it delivered a completed image. `n` is an upper bound; providers may return fewer images, and support for multiple images and native streaming depends on the endpoint. Check the [OpenRouter image API capabilities](https://openrouter.ai/docs/guides/overview/multimodal/image-generation) before combining them.

Image JSON responses must contain a `data` array. An empty array remains a valid empty result for blocking calls. The streaming JSON fallback requires at least one image and raises `OpenRouterTruncatedResponseException` for an empty array. Each entry and each completed SSE event must contain nonblank `b64_json` or `url`. The JSON streaming fallback preserves both forms. Missing required content raises `OpenRouterProtocolException`; HTTP 200 error envelopes raise structured `OpenRouterApiException` errors before success conversion. Synchronous Chat Completions choices require a `message` object, but its text may be empty (including tool, refusal, and media messages). Usage-only chat stream chunks and image partial previews remain supported.

Image-capable _chat_ models work too: set `OpenRouterChatOptions.builder().modalities(List.of("image", "text"))` (optionally with `imageConfig`) and generated images arrive as `AssistantMessage` media — in sync calls and streams alike, in both chat-completions and responses request modes.

Generated media can be attached directly to a new `UserMessage` for image edits or follow-up questions. Responses base64 results are exposed as complete data URLs; `output_format`, when supplied, determines the MIME type (PNG by default), while an existing data URL retains its own MIME type.

Chat Completions preserves images when an `AssistantMessage` is replayed in conversation history. Responses mode rejects assistant media history explicitly: attach the media to a `UserMessage` instead, retaining any assistant text/tool history separately. This applies to both synchronous and streaming requests. Image understanding still requires a model that supports image input.

## Chat audio output

Use an [audio-output-capable OpenRouter model](https://openrouter.ai/docs/guides/overview/multimodal/audio) with streaming Chat Completions. Voices and supported encodings depend on the model:

```java
var options = OpenRouterChatOptions.builder()
    .model("your-audio-output-model")
    .modalities(List.of("text", "audio"))
    .audio(new OpenRouterAudioOptions("alloy", "pcm16"))
    .build();
chatModel.stream(new Prompt("Say hello.", options))
    .subscribe(response -> response.getResults().forEach(generation ->
        generation.getOutput().getMedia().forEach(media -> {
            byte[] completeAudio = media.getDataAsByteArray();
            // Save or play using a decoder appropriate for the requested format.
        })));
```

Boot equivalents are `spring.ai.openrouter.chat.modalities=text,audio`, `spring.ai.openrouter.chat.audio.voice=alloy`, and `spring.ai.openrouter.chat.audio.format=pcm16`. Formats are `wav`, `mp3`, `flac`, `opus`, and `pcm16`; PCM bytes are raw audio, not a WAV file. Sample rate and other playback parameters must match the selected provider. No transcoding is performed. `call()` and Responses mode reject output audio; there is no blocking adapter.

Each `delta.audio.data` value is decoded independently from base64, then its bytes are appended per choice. One complete Spring AI `Media` is emitted when the choice completes, and no partial media is emitted. A choice completes in one of two ways:

- It receives a `stop` finish reason. The media arrives with that event.
- Its last audio delta contains only `expires_at` and no finish reason ever arrives. Some providers, including OpenAI audio models, end audio this way. The media then arrives in one extra response after the stream ends normally with `[DONE]`. That response has empty text, no usage, and no finish reason.

Without a finish reason, a response cut short by `max_tokens` cannot be told apart from a complete one. Set a limit large enough for the expected audio.

Normal text remains incremental; the assembled transcript is exposed once in the final assistant metadata under `openrouter.audio`, alongside `format` and, when supplied, `id` and `expires_at`. The transcript is not appended to text, so simultaneous text and speech do not produce duplicate text. Repeated terminal events for completed audio choices do not emit a second transcript or media object. Wait for successful stream completion before treating the entire request as successful: a later transport/provider error still fails the stream.

Retained decoded audio plus transcript/identifier characters (counted as two bytes each) are limited to 16 MiB across unfinished choices per subscription, with at most 128 audio choices per stream. Buffer capacity and temporary decoding/final copies add bounded overhead. Buffers are released on finish, cancellation, or error. A stream that ends before `[DONE]`, a choice with neither completion signal, audio or text after the `expires_at` marker, invalid base64, conflicting identifiers/formats, and non-`stop` audio finishes fail explicitly. Each data value must encode a complete byte fragment; arbitrary splits inside a base64 value are unsupported. Audio mixed with tool calls in one choice and `message.audio` snapshots are unsupported. Except for truncation and size limits, these failures raise `OpenRouterProtocolException`.

Assistant audio replay is rejected in both request modes, including identifier-only metadata. To continue using only its transcript, explicitly create a new text-only assistant message. This discards audio continuation state; audio IDs and expiry metadata are preserved for callers, not automatically replayed to the provider.
