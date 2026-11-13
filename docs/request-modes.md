# Request modes

Select the mode with `spring.ai.openrouter.chat.request-mode` (`openai-chat-completions` or `openai-responses`) or `OpenRouterChatOptions.builder().requestMode(...)`. To switch modes per request, read [Replacement and composition](chat-options.md#replacement-and-composition) first.

## Which mode?

Use Chat Completions. It is the default and the production path. Responses sends requests to OpenRouter's `/responses` endpoint instead of `/chat/completions`. It is experimental: opt in explicitly to test that endpoint. It accepts fewer options than Chat Completions, as the list below shows.

![Chat Completions, the default, and Responses, experimental, side by side: the wire fields for structured output, strict tools and token limits; the options, extraBody keys, cache breakpoints and audio output that Responses rejects; and how each stream ends.](images/request-modes.svg)

Responses rejects these with `IllegalArgumentException` before sending a request. Unset them or use Chat Completions:

- `stopSequences`, `seed`, `repetitionPenalty`, `minP` and `topA`.
- `includeUsage`, with any non-null value.
- The `extraBody` keys `logit_bias`, `logprobs`, `verbosity` and `transforms`. The error names the mode and lists the keys Responses accepts, `prompt_cache_key` and `top_logprobs`.
- Cache breakpoints.
- Chat audio output.
- Assistant media history. Attach media to a `UserMessage` instead.

Other options map to their wire fields. Responses sends a single output limit, `max_output_tokens`. When both `maxTokens` and `maxCompletionTokens` are set, it sends `maxCompletionTokens` and logs a warning if the two differ. It does not reject the pair, because a default of one and a request value of the other is a valid combination; set only `maxCompletionTokens` when you use Responses.

## Structured output

Two options set the output format:

- `outputSchema` is a JSON schema document. It is portable: the schema name is `response` and `strict` is unset.
- `responseFormat` is a typed format: `text`, `json-object` or `json-schema` with a name, `strict` and a schema.

When both are set, `outputSchema` wins and a warning names both options, because `ChatClient.entity(...)` parses the reply against that schema. This follows Spring AI's OpenAI options, where the output schema replaces the response format. When options combine, a request that sets either option replaces both defaults: a request `responseFormat` drops a default `outputSchema`, and the reverse. `OpenRouterResponseFormat.jsonSchema(name, strict, schema)` keeps an explicit `true` or `false`. Schema enforcement depends on the selected provider. Function-tool strictness is separate: see [`toolStrict`](tool-calling.md#strict-function-tools).

With `ChatClient`, `.entity(Type.class, spec -> spec.useProviderStructuredOutput())` passes the entity's schema as `outputSchema`.

Set defaults as Boot properties:

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

For a portable schema, set `spring.ai.openrouter.chat.output-schema` to the JSON document instead. A JSON-schema format requires a nonblank schema: a blank one raises `IllegalArgumentException` when the format is built, so a blank Boot property fails at startup. Malformed JSON raises `IllegalArgumentException` before the request is sent.

## Tool choice

`toolChoice` takes `"auto"`, `"none"`, `"required"` or a named function:

```java
OpenRouterChatOptions.builder().toolChoice(Map.of("type", "function", "name", "lookup"))
```

The mapper converts the named form to the selected endpoint's wire shape. `{type: "function", function: {name: "lookup"}}` is accepted too. Other shapes are rejected, and the error echoes the value. A string other than `auto`, `none` or `required` is rejected when the options are built. A named choice serializes with `type` first.

Tool choice depends on the request's tools. Without tools, `auto` and `none` are left out of the request, and `required` or a named function raises `IllegalArgumentException` before the request is sent. A named function must name one of the request's tools.

## Refusals and incomplete output

Both modes put provider refusal explanations under `openrouter.refusal` in assistant message and generation metadata, separate from the answer text. Check this key to tell a refusal from empty output. A refusal can still have `STOP` as its finish reason. Streaming metadata holds the cumulative refusal text. Keep the original assistant message and its metadata in history so follow-up requests replay the refusal, including refusal-only turns.

Responses maps `max_output_tokens` to `LENGTH` and `content_filter` to `CONTENT_FILTER`, for calls and streams. Other incomplete reasons pass through. An absent reason falls back to the response status. Generation metadata keeps the raw values:

- `openrouter.native_finish_reason`: the unmapped reason.
- `openrouter.responses.status`: the response status.
- `openrouter.responses.incomplete_details`: the typed incomplete details.

A `completed` status or `stop` finish reason does not prove that the output ended before the token limit. When it matters, compare the output token usage with the configured `maxCompletionTokens`.

## Stream contract

A Responses stream must end with `response.completed`, `response.incomplete`, `response.failed` or an error event. Otherwise it raises `OpenRouterTruncatedResponseException`, which is not retried. Any other contract violation raises `OpenRouterProtocolException`; see [Errors and retries](errors-and-retries.md). A non-null error envelope raises a typed provider exception, even over HTTP 200. Unknown event types are ignored. An event with a missing, null or blank `type` raises `OpenRouterProtocolException`, even when a successful terminal event follows, unless it carries an error envelope.
