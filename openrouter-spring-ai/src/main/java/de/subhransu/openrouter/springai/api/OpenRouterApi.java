package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.DecisionsRequest;
import de.subhransu.openrouter.springai.api.dto.DecisionsResponse;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.SpeechRequest;
import de.subhransu.openrouter.springai.api.dto.TranscriptionRequest;
import de.subhransu.openrouter.springai.api.dto.TranscriptionResponse;
import de.subhransu.openrouter.springai.errors.OpenRouterDiagnosticSanitizer;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.internal.StreamRetries;
import de.subhransu.openrouter.springai.support.OptionChecks;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.SequenceInputStream;
import java.util.Collections;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.codec.DecodingException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.AbstractJacksonDecoder;
import org.springframework.http.codec.DecoderHttpMessageReader;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

public class OpenRouterApi {

	public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

	public static final int DEFAULT_MAX_RESPONSE_BODY_BYTES = 64 * 1024 * 1024;

	public static final int DEFAULT_MAX_ERROR_BODY_BYTES = 64 * 1024;

	private static final String IMAGES = "/images";

	private static final String SPEECH = "/audio/speech";

	static final String DECISIONS = "/alpha/decisions";

	// Errors arrive as JSON, so the speech request accepts both.
	private static final List<MediaType> SSE = List.of(MediaType.TEXT_EVENT_STREAM);

	private static final List<MediaType> AUDIO_OR_ERROR = List.of(new MediaType("audio", "*"),
			MediaType.APPLICATION_JSON);

	private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;

	private final WebClient webClient;

	private final ObjectMapper objectMapper;

	private final boolean customObjectMapper;

	private final StreamEventBinder eventBinder;

	private final @Nullable BlockingResponseDecoder responseDecoder;

	private final OpenRouterHttpExceptionFactory httpExceptionFactory;

	private final OpenRouterDiagnosticSanitizer diagnosticSanitizer;

	private final @Nullable Duration timeout;

	private final int maxResponseBodyBytes;

	private final int maxErrorBodyBytes;

	private final SpeechExchange speechExchange = new SpeechExchange();

	private OpenRouterApi(Builder builder) {
		Assert.hasText(builder.apiKey, "OpenRouter API key must not be empty");
		Assert.notNull(builder.objectMapper, "ObjectMapper must not be null");

		String baseUrl = StringUtils.hasText(builder.baseUrl) ? builder.baseUrl : DEFAULT_BASE_URL;
		this.objectMapper = builder.objectMapper;
		this.customObjectMapper = builder.customObjectMapper;
		this.eventBinder = new StreamEventBinder(this.objectMapper, !this.customObjectMapper);
		this.httpExceptionFactory = new OpenRouterHttpExceptionFactory(this.objectMapper, builder.apiKey);
		this.diagnosticSanitizer = OpenRouterDiagnosticSanitizer.forApiKey(builder.apiKey);
		this.timeout = builder.timeout;
		Assert.isTrue(builder.maxResponseBodyBytes > 0, "Maximum response body size must be greater than zero");
		Assert.isTrue(builder.maxErrorBodyBytes > 0, "Maximum error body size must be greater than zero");
		Assert.isTrue(builder.maxResponseBodyBytes < Integer.MAX_VALUE,
				"Maximum response body size must be less than Integer.MAX_VALUE");
		Assert.isTrue(builder.maxErrorBodyBytes < Integer.MAX_VALUE,
				"Maximum error body size must be less than Integer.MAX_VALUE");
		this.maxResponseBodyBytes = builder.maxResponseBodyBytes;
		this.maxErrorBodyBytes = builder.maxErrorBodyBytes;
		this.responseDecoder = this.customObjectMapper ? null
				: new BlockingResponseDecoder(this.objectMapper, this.maxResponseBodyBytes);

		// Blocking timeouts belong to the request factory. Auto-configuration installs
		// a timeout-configured factory on its own builder clone; programmatic callers
		// supply their factory. This timeout field only guards streaming idle gaps.
		RestClient.Builder restClientBuilder = builder.restClientBuilder != null ? builder.restClientBuilder.clone()
				: RestClient.builder();
		OpenRouterAttributionInterceptor attribution = new OpenRouterAttributionInterceptor(builder.httpReferer,
				builder.appTitle, builder.appCategories);
		restClientBuilder.requestInterceptor(attribution);
		this.restClient = restClientBuilder.baseUrl(baseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + builder.apiKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			.build();

		WebClient.Builder webClientBuilder = builder.webClientBuilder != null ? builder.webClientBuilder.clone()
				: WebClient.builder();
		this.webClient = webClientBuilder.baseUrl(baseUrl)
			.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(this.maxResponseBodyBytes))
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + builder.apiKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
			.defaultHeaders(attribution::applyTo)
			.build();
	}

	// Checked here as well as in the chat options, so a direct API caller cannot replace
	// the configured key, which the diagnostic sanitizer redacts, or the attribution, or
	// send a malformed value that a connector would echo in its exception.
	private static Consumer<HttpHeaders> requestHeaders(Map<String, String> headers) {
		Assert.notNull(headers, "headers must not be null");
		OptionChecks.customHeaders(headers);
		return (outgoing) -> headers.forEach(outgoing::set);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Return the sanitizer that redacts a client's configured API key, including its
	 * encoded forms, from provider diagnostics. The OpenRouter models use it for errors
	 * that arrive inside a successful response or stream. It does not expose the key.
	 * <p>
	 * This is static so that it records no interaction on a mocked client. A mock skips
	 * the constructor, so it gets the keyless sanitizer.
	 * @param api the client
	 * @return the diagnostic sanitizer bound to the client
	 */
	public static OpenRouterDiagnosticSanitizer diagnosticSanitizer(OpenRouterApi api) {
		OpenRouterDiagnosticSanitizer sanitizer = api.diagnosticSanitizer;
		return sanitizer != null ? sanitizer : OpenRouterDiagnosticSanitizer.keyless();
	}

	public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
		return chatCompletion(request, Map.of());
	}

	/**
	 * Create a chat completion with HTTP headers for this request only.
	 * @param request the request
	 * @param headers the headers to add; a header that this client sets itself, such as
	 * Authorization, is rejected, and the values are redacted from error details
	 * @return the response
	 */
	public ChatCompletionResponse chatCompletion(ChatCompletionRequest request, Map<String, String> headers) {
		return blockingPost("/chat/completions", request, ChatCompletionResponse.class, headers);
	}

	public ResponsesResult responses(ResponsesRequest request) {
		return responses(request, Map.of());
	}

	/**
	 * Create a response with HTTP headers for this request only.
	 * @param request the request
	 * @param headers the headers to add; a header that this client sets itself, such as
	 * Authorization, is rejected, and the values are redacted from error details
	 * @return the response
	 */
	public ResponsesResult responses(ResponsesRequest request, Map<String, String> headers) {
		return blockingPost("/responses", request, ResponsesResult.class, headers);
	}

	public EmbeddingsResponse embeddings(EmbeddingsRequest request) {
		return blockingPost("/embeddings", request, EmbeddingsResponse.class, Map.of());
	}

	public ImagesResponse images(ImagesRequest request) {
		return blockingPost("/images", request, ImagesResponse.class, Map.of());
	}

	public TranscriptionResponse transcription(TranscriptionRequest request) {
		return blockingPost("/audio/transcriptions", request, TranscriptionResponse.class, Map.of());
	}

	/**
	 * Ask a Decisions model typed questions about a state. OpenRouter's Decisions API is
	 * in alpha and sits beside the versioned API: for a base URL whose path ends in
	 * {@code /v1} the request goes to the sibling {@code alpha/decisions}, which is
	 * {@code https://openrouter.ai/api/alpha/decisions} for the default base URL; any
	 * other base URL gets {@code /alpha/decisions} appended, like the other endpoints.
	 * @param request the decisions request
	 * @return one answer per question
	 */
	public DecisionsResponse decisions(DecisionsRequest request) {
		return blockingPost(DECISIONS, this.restClient.post().uri(DecisionsUri::resolve), request,
				DecisionsResponse.class, this.httpExceptionFactory);
	}

	/**
	 * Synthesize speech and return the complete audio. The body is buffered up to the
	 * maximum response body size; use {@link #speechStream(SpeechRequest)} for long
	 * audio.
	 * @param request the speech request
	 * @return the audio bytes in the requested format
	 */
	public byte[] speech(SpeechRequest request) {
		return this.speechExchange.call(request);
	}

	/**
	 * Synthesize speech and emit the audio bytes as they arrive, without buffering the
	 * whole body. The connection timeout bounds the wait for each chunk. A connection
	 * that closes early fails the stream; cancelling the subscription closes the
	 * connection.
	 * @param request the speech request
	 * @return the audio chunks in arrival order
	 */
	public Flux<byte[]> speechStream(SpeechRequest request) {
		return this.speechExchange.stream(request);
	}

	private <T> T blockingPost(String uri, Object request, Class<T> responseType, Map<String, String> headers) {
		return blockingPost(uri, this.restClient.post().uri(uri).headers(requestHeaders(headers)), request,
				responseType, this.httpExceptionFactory.redacting(headers.values()));
	}

	// The uri labels errors with the logical endpoint, apart from the transport URI.
	private <T> T blockingPost(String uri, RestClient.RequestBodySpec outgoing, Object request, Class<T> responseType,
			OpenRouterHttpExceptionFactory errors) {
		if (this.customObjectMapper) {
			outgoing.body(output -> this.objectMapper.writeValue(output, request));
		}
		else {
			outgoing.body(request);
		}
		return TransportFailures.blocking(uri,
				() -> outgoing.exchange((httpRequest, response) -> readResponse(uri, response, responseType, errors)));
	}

	private <T> T readResponse(String uri, ClientHttpResponse response, Class<T> responseType,
			OpenRouterHttpExceptionFactory errors) throws IOException {
		HttpStatusCode statusCode = response.getStatusCode();
		if (this.responseDecoder != null && !statusCode.isError()) {
			return this.responseDecoder.decode(uri, statusCode, response.getBody(), responseType);
		}
		return readBlockingResponse(uri, response, responseType, errors);
	}

	private <T> T readBlockingResponse(String uri, ClientHttpResponse response, Class<T> responseType,
			OpenRouterHttpExceptionFactory errors) throws IOException {
		BoundedBody body = checkedBody(uri, response, errors);
		try {
			T decoded = body.decode(this.objectMapper, responseType);
			if (decoded == null) {
				throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.EMPTY_BODY, uri,
						"Null OpenRouter " + uri + " response");
			}
			return decoded;
		}
		catch (JacksonException ex) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.DECODE, uri,
					"Failed to decode OpenRouter " + uri + " response", ex);
		}
	}

	/**
	 * Read a non-empty success body within the response limit, or throw the classified
	 * error for an error status.
	 */
	private BoundedBody checkedBody(String uri, ClientHttpResponse response, OpenRouterHttpExceptionFactory errors)
			throws IOException {
		HttpStatusCode statusCode = response.getStatusCode();
		int limit = statusCode.isError() ? this.maxErrorBodyBytes : this.maxResponseBodyBytes;
		BoundedBody body = BoundedBody.read(response.getBody(), limit);
		if (body.exceeded()) {
			if (statusCode.isError()) {
				String excerpt = new String(body.bytes(), StandardCharsets.UTF_8);
				throw errors.createErrorBodyLimit(uri, statusCode, excerpt, limit, limit + 1L);
			}
			throw new OpenRouterLimitExceededException(
					OpenRouterLimitExceededException.Limit.BLOCKING_RESPONSE_BODY_BYTES, limit, limit + 1L, uri,
					statusCode, null, null);
		}
		if (statusCode.isError()) {
			String errorBody = new String(body.bytes(), StandardCharsets.UTF_8);
			throw errors.create(uri, statusCode, response.getHeaders(), errorBody);
		}
		if (body.size() == 0) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.EMPTY_BODY, uri,
					"Empty OpenRouter " + uri + " response");
		}
		return body;
	}

	public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
		return chatCompletionStream(request, Map.of());
	}

	/**
	 * Stream a chat completion with HTTP headers for this request only.
	 * @param request the request
	 * @param headers the headers to add; a header that this client sets itself, such as
	 * Authorization, is rejected, and the values are redacted from error details
	 * @return the chunks
	 */
	public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request, Map<String, String> headers) {
		return stream("/chat/completions", request, ChatCompletionChunk.class, headers);
	}

	// Providers without native image streaming make OpenRouter ignore stream=true and
	// answer with one complete application/json generation instead of an SSE stream
	// (found live by the Garage paint bay). Branching on the response content type turns
	// that answer into completed events, so callers see one uniform contract.
	public Flux<ImagesStreamEvent> imagesStream(ImagesRequest request) {
		return streamingPost(IMAGES, request, SSE).exchangeToFlux((response) -> {
			if (response.statusCode().isError()) {
				return streamingError(IMAGES, response, this.httpExceptionFactory).flatMapMany(Flux::error);
			}
			MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
			if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
				return response.bodyToFlux(STRING_SSE_TYPE).map(event -> new ImageStreamBody(event, null));
			}
			return readImagesResponse(response)
				.onErrorMap(DecodingException.class,
						ex -> new OpenRouterProtocolException(OpenRouterProtocolException.Kind.DECODE, IMAGES,
								"Invalid OpenRouter image response JSON"))
				.switchIfEmpty(Mono.error(new OpenRouterProtocolException(OpenRouterProtocolException.Kind.EMPTY_BODY,
						IMAGES, "Empty OpenRouter image response")))
				.map(images -> new ImageStreamBody(null, images))
				.flux();
		})
			// Include headers and error bodies, while letting SSE comments reset the
			// guard.
			.transform(this::applyTimeout)
			.switchOnFirst((signal, body) -> {
				ImageStreamBody first = signal.get();
				if (first != null && first.images() != null) {
					return body.concatMapIterable(item -> completedEvents(Objects.requireNonNull(item.images())));
				}
				return decodeStream(body.map(item -> Objects.requireNonNull(item.event())), ImagesStreamEvent.class,
						IMAGES);
			})
			.onErrorMap(error -> TransportFailures.stream(IMAGES, this.maxResponseBodyBytes, error));
	}

	private Mono<ImagesResponse> readImagesResponse(ClientResponse response) {
		if (!this.customObjectMapper) {
			return response.bodyToMono(ImagesResponse.class);
		}
		var decoder = new AbstractJacksonDecoder<>(this.objectMapper, MediaType.APPLICATION_JSON,
				new MediaType("application", "*+json"), MediaType.APPLICATION_NDJSON) {
		};
		decoder.setMaxInMemorySize(this.maxResponseBodyBytes);
		return ClientResponse.create(response.statusCode(), List.of(new DecoderHttpMessageReader<>(decoder)))
			.headers(headers -> headers.addAll(response.headers().asHttpHeaders()))
			.body(response.bodyToFlux(DataBuffer.class))
			.build()
			.bodyToMono(ImagesResponse.class);
	}

	private WebClient.RequestHeadersSpec<?> streamingPost(String uri, Object request, List<MediaType> accept) {
		var outgoing = this.webClient.post().uri(uri).accept(accept.toArray(MediaType[]::new));
		if (!this.customObjectMapper) {
			return outgoing.bodyValue(request);
		}
		return outgoing.body((output, context) -> Mono.fromCallable(() -> this.objectMapper.writeValueAsBytes(request))
			.flatMap(bytes -> {
				output.getHeaders().setContentLength(bytes.length);
				return output.writeWith(Mono.just(output.bufferFactory().wrap(bytes)));
			}));
	}

	private List<ImagesStreamEvent> completedEvents(ImagesResponse images) {
		OpenRouterImageResponseValidator.validate(images, this.diagnosticSanitizer);
		List<ImagesResponse.@Nullable ImageData> imageData = Objects.requireNonNull(images.data());
		List<ImagesStreamEvent> events = new ArrayList<>(imageData.size());
		for (int i = 0; i < imageData.size(); i++) {
			ImagesResponse.ImageData data = Objects.requireNonNull(imageData.get(i));
			boolean last = i == imageData.size() - 1;
			events.add(new ImagesStreamEvent(ImagesStreamEvent.COMPLETED, null, data.b64Json(), data.mediaType(),
					images.created(), last ? images.usage() : null, null, data.url()));
		}
		return events;
	}

	public Flux<ResponsesStreamEvent> responsesStream(ResponsesRequest request) {
		return responsesStream(request, Map.of());
	}

	/**
	 * Stream a response with HTTP headers for this request only.
	 * @param request the request
	 * @param headers the headers to add; a header that this client sets itself, such as
	 * Authorization, is rejected, and the values are redacted from error details
	 * @return the events
	 */
	public Flux<ResponsesStreamEvent> responsesStream(ResponsesRequest request, Map<String, String> headers) {
		return stream("/responses", request, ResponsesStreamEvent.class, headers);
	}

	private <T> Flux<T> stream(String uri, Object request, Class<T> eventType, Map<String, String> headers) {
		var outgoing = streamingPost(uri, request, SSE).headers(requestHeaders(headers));
		OpenRouterHttpExceptionFactory errors = this.httpExceptionFactory.redacting(headers.values());
		return outgoing.retrieve()
			.onStatus(HttpStatusCode::isError, response -> streamingError(uri, response, errors))
			.bodyToFlux(STRING_SSE_TYPE)
			.transform(this::applyTimeout)
			.transform(events -> decodeStream(events, eventType, uri))
			.onErrorMap(error -> TransportFailures.stream(uri, this.maxResponseBodyBytes, error));
	}

	private Mono<RuntimeException> streamingError(String uri, ClientResponse response,
			OpenRouterHttpExceptionFactory errors) {
		return Mono.defer(() -> {
			ByteArrayOutputStream prefix = new ByteArrayOutputStream();
			return response.bodyToFlux(DataBuffer.class).map(buffer -> {
				try {
					int remaining = this.maxErrorBodyBytes - prefix.size();
					int available = buffer.readableByteCount();
					byte[] bytes = new byte[Math.min(remaining, available)];
					buffer.read(bytes);
					prefix.writeBytes(bytes);
					return available > remaining;
				}
				finally {
					DataBufferUtils.release(buffer);
				}
			}).takeUntil(Boolean::booleanValue).last(false).map(exceeded -> {
				String body = prefix.toString(StandardCharsets.UTF_8);
				return exceeded ? errors.createErrorBodyLimit(uri, response.statusCode(), body, this.maxErrorBodyBytes,
						this.maxErrorBodyBytes + 1L, OpenRouterLimitExceededException.Limit.STREAMING_ERROR_BODY_BYTES)
						: errors.create(uri, response.statusCode(), response.headers().asHttpHeaders(), body);
			}).flatMap(StreamRetries::rejected);
		});
	}

	// Reactor's timeout operator caps the gap between elements, so a stalled stream fails
	// rather
	// than hanging forever. It is connector-agnostic, unlike a netty-specific
	// responseTimeout.
	// Applied to decoded SSE events before comment-only events are filtered, so
	// OpenRouter's
	// keep-alive comments (": OPENROUTER PROCESSING") still reset the timer -- otherwise
	// a
	// slow-but-healthy generation emitting only keep-alives would wrongly time out.
	private <T> Flux<T> applyTimeout(Flux<T> flux) {
		return this.timeout != null ? flux.timeout(this.timeout) : flux;
	}

	private Flux<String> eventData(Flux<ServerSentEvent<String>> events) {
		return events.<String>handle((event, sink) -> {
			String data = event.data();
			if (StringUtils.hasText(data)) {
				sink.next(data);
			}
		});
	}

	// Spring owns SSE framing. Only split the legacy coalesced-line format when
	// its first line is already a complete JSON object (or a raw data: line).
	private List<String> streamPayloads(String payload) {
		String data = payload.trim();
		int newline = data.indexOf('\n');
		if (newline < 0) {
			return List.of(data);
		}
		String firstLine = data.substring(0, newline).trim();
		if (!this.customObjectMapper && "{".equals(firstLine)) {
			return List.of(data);
		}
		if (!firstLine.startsWith("data:") && !"[DONE]".equals(firstLine)) {
			try {
				if (!this.objectMapper.reader()
					.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(firstLine)
					.isObject()) {
					return List.of(data);
				}
			}
			catch (JacksonException ex) {
				return List.of(data);
			}
		}
		return Arrays.asList(data.split("\n"));
	}

	private <T> Flux<T> decodeStream(Flux<ServerSentEvent<String>> events, Class<T> eventType, String uri) {
		return Flux.defer(() -> {
			AtomicBoolean protocolTerminated = new AtomicBoolean();
			ObjectReader reader = this.eventBinder.reader(eventType);
			return eventData(events).concatMapIterable(this::streamPayloads)
				.map(String::trim)
				.map(line -> line.startsWith("data:") ? line.substring(5).trim() : line)
				.filter(StringUtils::hasText)
				// Termination belongs to the whole subscription, not an individual
				// payload.
				.takeWhile(line -> {
					if ("[DONE]".equals(line)) {
						// Responses requires its own terminal event before transport EOF.
						if (eventType != ResponsesStreamEvent.class) {
							protocolTerminated.set(true);
						}
						return false;
					}
					return true;
				})
				.map(line -> readEvent(line, reader, eventType, uri))
				.doOnNext(event -> {
					if (isTerminalEvent(event) || event instanceof ChatCompletionChunk chunk && chunk.error() != null) {
						protocolTerminated.set(true);
					}
				})
				// Preserve final metadata and errors for the model-layer mappers.
				.takeUntil(this::isTerminalEvent)
				.concatWith(Flux.defer(() -> !protocolTerminated.get()
						? Flux.error(new OpenRouterTruncatedResponseException(
								eventType.getSimpleName() + " stream ended before protocol termination"))
						: Flux.empty()));
		});
	}

	private boolean isTerminalEvent(Object event) {
		if (event instanceof ResponsesStreamEvent response) {
			var type = response.type();
			return "response.completed".equals(type) || "response.failed".equals(type)
					|| "response.incomplete".equals(type) || "error".equals(type)
					|| type != null && type.endsWith(".error");
		}
		if (event instanceof ImagesStreamEvent image) {
			// A completed image is not the end of the request; image SSE ends at [DONE].
			return ImagesStreamEvent.ERROR.equals(image.type());
		}
		return false;
	}

	private <T> T readEvent(String line, @Nullable ObjectReader reader, Class<T> eventType, String uri) {
		try {
			T event = this.eventBinder.bind(line, reader, eventType, uri);
			if (event instanceof ResponsesStreamEvent response && "response.done".equals(response.type())) {
				throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.CONTRACT, uri,
						"Legacy response.done is unsupported; expected response.completed, "
								+ "response.incomplete, or response.failed");
			}
			if (event instanceof ChatCompletionChunk chunk) {
				StreamEventBinder.validateChunk(chunk, uri);
			}
			return event;
		}
		catch (JacksonException ex) {
			throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.DECODE, uri,
					"Failed to decode OpenRouter stream chunk", ex);
		}
	}

	private record ImageStreamBody(@Nullable ServerSentEvent<String> event, @Nullable ImagesResponse images) {
	}

	/**
	 * The speech endpoint answers with raw audio, not JSON or SSE, so it has its own
	 * exchange on the shared clients, limits and error classification.
	 */
	private final class SpeechExchange {

		byte[] call(SpeechRequest request) {
			var outgoing = OpenRouterApi.this.restClient.post()
				.uri(SPEECH)
				.accept(AUDIO_OR_ERROR.toArray(MediaType[]::new));
			if (OpenRouterApi.this.customObjectMapper) {
				outgoing.body(output -> OpenRouterApi.this.objectMapper.writeValue(output, request));
			}
			else {
				outgoing.body(request);
			}
			return TransportFailures.blocking(SPEECH, () -> outgoing.exchange((httpRequest, response) -> {
				BoundedBody body = checkedBody(SPEECH, response, OpenRouterApi.this.httpExceptionFactory);
				rejectJsonAudio(response.getHeaders().getContentType());
				return body.bytes();
			}));
		}

		Flux<byte[]> stream(SpeechRequest request) {
			// A server compressing the body may hold chunks back to fill its compression
			// window, which defeats streaming; encoded audio gains little from gzip
			// anyway.
			return streamingPost(SPEECH, request, AUDIO_OR_ERROR).header(HttpHeaders.ACCEPT_ENCODING, "identity")
				.exchangeToFlux(response -> {
					if (response.statusCode().isError()) {
						return streamingError(SPEECH, response, OpenRouterApi.this.httpExceptionFactory)
							.flatMapMany(Flux::error);
					}
					try {
						rejectJsonAudio(response.headers().contentType().orElse(null));
					}
					catch (OpenRouterProtocolException ex) {
						return response.releaseBody().then(Mono.<byte[]>error(ex)).flux();
					}
					return response.bodyToFlux(DataBuffer.class).map(SpeechExchange::bytes);
				})
				.transform(OpenRouterApi.this::applyTimeout)
				.filter(bytes -> bytes.length > 0)
				.switchIfEmpty(
						Flux.error(() -> new OpenRouterProtocolException(OpenRouterProtocolException.Kind.EMPTY_BODY,
								SPEECH, "Empty OpenRouter " + SPEECH + " response")))
				.onErrorMap(error -> TransportFailures.stream(SPEECH, OpenRouterApi.this.maxResponseBodyBytes, error));
		}

		private static byte[] bytes(DataBuffer buffer) {
			try {
				byte[] bytes = new byte[buffer.readableByteCount()];
				buffer.read(bytes);
				return bytes;
			}
			finally {
				DataBufferUtils.release(buffer);
			}
		}

		// A JSON body on a 2xx speech response is not audio; handing it to a player as
		// mp3 would hide the problem.
		private static void rejectJsonAudio(@Nullable MediaType contentType) {
			if (contentType != null && (MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
					|| contentType.getSubtype().endsWith("+json"))) {
				throw new OpenRouterProtocolException(OpenRouterProtocolException.Kind.CONTRACT, SPEECH,
						"OpenRouter " + SPEECH + " returned " + contentType + " instead of audio");
			}
		}

	}

	private record BoundedBody(List<byte[]> chunks, int size, boolean exceeded) {

		private static BoundedBody read(InputStream input, int limit) throws IOException {
			List<byte[]> chunks = new ArrayList<>();
			int size = 0;
			while (size <= limit) {
				int requested = Math.min(chunks.isEmpty() ? 1024 : 8192, limit + 1 - size);
				byte[] chunk = input.readNBytes(requested);
				if (chunk.length > 0) {
					chunks.add(chunk);
					size += chunk.length;
				}
				if (chunk.length < requested) {
					break;
				}
			}
			return new BoundedBody(chunks, size, size > limit);
		}

		private <T> T decode(ObjectMapper objectMapper, Class<T> responseType) throws IOException {
			return objectMapper.readValue(chunks.size() == 1 ? chunks.get(0) : bytes(), responseType);
		}

		private byte[] bytes() throws IOException {
			return new SequenceInputStream(
					Collections.enumeration(chunks.stream().map(ByteArrayInputStream::new).toList()))
				.readNBytes(exceeded ? size - 1 : size);
		}
	}

	public static final class Builder {

		private @Nullable String baseUrl = DEFAULT_BASE_URL;

		private @Nullable String apiKey;

		private @Nullable String httpReferer;

		private @Nullable String appTitle;

		private @Nullable String appCategories;

		private RestClient.@Nullable Builder restClientBuilder;

		private WebClient.@Nullable Builder webClientBuilder;

		private ObjectMapper objectMapper = new ObjectMapper();

		private boolean customObjectMapper;

		private @Nullable Duration timeout;

		private int maxResponseBodyBytes = DEFAULT_MAX_RESPONSE_BODY_BYTES;

		private int maxErrorBodyBytes = DEFAULT_MAX_ERROR_BODY_BYTES;

		private Builder() {
		}

		public Builder baseUrl(@Nullable String baseUrl) {
			this.baseUrl = baseUrl;
			return this;
		}

		public Builder apiKey(String apiKey) {
			this.apiKey = apiKey;
			return this;
		}

		public Builder httpReferer(@Nullable String httpReferer) {
			this.httpReferer = httpReferer;
			return this;
		}

		public Builder appTitle(@Nullable String appTitle) {
			this.appTitle = appTitle;
			return this;
		}

		public Builder appCategories(@Nullable String appCategories) {
			this.appCategories = appCategories;
			return this;
		}

		/**
		 * Set the {@code X-OpenRouter-Title} attribution header.
		 * @param applicationTitle the app title, or {@code null}
		 * @return this builder
		 * @deprecated since 0.2.0 for removal in 1.0.0; use {@link #appTitle(String)}, as
		 * in {@code spring.ai.openrouter.app.title}
		 */
		@Deprecated(since = "0.2.0", forRemoval = true)
		public Builder applicationTitle(@Nullable String applicationTitle) {
			return appTitle(applicationTitle);
		}

		/**
		 * Set the {@code X-OpenRouter-Categories} attribution header.
		 * @param applicationCategories comma-separated categories, or {@code null}
		 * @return this builder
		 * @deprecated since 0.2.0 for removal in 1.0.0; use
		 * {@link #appCategories(String)}, as in
		 * {@code spring.ai.openrouter.app.categories}
		 */
		@Deprecated(since = "0.2.0", forRemoval = true)
		public Builder applicationCategories(@Nullable String applicationCategories) {
			return appCategories(applicationCategories);
		}

		public Builder restClientBuilder(RestClient.@Nullable Builder restClientBuilder) {
			this.restClientBuilder = restClientBuilder;
			return this;
		}

		public Builder webClientBuilder(WebClient.@Nullable Builder webClientBuilder) {
			this.webClientBuilder = webClientBuilder;
			return this;
		}

		public Builder objectMapper(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			this.customObjectMapper = true;
			return this;
		}

		/**
		 * The timeout applied to the streaming {@link WebClient} as a non-destructive
		 * reactor operator that bounds the wait for the first event (including response
		 * headers and error bodies) and gaps between SSE events, including keep-alives.
		 * The blocking {@link RestClient}'s connect/read timeout is a transport concern
		 * configured on its request factory by the caller (the auto-configuration builds
		 * a timeout-aware factory onto a clone of the supplied builder).
		 */
		public Builder timeout(@Nullable Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Maximum bytes buffered for a blocking response, a streaming JSON response, or
		 * an individual SSE event. Does not limit the total stream size.
		 */
		public Builder maxResponseBodyBytes(int maxResponseBodyBytes) {
			this.maxResponseBodyBytes = maxResponseBodyBytes;
			return this;
		}

		/** Maximum bytes retained from a blocking or streaming HTTP error response. */
		public Builder maxErrorBodyBytes(int maxErrorBodyBytes) {
			this.maxErrorBodyBytes = maxErrorBodyBytes;
			return this;
		}

		public OpenRouterApi build() {
			return new OpenRouterApi(this);
		}

	}

}
