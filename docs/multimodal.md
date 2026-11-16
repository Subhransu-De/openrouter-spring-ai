# Images, documents and audio

Images, PDFs, audio and video as inputs, generated images and audio as outputs, and the dedicated text-to-speech and transcription endpoints.

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

- `OpenRouterImageOptions` holds `resolution`, `aspectRatio`, `quality`, `outputFormat`, `background`, `outputCompression`, `seed`, `inputReferences` (image-to-image) and `providerExtraBody` (a raw map sent unchanged as the wire `provider` object, with wire field names; settings for one upstream provider go under `options.<provider-slug>`. A per-request map replaces the default map, and any key is accepted without validation; chat's `providerExtraBody` merges by key and accepts only its listed keys. Boot binds `spring.ai.openrouter.image.provider-extra-body.*` into it, but binds a list as a map keyed by index, and properties files and environment variables give booleans and numbers as strings, so set such values with `OpenRouterImageOptions.builder().providerExtraBody(Map)`). `outputFormat` selects the image encoding, such as `webp`, not URL or base64 delivery.
- Spring AI `ImageOptions` map `model` and `n` directly. A paired `width` and `height` becomes a pixel `size`. A lone dimension is rejected after defaults merge.
- `responseFormat` must be omitted or `b64_json`, because the Image API always returns base64. A non-null `style` is rejected because OpenRouter has no equivalent. Both throw `IllegalArgumentException` before HTTP, in `call` and `stream`.
- `OpenRouterImageModel.getOptions()` returns a copy of the model's default options, typed as `OpenRouterImageOptions`; derive per-request options from it with `mutate()`.
- `OpenRouterImageOptions.fromOptions(...)` converts generic defaults. Generic per-request options override the mapped fields and keep unset native defaults such as `quality` and `providerExtraBody`.

### Streaming

`stream(ImagePrompt)` exposes OpenRouter's SSE image streaming. Partial previews arrive first, then completed images with usage and cost. This snippet blocks until the stream ends; in a web application, return the `Flux` instead:

```java
imageModel.stream(prompt).doOnNext(response -> response.getResults().forEach(result -> {
    OpenRouterImageGenerationMetadata meta = OpenRouterImageGenerationMetadata.from(result); // never null
    boolean preview = meta.isPartial();
    String mediaType = meta.mediaType(); // null when the provider sent none
})).blockLast();
```

`OpenRouterImageGenerationMetadata.from(...)` works for calls and streams. `mediaType()` is `null` when the provider does not report one; the library does not guess it, because `outputFormat` can select another encoding than PNG.

- The stream ends at `[DONE]`, not at the first completed image.
- It fails with `OpenRouterTruncatedResponseException` if no completed image arrives, or if the connection closes without `[DONE]`, even after a completed image.
- Unknown event types are ignored. A missing, null or blank type fails with `OpenRouterProtocolException`.
- Cancellation stops the stream without a protocol failure.
- If a provider answers a streamed image request with one JSON body instead of SSE, the library turns that body into completed events (the JSON fallback).
- `n` is an upper bound. Providers may return fewer images. Multiple images and native streaming depend on the endpoint; see the [OpenRouter image API capabilities](https://openrouter.ai/docs/guides/overview/multimodal/image-generation).

### Response checks

- A JSON response must contain a `data` array. An empty array is a valid empty result for blocking calls. The JSON fallback needs at least one image and raises `OpenRouterTruncatedResponseException` for an empty array.
- Each entry and each completed SSE event needs a nonblank `b64_json` or `url`. Otherwise `OpenRouterProtocolException` is raised.
- An HTTP 200 error envelope takes precedence over image data. It raises `OpenRouterTransientApiException` when transient and `OpenRouterInBandErrorException` otherwise. Errors inside an accepted stream, including its JSON fallback, are not retried.

### Chat models with image output

Set `OpenRouterChatOptions.builder().modalities(OpenRouterModality.IMAGE, OpenRouterModality.TEXT)` or `modalities(List.of("image", "text"))`, optionally with `imageConfig`. Generated images arrive as `AssistantMessage` media, in sync calls and streams, in both request modes.

Modalities are lower-cased when the options are built, because OpenRouter documents lower-case values. A modality other than `text`, `image` or `audio` is sent and logged once as a warning. `imageConfig` is sent as `image_config` whenever it is set; the library does not check the modalities, because its keys are model-specific. Include `image` in the modalities to get image output.

Attach generated media to a new `UserMessage` for image edits or follow-up questions. Responses base64 results are exposed as complete data URLs. To get the bytes, call `OpenRouterMedia.bytes(media)`. It decodes a base64 data URL and returns raw bytes unchanged, so it also reads [chat audio output](#chat-audio-output). It does not download a remote `https` URL; it throws `IllegalArgumentException` for one. A supplied `output_format` sets the MIME type (PNG by default). An existing data URL keeps its own MIME type.

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
            byte[] completeAudio = OpenRouterMedia.bytes(media); // decode per the requested format
        });
        OpenRouterAudioOutput.from(message)
            .ifPresent(audio -> System.out.println(audio.transcript()));
    }))
    .blockLast(); // waits for successful completion; in a web application, return the Flux
```

Boot equivalents are `spring.ai.openrouter.chat.modalities=text,audio`, `spring.ai.openrouter.chat.audio.voice=alloy` and `spring.ai.openrouter.chat.audio.format=pcm16`. Formats are `wav`, `mp3`, `flac`, `opus` and `pcm16`. PCM bytes are raw audio, not a WAV file. Sample rate and other playback parameters must match the provider. The library does no transcoding.

Only `stream()` supports audio output. `call()` fails with `IllegalArgumentException`. If the app also uses `call()`, set the audio modalities and options on each streaming request instead of in Boot properties.

The audio arrives as one complete Spring AI `Media` per choice, never as partial media:

![Streamed audio.data chunks are each decoded from base64 and appended per choice. Path A: a stop finish reason delivers one complete Media in that same response. Path B: the last delta has only expires_at and no finish reason, as with OpenAI audio models; the Media arrives in one extra response after [DONE] with empty text, no usage and no finish reason. A max_tokens cut-off looks the same, so leave enough room. The transcript goes once into the final assistant metadata as openrouter.audio, not into the text. call() and Responses mode reject output audio.](images/audio-output.svg)

The transcript is not part of the text, so `ChatClient` `.content()` does not return it. Read it with `OpenRouterAudioOutput.from(message)`, which is empty until the message that carries the completed audio. The record holds `transcript()` and `format()` and, when the provider sends them, `id()` and `expiresAt()` (epoch seconds); otherwise those two are `null`. The same values stay in the assistant message metadata under `OpenRouterMetadata.AUDIO` (`openrouter.audio`), a map with `transcript`, `format`, `id` and `expires_at`. Normal text stays incremental. Treat the request as successful only when the stream completes, because a later transport or provider error still fails it.

A `max_tokens` cut-off fails the stream when the provider reports a non-`stop` finish reason, but goes unnoticed on path B, which sends none. Leave enough `max_tokens`.

These cases fail the stream:

- `OpenRouterTruncatedResponseException`: the stream ends before `[DONE]`, or a choice ends without a `stop` finish reason or a final `expires_at` marker.
- `OpenRouterProtocolException`: a fragment has invalid base64, identifiers or formats conflict within a choice, or a choice mixes audio with tool calls. Each data value must encode a complete byte fragment, and `message.audio` snapshots are unsupported.
- `OpenRouterLimitExceededException` with `CHAT_AUDIO_BYTES` or `CHAT_AUDIO_CHOICES`: the fixed audio buffer limits (16 MiB, 128 audio choices per stream) are exceeded; see [Size, time and state limits](http-and-limits.md#size-time-and-state-limits).

Assistant audio replay is rejected in both request modes, including identifier-only metadata. To continue with only the transcript, create a new text-only assistant message. This discards audio continuation state. Audio IDs and expiry metadata are kept for you but not replayed to the provider.

## Text-to-speech

`OpenRouterAudioSpeechModel` implements Spring AI's `TextToSpeechModel` on OpenRouter's [speech endpoint](https://openrouter.ai/docs/api/api-reference/tts/create-speech). The audio models are opt-in: set `spring.ai.model.audio.speech=openrouter` to get the bean. Pick a speech model and one of its voices:

```java
TextToSpeechResponse response = speechModel.call(new TextToSpeechPrompt("Your car is ready for pickup.",
    OpenRouterAudioSpeechOptions.builder()
        .model("your-speech-model")
        .voice("your-voice")
        .format("mp3")
        .build()));
byte[] mp3 = response.getResult().getOutput();
```

`stream(...)` emits each chunk of bytes as it arrives and does not buffer the whole body. Write the chunks in order to get the audio. A stream that fails after its first chunk ends with the error, not with completion, so keep the audio only when the stream completes. Cancelling the subscription closes the connection. Rate-limit rejections are retried before the first chunk only. The stream asks the server not to compress the body, because a server that compresses may hold chunks back.

- `format` is sent as `response_format` and must be `mp3` or `pcm`. The endpoint defaults to `pcm`, which is headerless 16-bit little-endian audio, not a WAV file. Other formats are rejected when the options are built, including formats that Spring AI's portable `TextToSpeechOptions` carry for other providers.
- `speed` must be positive. Providers without speed control ignore it or reject a value other than 1. `instructions` sets tone or pacing for the providers that support it.
- OpenRouter applies only its data policy preferences to speech; `order`, `only` and `ignore` are not applied. Of the typed preferences only `dataCollection` is accepted, and setting another one fails before the request. `zdr` and provider-specific `provider.options` are not exposed yet.
- Voice cloning (`input_references`) and multi-speaker input are not supported yet.
- `call()` buffers the audio up to the [response body limit](http-and-limits.md#size-time-and-state-limits). Use `stream()` for long audio.

Boot properties are under `spring.ai.openrouter.audio.speech.*`, such as `model`, `voice`, `format`, `speed`, `instructions`, `session-id`, `trace.*` and `provider.data-collection`.

## Transcription

`OpenRouterAudioTranscriptionModel` implements Spring AI's `TranscriptionModel` on OpenRouter's [transcription endpoint](https://openrouter.ai/docs/api/api-reference/stt/create-transcription). Set `spring.ai.model.audio.transcription=openrouter` to get the bean:

```java
AudioTranscriptionResponse response = transcriptionModel.call(new AudioTranscriptionPrompt(
    new FileSystemResource("inspection-note.wav"),
    OpenRouterAudioTranscriptionOptions.builder().model("your-transcription-model").language("en").build()));
String text = response.getResult().getOutput();
OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
Double cost = metadata.usage() != null ? metadata.usage().cost() : null;
```

The model reads the resource and sends it inline as base64 JSON. The audio format comes from `audioFormat`, or else from the extension of the resource's filename. A resource without either is rejected before the request. Resources larger than 25 MiB, OpenRouter's documented upload cap, are rejected before they are read in full. OpenAI and Groq models accept larger base64 bodies; raise the limit, up to 1 GiB, with `spring.ai.openrouter.audio.transcription.max-inline-audio-size` or `OpenRouterAudioTranscriptionModel.Builder.maxInlineAudioBytes(...)` for them. An audio format that is not 1 to 16 letters, digits or `+._-`, such as `wav~`, is rejected before the resource is read. Upstream providers time out after about 60 seconds of processing, so split long recordings.

An HTTP(S) resource, such as a `UrlResource`, is downloaded by this application and sent inline by default. `UrlResource` downloads on the calling thread without a connect or read timeout, so for a remote file you do not control, download it with your own HTTP client and pass the bytes. With `sendAudioUrl(true)`, its URL is sent instead and the upstream provider downloads the file. The URL must then be publicly reachable, only some providers accept it, and the inline size limit does not apply. When the URL path has no extension, set `audioFormat`. Other resources are rejected when `sendAudioUrl` is set.

- `responseFormat(OpenRouterTranscriptionFormat.VERBOSE_JSON)` adds the task, language, duration, confidence, segments and, when requested, words. `diarize` and `timestampGranularities` need it; a request that sets them with `json` fails before it is sent. Only some providers support `verbose_json`, diarization and `keyterms`, and OpenRouter rejects the others with HTTP 400.
- `OpenRouterTranscriptionMetadata.from(transcription)` returns the usage (`seconds`, `cost` and tokens) and the `verbose_json` details, including each segment's timestamps, speaker, tokens and decoding statistics such as `avgLogprob` and `noSpeechProb`. The lists are empty, and the other values `null`, when the provider did not send them. A response without `text` raises `OpenRouterProtocolException`.
- OpenRouter applies only its data policy preferences to transcription. Of the typed preferences only `dataCollection` is accepted, and setting another one fails before the request.
- OpenRouter has no streaming transcription, so `stream(...)` fails with `UnsupportedOperationException`, Spring AI's default. Multipart uploads are not supported.

Boot properties are under `spring.ai.openrouter.audio.transcription.*`, such as `model`, `language`, `response-format=verbose-json`, `timestamp-granularities=word,segment`, `audio-format`, `send-audio-url` and `provider.data-collection`.

Unlike chat, embeddings and images, the audio models are opt-in, because Spring AI's other audio providers also create their models when the selector is unset, and an application that already uses one would otherwise get two beans of the same type. An audio selector that names `openrouter` also creates the OpenRouter client when chat, embeddings and images use other providers.
