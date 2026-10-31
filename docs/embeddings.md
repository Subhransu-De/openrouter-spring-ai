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

Documents embed their raw text by default (`metadata-mode: none`). To include metadata, set `spring.ai.openrouter.embedding.metadata-mode: embed`, or use `OpenRouterEmbeddingModel.builder().metadataMode(MetadataMode.EMBED)`. Single and batched document embedding both apply the mode. The values are Spring AI's `MetadataMode`:

- `NONE`: the text only.
- `EMBED`: the text and metadata, minus the document's excluded embedding keys.
- `INFERENCE`: the text and metadata, minus the excluded inference keys.
- `ALL`: the text and all metadata.

Changing the mode changes the embedding input, so existing vector collections may need re-embedding.

`dimensions()` returns the configured dimensions when they are positive, without an API call. Otherwise it discovers them once per model instance and caches the result. Failed or invalid responses are not cached, and per-request overrides never update the cache. Build a new model to change its defaults.
