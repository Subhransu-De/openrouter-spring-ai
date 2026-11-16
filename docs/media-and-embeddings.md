# Media and embeddings

What the code does not tell you about attachments, generated images, chat audio, speech, transcription and embeddings.

## Attachments

- The library never fetches attachment URLs. Spring AI reads a resource when it builds `Media`, before the library's 20 MiB inline limit applies, so bound resource reads yourself.
- An unchanged message replays a remote URL as a URL, and the URL may serve different content later.
- The library keeps no model allowlist. Pick a model and provider that support the modality and format; video URL support depends on the provider. See OpenRouter's [multimodal guide](https://openrouter.ai/docs/guides/overview/multimodal/overview).

## Image generation

- `n` is an upper bound. Providers may return fewer images, and multiple images and native streaming depend on the endpoint. See OpenRouter's [image generation guide](https://openrouter.ai/docs/guides/overview/multimodal/image-generation).
- `OpenRouterImageGenerationMetadata.mediaType()` is `null` when the provider reports none. The library does not guess it, because `outputFormat` can select another encoding than PNG.
- Boot binds a list under `spring.ai.openrouter.image.provider-extra-body.*` as a map keyed by index, and properties files and environment variables give booleans and numbers as strings. Set such values with `OpenRouterImageOptions.builder().providerExtraBody(Map)`.

## Chat audio output

Only streaming Chat Completions returns audio. Voices and encodings depend on the [model](https://openrouter.ai/docs/guides/overview/multimodal/audio). PCM bytes are raw audio, not a WAV file, and the sample rate must match the provider. The library does no transcoding.

![Streamed audio.data chunks are each decoded from base64 and appended per choice. Path A: a stop finish reason delivers one complete Media in that same response. Path B: the last delta has only expires_at and no finish reason, as with OpenAI audio models; the Media arrives in one extra response after [DONE] with empty text, no usage and no finish reason. A max_tokens cut-off looks the same, so leave enough room. The transcript goes once into the final assistant metadata as openrouter.audio, not into the text. call() and Responses mode reject output audio.](images/audio-output.svg)

OpenAI audio models end a choice with only `expires_at` and no finish reason (path B). A `max_tokens` cut-off looks the same there and goes unnoticed, so leave enough `max_tokens`. The transcript is not part of the text, so `ChatClient` `.content()` does not return it; read it with `OpenRouterAudioOutput.from(message)`. Treat the request as successful only when the stream completes.

## Text-to-speech

`OpenRouterAudioSpeechModel` calls OpenRouter's [speech endpoint](https://openrouter.ai/docs/api/api-reference/tts/create-speech).

- The endpoint defaults to `pcm`: headerless 16-bit little-endian audio, not a WAV file.
- Providers without speed control ignore `speed` or reject a value other than 1. `instructions` works only on providers that support it.
- OpenRouter applies only its data policy preferences to speech, not `order`, `only` or `ignore`. So of the typed preferences, only `dataCollection` is accepted.
- The stream asks the server not to compress the body, because a server that compresses may hold chunks back. A stream that fails after its first chunk ends with the error, so keep the audio only when the stream completes. Use `stream()` for long audio, because `call()` buffers it up to the response body limit.
- Not supported yet: voice cloning (`input_references`), multi-speaker input, `zdr` and provider-specific `provider.options`.

## Transcription

`OpenRouterAudioTranscriptionModel` calls OpenRouter's [transcription endpoint](https://openrouter.ai/docs/api/api-reference/stt/create-transcription).

- The 25 MiB inline limit is OpenRouter's documented upload cap. OpenAI and Groq models accept larger base64 bodies; for them, raise `spring.ai.openrouter.audio.transcription.max-inline-audio-size`, up to 1 GiB. The audio, its base64 encoding and the JSON body are in memory at once, so a large limit needs several times that much heap.
- Upstream providers time out after about 60 seconds of processing, so split long recordings.
- `UrlResource` downloads on the calling thread without a connect or read timeout. For a remote file you do not control, download it with your own HTTP client and pass the bytes. With `sendAudioUrl(true)`, the upstream provider downloads the file instead: the URL must be publicly reachable, and only some providers accept it.
- Only some providers support `verbose_json`, diarization and `keyterms`; OpenRouter rejects the request on the others with HTTP 400.
- OpenRouter applies only its data policy preferences to transcription, so only `dataCollection` is accepted. It has no streaming transcription and no multipart upload.

## Embeddings

### Metadata mode

Documents embed their raw text by default (`metadata-mode: none`). Spring AI's OpenAI and Mistral embedding models default to `EMBED`. When you move a collection that one of them filled, set `spring.ai.openrouter.embedding.metadata-mode: embed` to keep the same input. Even with `none`, their models call the document's `ContentFormatter`, and Spring AI's default template puts two newlines before the text, so their `none` input differs slightly from this library's.

### Text and image documents

`OpenRouterDocumentEmbeddingModel` is opt-in through `spring.ai.model.embedding.multimodal=openrouter` and shares `spring.ai.openrouter.embedding.*` with the text model, so text queries and image documents land in the same vector space. To use two different models, declare your own `DocumentEmbeddingModel` bean.

- A Spring AI `Document` holds either text or one media item, never both, so this model cannot send the joint text-and-image entry that OpenRouter also accepts.
- Unlike chat attachments, an image URL is sent as is, so the upstream provider downloads it.
- Only some [embedding models](https://openrouter.ai/docs/api_reference/embeddings) accept images. The others answer with a provider error, such as HTTP 400.

### Dimensions

Without configured dimensions, `dimensions()` discovers them with a billable embedding request. Vector stores call it: PgVector calls it when it initializes the schema and on every add, delete and query. PgVector also catches a failed call and falls back to 1536 dimensions, so a failed discovery shows up as a table with the wrong dimension rather than as a startup error. Prefer the vector store's own setting, such as `spring.ai.vectorstore.pgvector.dimensions`. `spring.ai.openrouter.embedding.dimensions` also avoids discovery, but it is sent on every request, and only some models accept it.
