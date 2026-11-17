# Images

`OpenRouterImageModel` implements Spring AI's `ImageModel`. Boot creates it when `spring.ai.model.image` is `openrouter` or unset. To get images in a chat reply instead, see [Images from a chat model](chat.md#images-from-a-chat-model).

The examples use these injected beans: `ChatClient chatClient`, `EmbeddingModel embeddingModel`, `OpenRouterImageModel imageModel`, `TextToSpeechModel speechModel`, `TranscriptionModel transcriptionModel` and `DocumentEmbeddingModel documentEmbeddingModel`. [Getting started](getting-started.md) shows how to get them. Each call is a billable OpenRouter request.

## Image generation

Base64 images from the Image API.

```java
ImageResponse response = imageModel.call(new ImagePrompt("a red panda wearing sunglasses"));
String base64 = response.getResult().getOutput().getB64Json();
```

## Image streaming

Previews first, then the final image.

```java
Flux<ImageResponse> events =
    imageModel.stream(new ImagePrompt("a red panda wearing sunglasses"));
events.subscribe(
    response ->
        response
            .getResults()
            .forEach(
                result -> {
                  boolean preview = OpenRouterImageGenerationMetadata.from(result).isPartial();
                }));
```

## Image to image

Start from a reference image.

```java
ImageResponse response =
    imageModel.call(
        new ImagePrompt(
            "the same sketch as a watercolour",
            OpenRouterImageOptions.builder()
                .inputReferences(List.of("https://example.com/sketch.png"))
                .build()));
```

## Good to know

- `n` is an upper bound. A provider can return fewer images.
- Streaming and more than one image depend on the endpoint.
- `OpenRouterImageGenerationMetadata.mediaType()` is `null` when the provider reports none.
- Set provider-specific fields with `OpenRouterImageOptions.builder().providerExtraBody(Map)`. Boot properties bind lists as maps and numbers as strings.
