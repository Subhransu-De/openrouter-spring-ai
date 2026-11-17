# Embeddings

`OpenRouterEmbeddingModel` implements Spring AI's `EmbeddingModel`, and `OpenRouterDocumentEmbeddingModel` implements `DocumentEmbeddingModel` for text and image documents. Boot creates the first when `spring.ai.model.embedding` is `openrouter` or unset. The second is opt-in: set `spring.ai.model.embedding.multimodal=openrouter`.

The examples use an injected `EmbeddingModel embeddingModel` and `DocumentEmbeddingModel documentEmbeddingModel`.

## Text embedding

Works with Spring AI vector stores.

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

## Batch with options

Options merge with the defaults.

```java
EmbeddingResponse response =
    embeddingModel.call(
        new EmbeddingRequest(
            List.of("first text", "second text"),
            OpenRouterEmbeddingOptions.builder().dimensions(256).build()));
```

## Text and image documents

One vector space; needs a model that accepts images.

```java
Document caption = new Document("A boardwalk through a green meadow");
Document photo =
    new Document(
        new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/boardwalk.png")),
        Map.of());
EmbeddingResponse response =
    documentEmbeddingModel.call(new DocumentEmbeddingRequest(caption, photo));
```

## Good to know

- **Metadata:** documents embed their raw text by default (`metadata-mode: none`). Set `spring.ai.openrouter.embedding.metadata-mode: embed` to include the metadata, as Spring AI's OpenAI model does.
- **Dimensions:** without configured dimensions, `dimensions()` makes a billable request to find them. Set `spring.ai.openrouter.embedding.dimensions` or the vector store's own setting.
