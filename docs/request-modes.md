# Request modes

Select the mode with `spring.ai.openrouter.chat.request-mode` (`openai-chat-completions` or `openai-responses`) or `OpenRouterChatOptions.builder().requestMode(...)`. To switch modes per request, read [Replacement and composition](chat-options.md#replacement-and-composition) first.

## Which mode?

Use Chat Completions. It is the default and the production path. Responses sends requests to OpenRouter's `/responses` endpoint instead of `/chat/completions`. It is experimental: try it to test that endpoint, and opt in explicitly. It accepts fewer options than Chat Completions, as the list below shows.

![Chat Completions, the default, and Responses, experimental, side by side: the wire fields for structured output, strict tools and token limits; the options, extraBody keys, cache breakpoints and audio output that Responses rejects; and how each stream ends.](images/request-modes.svg)

Responses rejects these with `IllegalArgumentException` before sending a request. Unset them or use Chat Completions:

- `stopSequences`, `seed`, `repetitionPenalty`, `minP` and `topA`.
- `includeUsage`, with any non-null value.
- The `extraBody` keys `logit_bias`, `logprobs`, `verbosity` and `transforms`.
- Cache breakpoints.
- Chat audio output.
- Assistant media history. Attach media to a `UserMessage` instead.

Other sampling, routing, metadata, image and tool controls map to their wire fields. `requestMode` selects the endpoint. `toolCallbacks` supply tool definitions, and `toolContext` stays on the client.

## Structured output

Two options set the output format:

- `outputSchema` is a JSON schema document. It is portable: the schema name is `response` and `strict` is unset.
- `responseFormat` is a typed format: `text`, `json-object` or `json-schema` with a name, `strict` and a schema.

An explicit `responseFormat` takes precedence over `outputSchema`. `OpenRouterResponseFormat.jsonSchema(name, strict, schema)` preserves an explicit `true` or `false`. Schema enforcement depends on the selected provider. Function-tool strictness is separate: see [`toolStrict`](tool-calling.md#strict-function-tools).

With `ChatClient`, `.entity(Type.class, spec -> spec.useProviderStructuredOutput())` passes the entity's schema as `outputSchema`. A configured `responseFormat` still wins.

Set defaults under the flattened chat namespace (no `options` segment):

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

`response-format.type` also accepts `text` and `json-object`. For a portable schema, set `spring.ai.openrouter.chat.output-schema` to the JSON document instead. An explicit `response-format` wins when both are set. Omit `strict` to leave it unset. With neither option, no format is sent. A JSON-schema format requires a schema, and malformed JSON fails before the request is sent. Properties and Java options map the same way for calls and streams in both modes.

## Tool choice

`toolChoice` accepts `"auto"`, `"none"`, `"required"`, or a named function object: `{type: "function", function: {name: "lookup"}}` or `{type: "function", name: "lookup"}`. Both named shapes convert to the selected endpoint's form. The objects `{type: "auto"}`, `{type: "none"}` and `{type: "required"}` become strings. Other shapes are rejected. These rules apply to calls and streams.

## Refusals and incomplete output

Both modes put provider refusal explanations under `openrouter.refusal` in assistant message and generation metadata, separate from the answer text. Check this key to tell a refusal from empty output. A refusal can still have `STOP` as its finish reason. Streaming metadata holds the cumulative refusal text. Keep the original assistant message and its metadata in history so follow-up requests replay the refusal, including refusal-only turns.

Responses maps `max_output_tokens` to `LENGTH` and `content_filter` to `CONTENT_FILTER`, for calls and streams. Other incomplete reasons pass through. An absent reason falls back to the response status. Generation metadata keeps the raw values:

- `openrouter.native_finish_reason`: the unmapped reason.
- `openrouter.responses.status`: the response status.
- `openrouter.responses.incomplete_details`: the typed incomplete details.

A `completed` status or `stop` finish reason does not prove that the output ended before the token limit. When it matters, compare the output token usage with the configured `maxCompletionTokens`.

## Stream contract (reference)

This section lists what Responses calls and streams must contain, and what the library does with differences.

- A stream must end with `response.completed`, `response.incomplete`, `response.failed` or a terminal error event. Otherwise it raises `OpenRouterTruncatedResponseException`, even when it is empty or text-only. The older `response.done` shape from the [OpenRouter basic-usage guide](https://openrouter.ai/docs/api_reference/responses/basic-usage) raises `OpenRouterProtocolException`.
- A call needs a final `completed` or `incomplete` status and an `output` array, which may be empty. A terminal stream event also needs a response snapshot whose status matches the event. Missing or contradictory structure fails before buffered tool calls can execute.
- A non-null error envelope raises a typed provider exception, even over HTTP 200.
- Text-done, item-done and terminal snapshots are reconciled per output item and content part. Only missing suffixes are emitted, and later text waits for earlier parts. A snapshot that contradicts received text, or extends earlier text after later text was emitted, raises `OpenRouterProtocolException`. Whitespace-only parts are kept. Missing text indexes default to zero.
- Terminal snapshots also supply images not yet emitted, in terminal output order. Images are matched by ID, or by output index when the ID is absent. An image event with neither is rejected. Repeated identities must keep the same result and format.
- Optional fields may be absent and unknown fields are ignored. Invalid typed fields fail decoding. Valid empty text, refusals, tool calls, media and incomplete text stay supported.
