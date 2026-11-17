# Speech and transcription

`OpenRouterAudioSpeechModel` implements Spring AI's `TextToSpeechModel`, with streaming, and `OpenRouterAudioTranscriptionModel` implements `TranscriptionModel`. Both are opt-in: set `spring.ai.model.audio.speech=openrouter` and `spring.ai.model.audio.transcription=openrouter`. For audio in a chat reply, see [Audio output](chat.md#audio-output).

The examples use an injected `TextToSpeechModel speechModel` and `TranscriptionModel transcriptionModel`.

## Text-to-speech

Raw PCM by default; set the format to mp3 for a playable file.

```java
byte[] audio = speechModel.call("Your car is ready for pickup.");
```

## Speech options

Choose the voice and the format for one request.

```java
byte[] mp3 =
    speechModel
        .call(
            new TextToSpeechPrompt(
                "Your car is ready for pickup.",
                OpenRouterAudioSpeechOptions.builder().voice("af_heart").format("mp3").build()))
        .getResult()
        .getOutput();
```

## Speech streaming

Chunks arrive in order; keep the audio only when the stream completes.

```java
Flux<byte[]> chunks = speechModel.stream("Your car is ready for pickup.");
```

## Transcription

The format comes from the file extension.

```java
String text = transcriptionModel.transcribe(new FileSystemResource("inspection-note.wav"));
```

## Transcription with word timestamps

Verbose output adds the duration, segments and words. Only some providers support it.

```java
AudioTranscriptionResponse response =
    transcriptionModel.call(
        new AudioTranscriptionPrompt(
            new FileSystemResource("inspection-note.mp3"),
            OpenRouterAudioTranscriptionOptions.builder()
                .responseFormat(OpenRouterTranscriptionFormat.VERBOSE_JSON)
                .timestampGranularities(List.of(OpenRouterTimestampGranularity.WORD))
                .build()));
OpenRouterTranscriptionMetadata metadata = OpenRouterTranscriptionMetadata.from(response.getResult());
Double seconds = metadata.duration();
List<OpenRouterTranscriptionMetadata.Word> words = metadata.words();
```

## Good to know

- **Speech format:** the endpoint returns `pcm` by default, which is headerless 16-bit audio and not a WAV file.
- **Transcription size:** the audio is sent inline as base64, up to 25 MiB. `spring.ai.openrouter.audio.transcription.max-inline-audio-size` raises the limit.
- **No streaming transcription:** OpenRouter has none, so `TranscriptionModel.stream(...)` fails with `UnsupportedOperationException`.
