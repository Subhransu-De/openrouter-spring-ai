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

An error envelope in an HTTP 200 response takes precedence over any vectors in it. A transient one raises `OpenRouterTransientApiException`, which the blocking retry policy retries; see [Errors and retries](errors-and-retries.md). Low-level callers can read the nullable `EmbeddingsResponse.error()`.

Embedding indexes must be whole numbers within the input range. A fractional index raises `OpenRouterProtocolException`.

Documents embed their raw text by default (`metadata-mode: none`). To include metadata, set `spring.ai.openrouter.embedding.metadata-mode: embed`, or use `OpenRouterEmbeddingModel.builder().metadataMode(MetadataMode.EMBED)`. Single and batched document embedding both apply the mode. The values are Spring AI's `MetadataMode`:

- `NONE`: the text only.
- `EMBED`: the text and metadata, minus the document's excluded embedding keys.
- `INFERENCE`: the text and metadata, minus the excluded inference keys.
- `ALL`: the text and all metadata.

Changing the mode changes the embedding input, so existing vector collections may need re-embedding. Spring AI's OpenAI and Mistral embedding models default to `EMBED`. When you move a collection filled by one of them to OpenRouter, set `metadata-mode: embed` to keep the same input.

With `none`, this library sends `Document.getText()` as is and does not call the document's `ContentFormatter`. OpenAI's and Mistral's models call it in every mode, and Spring AI's default template puts two newlines before the text even when no metadata is included. A collection they filled with `none` was therefore embedded from slightly different input. A custom `ContentFormatter` takes effect here only with a mode other than `none`.

`dimensions()` returns the configured dimensions when they are positive, without an API call. Otherwise it discovers them once per model instance and caches the result. Failed or invalid responses are not cached, and per-request overrides never update the cache. Build a new model to change its defaults. `getOptions()` returns a copy of them, typed as `OpenRouterEmbeddingOptions`; derive per-request options from it with `mutate()`.

Discovery is a billable embedding request, retried under the model's `RetryTemplate`. A vector store may call `dimensions()` when it has no dimensions of its own. PgVector, for example, calls it when it initializes the schema, and on every add, delete and query to fill in its observation data. Then application startup, including an AOT cache training run, makes a network call. PgVector also catches a failed call and falls back to 1536 dimensions, so a failed discovery shows up as a table with the wrong dimension rather than as a startup error, and while discovery keeps failing, every operation repeats it. Prefer the vector store's own setting, such as `spring.ai.vectorstore.pgvector.dimensions`. `spring.ai.openrouter.embedding.dimensions` also avoids discovery, but it is sent as `dimensions` on every embedding request, which only some models accept.

## Text and image documents

`OpenRouterDocumentEmbeddingModel` implements Spring AI's `DocumentEmbeddingModel` for [models that accept image input](https://openrouter.ai/docs/api_reference/embeddings), such as multimodal retrieval models. It is opt-in and shares the `spring.ai.openrouter.embedding.*` properties with the text model:

```properties
spring.ai.model.embedding=openrouter
spring.ai.model.embedding.multimodal=openrouter
spring.ai.openrouter.embedding.model=vendor/multimodal-embedding-model
```

```java
Document caption = new Document("A boardwalk through a green meadow");
Document photo = new Document(new Media(MimeTypeUtils.IMAGE_PNG, URI.create("https://example.com/boardwalk.png")), Map.of());
EmbeddingResponse response = documentEmbeddingModel.call(new DocumentEmbeddingRequest(caption, photo));
```

Because both beans read the same properties, text queries embedded with `EmbeddingModel` and image documents embedded with `DocumentEmbeddingModel` land in the same vector space. To use two different models, define your own `DocumentEmbeddingModel` bean with `OpenRouterDocumentEmbeddingModel.builder()`; the auto-configured one then backs off.

- Each document becomes one entry of the `input` array with one part: a text document sends a `text` part, formatted with `metadata-mode` as above, and an image document sends an `image_url` part. A URL is sent as is, so the upstream provider downloads it; bytes become a base64 `data:` URL.
- A Spring AI `Document` holds either text or one media item, never both, so this model cannot send the joint text-and-image entry that OpenRouter also accepts.
- Media other than `image/*` fails with `IllegalArgumentException` before the request. A model that does not accept images answers with a provider error, such as HTTP 400, which raises `OpenRouterNonTransientApiException`.
- Results follow the input order whatever order the response lists them in, and each carries the document id, its modality (`TEXT` or `IMAGE`) and MIME type in `EmbeddingResultMetadata`. Usage, retries, observations, request options and `dimensions()` behave as for `OpenRouterEmbeddingModel`. Dimension discovery sends one text request.
