# Embeddings

`OpenRouterEmbeddingModel` is auto-configured next to the chat model and implements Spring AI's `EmbeddingModel`, so it plugs into vector stores and RAG advisors unchanged:

```yaml
spring:
  ai:
    model:
      embedding: openrouter
    openrouter:
      api-key: ${OPENROUTER_API_KEY}
      embedding:
        model: openai/text-embedding-3-small
        dimensions: 256 # optional, model-dependent
```

```java
float[] vector = embeddingModel.embed("The quick brown fox");
```

Provider routing (`spring.ai.openrouter.embedding.provider.*`) works the same way as for chat. Only `float` embeddings are decoded; requesting `encoding_format: base64` fails fast.

Document embedding keeps raw text by default (`metadata-mode: none`). To include metadata, set `spring.ai.openrouter.embedding.metadata-mode: embed`, or call `OpenRouterEmbeddingModel.builder().metadataMode(MetadataMode.EMBED)` in Java. Both `embed(Document)` and batched document embedding use the document's content formatter. The modes are Spring AI's `MetadataMode`:

- `NONE` embeds only the text and bypasses formatting.
- `EMBED` embeds the text with its metadata, except keys in the document's excluded embedding keys.
- `INFERENCE` does the same but excludes the document's excluded inference keys.
- `ALL` embeds the text with all metadata.

Changing the mode changes the embedding input, so existing vector collections may need re-embedding.

`dimensions()` returns positive configured dimensions without an API call. Otherwise it caches the first successful discovery per model instance, with concurrent callers sharing that discovery. Failed or invalid responses are not cached. The cache belongs to the model's copied default configuration (including model, dimensions and provider routing); per-request overrides never update it. Build a new model for a different default configuration; modifying the options originally supplied to the builder does not change an existing model or its cached dimensions.
