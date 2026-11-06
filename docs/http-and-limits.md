# HTTP, timeouts and limits

How to customize the HTTP clients, which timeouts apply, and the size limits for responses and streams.

## HTTP customization and timeout ownership

The supported transport extension points are Spring's `RestClient.Builder` request interceptors (blocking calls) and `WebClient.Builder` filters (streams). Boot auto-configuration clones the `RestClient.Builder` and `WebClient.Builder` beans from your application before applying OpenRouter settings. Your builder beans are not modified.

To change only OpenRouter traffic, declare `OpenRouterRestClientCustomizer` or `OpenRouterWebClientCustomizer` beans. They receive the OpenRouter clones after Boot's global `RestClientCustomizer` and `WebClientCustomizer` beans have run, in `@Order` order. Other clients in the application are unaffected:

```java
@Bean
OpenRouterRestClientCustomizer openRouterLogging() {
	return builder -> builder.requestInterceptor((request, body, execution) -> {
		log.debug("OpenRouter {} {}", request.getMethod(), request.getURI());
		return execution.execute(request, body);
	});
}
```

OpenRouter installs its base URL and its `Authorization` and attribution default headers after the customizers, so builder defaults a customizer sets do not replace them. Interceptors and filters a customizer adds still run on every request and can change any header or the URI. Customizers are trusted application code, so the rules below apply to them too. OpenRouter always replaces the request factory on the blocking builder to install its timeouts; select the transport with a `ClientHttpRequestFactoryBuilder` bean instead. The streaming builder keeps the `max-response-body-size` codec limit. Customizers apply only to the auto-configured `OpenRouterApi`, not to one you declare yourself.

`OpenRouterApi.Builder.objectMapper(...)` serializes every request body and decodes every response, including the JSON fallback of streamed image requests. Boot always passes one: the application's `JsonMapper` (Boot's auto-configured mapper, or one you declare), or a default `JsonMapper` when the application has none or several without `@Primary`. Other `ObjectMapper` beans, such as Boot's `XmlMapper` or `CBORMapper`, are never used. When the default mapper is used although the context holds a `JsonMapper` or plain `ObjectMapper` bean, startup logs a warning that names how many were skipped. The application's mapper settings apply to the wire format. The wire types fix their inclusion rules and tolerate unknown fields, but a global naming strategy can still rename fields they do not name explicitly, such as `model`. A supplied mapper bypasses any JSON converters or codecs registered on the supplied builders. Without one, the builders' request serialization is unchanged.

To own the whole client, define an `OpenRouterApi` bean. API auto-configuration backs off, and the auto-configured chat, embedding and image models use your bean. Set the base URL, attribution, timeout and size limits on its builder, because `spring.ai.openrouter.connection.*` and `app.*` no longer apply:

```java
@Bean
OpenRouterApi openRouterApi(@Value("${spring.ai.openrouter.api-key}") String apiKey,
		WebClient.Builder webClient) {
	return OpenRouterApi.builder()
		.apiKey(apiKey)
		.webClientBuilder(webClient)
		.timeout(Duration.ofMinutes(2))
		// also baseUrl, attribution, limits
		.build();
}
```

### Timeouts

`OpenRouterApi.Builder.timeout(...)` and Boot's `spring.ai.openrouter.connection.timeout` guard streams: the wait for the response and the idle gap between SSE events, keepalives included. Blocking connect and read timeouts belong to the request factory. Boot sets the blocking read timeout from the same property. The read timeout does not inherit `spring.http.clients.read-timeout`, because a generation can take longer than a global timeout tuned for ordinary REST calls. The blocking connect timeout is the first of these that is set, from the most specific to the most general:

1. `spring.ai.openrouter.connection.connect-timeout`.
2. `spring.ai.openrouter.connection.timeout`, when you set it.
3. Spring Boot's shared `spring.http.clients.connect-timeout`, which streams use as well.
4. The 2m default of `connection.timeout`.

`connection.connect-timeout` applies to blocking calls only. Streams connect with `spring.http.clients.connect-timeout`, or with the connector's own default when it is unset.

Streams have a second, transport-level read timeout. Their connector comes from Boot's `WebClient.Builder`, which applies `spring.http.clients.read-timeout`; Reactor Netty uses it as the longest allowed gap between reads. A global read timeout shorter than `connection.timeout` therefore ends a slow stream first. Leave the global value unset, or at least as long as `connection.timeout`. To change only OpenRouter streams, set a connector with an `OpenRouterWebClientCustomizer`. For a programmatic `OpenRouterApi`, supply a configured factory through `restClientBuilder(...)`:

```java
HttpClientSettings settings = HttpClientSettings.defaults()
	.withTimeouts(Duration.ofSeconds(5), Duration.ofSeconds(60));
RestClient.Builder blocking = RestClient.builder()
	.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
OpenRouterApi api = OpenRouterApi.builder().apiKey(apiKey)
	.restClientBuilder(blocking).timeout(Duration.ofMinutes(2)).build();
```

Boot replaces any request factory already set on the builder it clones. To change the transport, define a `ClientHttpRequestFactoryBuilder` bean and tune it through `HttpClientSettings`. In a WebFlux application, Boot creates no such bean and ignores `spring.http.clients.imperative.*` and `ClientHttpRequestFactoryBuilderCustomizer` beans, so blocking OpenRouter calls use `ClientHttpRequestFactoryBuilder.detect()` unless you define the bean yourself. A programmatic `timeout(null)` disables the streaming guard only.

For a deadline on one stream, apply Reactor `timeout(duration)` to the returned `Flux`. It measures gaps between emitted items, not wire keepalives, cancels only its own subscription and is not a total-stream deadline.

### Request-scoped headers

Model request options have no header or timeout fields. For a blocking call, install an interceptor that reads application-owned call context. For a stream, install a filter that reads the Reactor Context at subscription time. This `correlationFilter()`, added with `WebClient.Builder.filter(...)`, sends a synthetic correlation header:

```java
ExchangeFilterFunction correlationFilter() {
	return (request, next) -> Mono.deferContextual(context -> {
		ClientRequest.Builder outgoing = ClientRequest.from(request);
		context.<String>getOrEmpty("correlation-id")
			.ifPresent(id -> outgoing.header("X-Correlation-ID", id));
		return next.exchange(outgoing.build());
	});
}

chatModel.stream(prompt).contextWrite(context -> context.put("correlation-id", "synthetic-123"));
```

Rules for interceptors and filters:

- Clear blocking call context in `finally`. A thread-local does not propagate across reactive scheduler switches.
- Make them safe for concurrent use. Cloning a builder does not deep-copy their captured state.
- Do not mutate shared builders, factories or default headers to customize a single call.
- Use only application-approved header names. Keep credentials and destinations fixed when you construct each client. Do not forward authorization through URI-rewriting filters or cross-origin redirects.

### Separate clients per model family

Boot shares one `OpenRouterApi`. If chat, embeddings or images need different connect/read or idle policies, build each model with its own `OpenRouterApi` using the existing model builders.

### Inspect response headers

An interceptor or filter may read selected successful-response headers before it returns the response unchanged. Do not consume the body or log credentials. Successful response headers are not copied into model metadata, and reading them does not change retry behavior. HTTP errors expose the parsed `Retry-After` through `OpenRouterHttpException.getRetryAfter()`.

### Not supported

- Per-invocation timeout overrides for blocking calls.
- Automatic per-family client selection.
- Connection-pool metrics.
- Credential-provider abstractions.
- Typed successful rate-limit snapshots.

## Size, time and state limits

Each limit applies to one part of a request or its stream. Defaults are shown:

![Limits on a request and its SSE stream. Each inline PDF, audio or video file holds 1 byte to 20 MiB; images are not checked. max-response-body-size, 64MB, caps one blocking response body or one SSE event, not the whole stream; the stream's state budgets still apply. max-error-body-size, 64KB, caps error bodies. connection.timeout, 2m, caps the idle gap between SSE events and sets the blocking connect and read timeouts in Boot. Chat Completions and image streams end with [DONE]; Responses streams end with their own terminal event. Stream state budgets per subscription: Chat Completions keeps 1MB of retained metadata and 128 active choices under chat.streaming-state; Chat Completions tool-call assembly and all Responses state get 1MB, 8192 chunks or events and 2 minutes under chat.tool-call-aggregation; chat audio gets 16 MiB and 128 audio choices. Exceeding a blocking-body or error-body cap, or a state budget, raises OpenRouterLimitExceededException. Chat audio raises NonTransientAiException, and an SSE event over 64MB raises DataBufferLimitException.](images/limits.svg)

### Response buffering limits

`spring.ai.openrouter.connection.max-response-body-size` caps one blocking success body, one SSE event, and the complete JSON fallback for streamed image requests. A blocking success body over the cap raises `OpenRouterLimitExceededException` (`BLOCKING_RESPONSE_BODY_BYTES`). Streaming success decoding uses Spring's codec limits and reports `DataBufferLimitException` when exceeded (wrapped in `WebClientResponseException` for chat and Responses).

An error body over `spring.ai.openrouter.connection.max-error-body-size` raises `OpenRouterLimitExceededException` with the endpoint, HTTP status, bounded sanitized excerpt, and any recoverable error details. Both settings accept 1 through 2147483646 bytes; invalid values fail property binding. Java callers can set the same limits on `OpenRouterApi.Builder`.

### Inline attachments

Inline PDF, audio and video attachments have a size limit; see [PDF, audio and video inputs](multimodal.md#pdf-audio-and-video-inputs).

### Chat Completions stream state limits

Configure this budget with `streamingStateMaxBytes` and `streamingStateMaxChoices` on `OpenRouterChatModel.Builder`, or with the Boot properties `spring.ai.openrouter.chat.streaming-state.max-size` and `spring.ai.openrouter.chat.streaming-state.max-choices`. Both must be positive. These are model-wide settings, not per-request options. They do not affect synchronous calls or Responses. The budget applies to every stream, including streams without tools.

The budget counts the UTF-8 JSON size of incoming metadata, such as reasoning, refusals, annotations, logprobs and opaque extensions, each time it arrives. Ordinary text deltas are free. Exceeding the budget cancels upstream with `OpenRouterLimitExceededException` (`CHAT_STATE_BYTES` or `CHAT_STATE_CHOICES`). Replay metadata is never silently truncated.

### Tool-call assembly limits

Chat Completions streams buffer the fragments of each tool call until the call is complete. The `spring.ai.openrouter.chat.tool-call-aggregation.*` properties bound that buffer. On `OpenRouterChatModel.Builder` they are `toolCallAggregationMaxBytes`, `toolCallAggregationMaxChunks` and `toolCallAggregationMaxDuration`. Responses streams reuse the same settings for all the state they retain. The limits apply per subscription and are model-level settings, not per-request options.

| Property       | Default                 | Chat Completions scope                                                                                                         | Responses scope                                                                                               |
| -------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| `max-size`     | `1MB` (1,048,576 bytes) | Serialized JSON bytes of the buffered chunks, across all buffered choices                                                      | Cumulative serialized UTF-8 JSON bytes admitted from the first state-bearing event through the terminal event |
| `max-chunks`   | `8192`                  | Buffered chunks across all buffered choices, including chunks without choices, such as usage, that arrive while a call is open | Events in that interval, and separately the number of retained output items or items in a response snapshot   |
| `max-duration` | `2m`                    | How long a tool call may stay incomplete, from its first tool-call fragment                                                    | Absolute elapsed time from the first state-bearing event; activity and keepalives do not reset it             |

Exceeding a Chat Completions limit cancels the upstream request and raises `OpenRouterLimitExceededException` with a `STREAMING_TOOL_CALL_*` limit. No partial tool call is emitted, so `ToolCallingAdvisor` runs no tool.

The provider decides how finely it splits the arguments, so the argument size that fits depends on the provider. A chunk with OpenRouter's usual id, model and provider fields serializes to about 250 to 300 bytes, so the byte limit usually applies after 3,500 to 4,200 chunks, before the chunk limit. If your tools expect larger arguments, raise both, for example `max-chunks=32768` with `max-size=4MB`.

When several choices buffer tool calls at once, a merged tool call leaves out usage and extension values older than ones the stream already emitted, so its response can report no usage.

### Responses stream state limits

State-bearing Responses events include output items, text and function-argument fragments, reasoning, refusals, and nonempty response output snapshots. Once the budget starts, it counts every decoded JSON event, including text and repeated snapshots. SSE comments and empty heartbeats are not decoded events: they do not count, but they reset the idle guard.

Exceeding a limit raises `OpenRouterLimitExceededException` with a `RESPONSES_STATE_*` limit and the `/responses` endpoint, before the violating event can release tools. Increase the settings explicitly for larger expected Responses rounds.
