# Request modes

Chat Completions is the production default. The optional Responses request mode is experimental; applications should opt into it explicitly. Select the mode with `spring.ai.openrouter.chat.request-mode` (`openai-chat-completions` or `openai-responses`) or `OpenRouterChatOptions.builder().requestMode(...)`. To switch modes per request, read [Replacement and composition](chat-options.md#replacement-and-composition) first.

## Option contract

Both modes map `outputSchema` and `responseFormat`: Chat Completions uses `response_format`, and Responses uses `text.format`. An explicit `responseFormat` takes precedence over `outputSchema`. Portable schemas leave `strict` unset; `OpenRouterResponseFormat.jsonSchema(name, strict, schema)` preserves an explicit `true` or `false`. Schema enforcement depends on the selected provider. Function-tool strictness is controlled independently with [`toolStrict`](tool-calling.md#strict-function-tools).

Configure structured-output defaults under the flattened chat namespace (no `options` segment):

```yaml
spring:
  ai:
    openrouter:
      chat:
        response-format:
          type: json-schema
          name: answer
          strict: true
          schema: |
            {"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}
```

`response-format.type` also accepts `text` and `json-object`. For a portable schema, set `spring.ai.openrouter.chat.output-schema` to the JSON document instead; it uses the schema name `response` and leaves strict unset. An explicit `response-format` wins when both are configured. Omit `strict` to leave it unset, or set it to `true` or `false` explicitly. With neither option configured, no format is sent. JSON-schema formats require a schema; malformed JSON fails before the request is sent. These properties use the same mapping as Java options for calls and streams in both request modes.

Responses rejects explicitly set `stopSequences`, `seed`, `repetitionPenalty`, `minP`, `topA`, and `includeUsage` with `IllegalArgumentException` before sending a request. Unset these options or use Chat Completions. Responses usage is read from the response without an opt-in toggle. `maxCompletionTokens` takes precedence over `maxTokens` and maps to `max_output_tokens`. Other sampling, routing, metadata, image, and tool controls map to their corresponding wire fields. `requestMode` selects the endpoint; `toolCallbacks` supply tool definitions and `toolContext` stays on the client.

`toolChoice` accepts `"auto"`, `"none"`, `"required"`, or a named function object: `{type: "function", function: {name: "lookup"}}` or `{type: "function", name: "lookup"}`. Both named shapes are converted to the selected endpoint's form. Legacy `{type: "auto"}`, `{type: "none"}`, and `{type: "required"}` objects are normalized to strings. Other shapes are rejected. These rules apply to calls and streams.

## Refusals and incomplete output

Both request modes expose provider refusal explanations under `openrouter.refusal` in assistant message and generation metadata, separately from answer text. Check this key to distinguish a refusal from ordinary empty output; a refusal can still have `STOP` as its finish reason. Streaming metadata contains cumulative refusal text, with repeated done events and terminal snapshots reconciled rather than concatenated. Keep the original assistant message and its metadata in conversation history so follow-up requests replay the refusal explanation, including refusal-only assistant turns.

Responses normalizes `max_output_tokens` to `LENGTH` and `content_filter` to `CONTENT_FILTER` for both synchronous and streaming calls. Unknown incomplete reasons pass through unchanged; absent reasons fall back to the response status. Generation metadata retains the unnormalized reason as `openrouter.native_finish_reason`, the status as `openrouter.responses.status`, and the typed incomplete details as `openrouter.responses.incomplete_details`. Responses text preserves whitespace-only parts and messages. Streams reconcile text-done, item-done, and terminal snapshots per output item and content part, emitting only missing suffixes. Later text waits for earlier parts to finish so recovered suffixes retain output order. A snapshot that contradicts a received prefix or extends earlier text after later text was emitted raises `OpenRouterProtocolException`. Missing text indexes default to zero.

Terminal snapshots also supply generated images that were not emitted by item-done events. Image IDs identify repeated items; absent IDs fall back to output indexes. An image event with neither an ID nor an index is rejected because identical bytes may belong to distinct images. Repeated identities must retain the same image result and format. Newly recovered images follow terminal output order after any images already emitted.

Responses streams require `response.completed`, `response.incomplete`, `response.failed`, or a terminal error event. `[DONE]` or EOF alone raises `OpenRouterTruncatedResponseException`, even for an empty or text-only stream. The legacy `response.done` shape shown in the [OpenRouter basic-usage guide](https://openrouter.ai/docs/api_reference/responses/basic-usage) is explicitly unsupported and raises `OpenRouterProtocolException`; it does not map final metadata. Chat Completions and image streams continue to accept `[DONE]`.

Responses calls require a final `completed` or `incomplete` status and an `output` array, which may be empty. Streaming terminal events also require a response snapshot whose status matches the event. Missing or contradictory terminal structure fails before buffered tool calls can execute. Non-null error envelopes raise typed provider exceptions even over HTTP 200 and without `status: failed`. Optional fields may be absent and unknown fields are ignored, but invalid typed fields fail decoding rather than discarding response status or output. Valid empty text, refusals, tool calls, media, and incomplete text remain supported.

A `completed` status or `stop` finish reason does not prove that the output ended before the token limit. When it matters, compare the output token usage with the configured `maxCompletionTokens`.
