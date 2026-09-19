package de.subhransu.openrouter.springai.api;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionRequest;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionResponse;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsRequest;
import de.subhransu.openrouter.springai.api.dto.EmbeddingsResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesRequest;
import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.ResponsesRequest;
import de.subhransu.openrouter.springai.api.dto.ResponsesResult;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

public class OpenRouterApi {

	public static final String DEFAULT_BASE_URL = "https://openrouter.ai/api/v1";

	public static final int DEFAULT_MAX_RESPONSE_BODY_BYTES = 64 * 1024 * 1024;

	public static final int DEFAULT_MAX_ERROR_BODY_BYTES = 64 * 1024;

	private static final ParameterizedTypeReference<ServerSentEvent<String>> STRING_SSE_TYPE = new ParameterizedTypeReference<>() {
	};

	private final RestClient restClient;

	private final WebClient webClient;

	private final ObjectMapper objectMapper;

	private final OpenRouterHttpExceptionFactory httpExceptionFactory;

	private final @Nullable Duration timeout;

	private final int maxResponseBodyBytes;

	private final int maxErrorBodyBytes;

	private OpenRouterApi(Builder builder) {
		Assert.hasText(builder.apiKey, "OpenRouter API key must not be empty");
		Assert.notNull(builder.objectMapper, "ObjectMapper must not be null");

		String baseUrl = StringUtils.hasText(builder.baseUrl) ? builder.baseUrl : DEFAULT_BASE_URL;
		this.objectMapper = builder.objectMapper;
		this.httpExceptionFactory = new OpenRouterHttpExceptionFactory(this.objectMapper, builder.apiKey);
		this.timeout = builder.timeout;
		Assert.isTrue(builder.maxResponseBodyBytes > 0, "Maximum response body size must be greater than zero");
		Assert.isTrue(builder.maxErrorBodyBytes > 0, "Maximum error body size must be greater than zero");
		Assert.isTrue(builder.maxResponseBodyBytes < Integer.MAX_VALUE,
				"Maximum response body size must be less than Integer.MAX_VALUE");
		Assert.isTrue(builder.maxErrorBodyBytes < Integer.MAX_VALUE,
				"Maximum error body size must be less than Integer.MAX_VALUE");
		this.maxResponseBodyBytes = builder.maxResponseBodyBytes;
		this.maxErrorBodyBytes = builder.maxErrorBodyBytes;

		// The RestClient's connect/read timeout is a transport concern carried by its
		// request factory.
		// The factory varies by classpath (Apache/Jetty/Reactor/JDK), so the
		// auto-configuration builds
		// a timeout-configured factory onto the supplied builder rather than this
		// transport-agnostic
		// core. The timeout field here drives only the streaming WebClient guard (see
		// applyTimeout).
		RestClient.Builder restClientBuilder = builder.restClientBuilder != null ? builder.restClientBuilder.clone()
				: RestClient.builder();
		this.restClient = restClientBuilder.baseUrl(baseUrl)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + builder.apiKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
			.requestInterceptor(new OpenRouterAttributionInterceptor(builder.httpReferer, builder.applicationTitle,
					builder.applicationCategories))
			.build();

		WebClient.Builder webClientBuilder = builder.webClientBuilder != null ? builder.webClientBuilder.clone()
				: WebClient.builder();
		this.webClient = webClientBuilder.baseUrl(baseUrl)
			.codecs((codecs) -> codecs.defaultCodecs().maxInMemorySize(this.maxResponseBodyBytes))
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + builder.apiKey)
			.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
			.defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_EVENT_STREAM_VALUE)
			.defaultHeaders(headers -> {
				addIfPresent(headers, "HTTP-Referer", builder.httpReferer);
				addIfPresent(headers, "X-OpenRouter-Title", builder.applicationTitle);
				addIfPresent(headers, "X-OpenRouter-Categories", builder.applicationCategories);
			})
			.build();
	}

	private static void addIfPresent(HttpHeaders headers, String name, @Nullable String value) {
		if (StringUtils.hasText(value)) {
			headers.set(name, value);
		}
	}

	public static Builder builder() {
		return new Builder();
	}

	public ChatCompletionResponse chatCompletion(ChatCompletionRequest request) {
		return blockingPost("/chat/completions", request, ChatCompletionResponse.class);
	}

	public ResponsesResult responses(ResponsesRequest request) {
		return blockingPost("/responses", request, ResponsesResult.class);
	}

	public EmbeddingsResponse embeddings(EmbeddingsRequest request) {
		return blockingPost("/embeddings", request, EmbeddingsResponse.class);
	}

	public ImagesResponse images(ImagesRequest request) {
		return blockingPost("/images", request, ImagesResponse.class);
	}

	private <T> T blockingPost(String uri, Object request, Class<T> responseType) {
		return this.restClient.post()
			.uri(uri)
			.body(request)
			.exchange((httpRequest, response) -> readBlockingResponse(uri, response, responseType));
	}

	private <T> T readBlockingResponse(String uri, ClientHttpResponse response, Class<T> responseType)
			throws IOException {
		HttpStatusCode statusCode = response.getStatusCode();
		int limit = statusCode.isError() ? this.maxErrorBodyBytes : this.maxResponseBodyBytes;
		BoundedBody body = readBounded(response.getBody(), limit);
		if (body.exceeded()) {
			if (statusCode.isError()) {
				String excerpt = new String(body.bytes(), StandardCharsets.UTF_8);
				throw this.httpExceptionFactory.createErrorBodyLimit(uri, statusCode, excerpt, limit, limit + 1L);
			}
			throw new OpenRouterLimitExceededException(
					OpenRouterLimitExceededException.Limit.BLOCKING_RESPONSE_BODY_BYTES, limit, limit + 1L, uri,
					statusCode, null, null);
		}
		if (statusCode.isError()) {
			String errorBody = new String(body.bytes(), StandardCharsets.UTF_8);
			throw this.httpExceptionFactory.create(uri, statusCode, response.getHeaders(), errorBody);
		}
		if (body.bytes().length == 0) {
			throw new OpenRouterProtocolException("Empty OpenRouter " + uri + " response");
		}
		try {
			T decoded = this.objectMapper.readValue(body.bytes(), responseType);
			if (decoded == null) {
				throw new OpenRouterProtocolException("Null OpenRouter " + uri + " response");
			}
			return decoded;
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to decode OpenRouter " + uri + " response", ex);
		}
	}

	private BoundedBody readBounded(InputStream input, int limit) throws IOException {
		byte[] bytes = input.readNBytes(limit + 1);
		return bytes.length > limit ? new BoundedBody(Arrays.copyOf(bytes, limit), true)
				: new BoundedBody(bytes, false);
	}

	public Flux<ChatCompletionChunk> chatCompletionStream(ChatCompletionRequest request) {
		return stream("/chat/completions", request, ChatCompletionChunk.class);
	}

	// Providers without native image streaming make OpenRouter ignore stream=true and
	// answer with one complete application/json generation instead of an SSE stream
	// (found live by the Garage paint bay). Branching on the response content type turns
	// that answer into completed events, so callers see one uniform contract.
	public Flux<ImagesStreamEvent> imagesStream(ImagesRequest request) {
		return this.webClient.post()
			.uri("/images")
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.exchangeToFlux((response) -> {
				if (response.statusCode().isError()) {
					return streamingError("/images", response).flatMapMany(Flux::error);
				}
				MediaType contentType = response.headers().contentType().orElse(MediaType.APPLICATION_JSON);
				if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
					return response.bodyToFlux(STRING_SSE_TYPE).map(event -> new ImageStreamBody(event, null));
				}
				return response.bodyToMono(ImagesResponse.class)
					.map(images -> new ImageStreamBody(null, images))
					.flux();
			})
			// Include headers and error bodies, while letting SSE comments reset the
			// guard.
			.transform(this::applyTimeout)
			.switchOnFirst((signal, body) -> {
				if (signal.hasValue() && signal.get().images() != null) {
					return body.concatMapIterable(item -> completedEvents(item.images()));
				}
				return decodeStream(body.map(ImageStreamBody::event), ImagesStreamEvent.class);
			});
	}

	private List<ImagesStreamEvent> completedEvents(ImagesResponse images) {
		if (images.data() == null || images.data().isEmpty()) {
			return List.of();
		}
		List<ImagesStreamEvent> events = new ArrayList<>(images.data().size());
		for (int i = 0; i < images.data().size(); i++) {
			ImagesResponse.ImageData data = images.data().get(i);
			boolean last = i == images.data().size() - 1;
			events.add(new ImagesStreamEvent(ImagesStreamEvent.COMPLETED, null, data.b64Json(), data.mediaType(),
					images.created(), last ? images.usage() : null, null));
		}
		return events;
	}

	public Flux<ResponsesStreamEvent> responsesStream(ResponsesRequest request) {
		return stream("/responses", request, ResponsesStreamEvent.class);
	}

	private <T> Flux<T> stream(String uri, Object request, Class<T> eventType) {
		return this.webClient.post()
			.uri(uri)
			.accept(MediaType.TEXT_EVENT_STREAM)
			.bodyValue(request)
			.retrieve()
			.onStatus(HttpStatusCode::isError, response -> streamingError(uri, response))
			.bodyToFlux(STRING_SSE_TYPE)
			.transform(this::applyTimeout)
			.transform(events -> decodeStream(events, eventType));
	}

	private Mono<RuntimeException> streamingError(String uri, ClientResponse response) {
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
				return exceeded
						? this.httpExceptionFactory.createErrorBodyLimit(uri, response.statusCode(), body,
								this.maxErrorBodyBytes, this.maxErrorBodyBytes + 1L,
								OpenRouterLimitExceededException.Limit.STREAMING_ERROR_BODY_BYTES)
						: this.httpExceptionFactory.create(uri, response.statusCode(),
								response.headers().asHttpHeaders(), body);
			});
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

	private <T> Flux<T> decodeStream(Flux<ServerSentEvent<String>> events, Class<T> eventType) {
		return Flux.defer(() -> {
			AtomicBoolean protocolTerminated = new AtomicBoolean();
			return eventData(events).concatMapIterable(this::streamPayloads)
				.map(String::trim)
				.filter(line -> line.startsWith("data:") || line.startsWith("{") || "[DONE]".equals(line))
				.map(line -> line.startsWith("data:") ? line.substring(5).trim() : line)
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
				.map(line -> readEvent(line, eventType))
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
			return "response.completed".equals(response.type()) || "response.failed".equals(response.type())
					|| "response.incomplete".equals(response.type()) || "error".equals(response.type())
					|| response.type() != null && response.type().endsWith(".error");
		}
		if (event instanceof ImagesStreamEvent image) {
			// A completed image is not the end of the request; image SSE ends at [DONE].
			return ImagesStreamEvent.ERROR_EVENT.equals(image.type());
		}
		return false;
	}

	private <T> T readEvent(String line, Class<T> eventType) {
		try {
			T event = this.objectMapper.readValue(line, eventType);
			if (event instanceof ResponsesStreamEvent response && "response.done".equals(response.type())) {
				throw new OpenRouterProtocolException(
						"Legacy response.done is unsupported; expected response.completed, "
								+ "response.incomplete, or response.failed");
			}
			if (event instanceof ChatCompletionChunk chunk && chunk.error() == null
					&& ((CollectionUtils.isEmpty(chunk.choices()) && chunk.usage() == null)
							|| (chunk.choices() != null && chunk.choices().stream().anyMatch(Objects::isNull)))) {
				throw new OpenRouterProtocolException("OpenRouter chat chunk requires non-null choices or usage");
			}
			return event;
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to decode OpenRouter stream chunk", ex);
		}
	}

	private record ImageStreamBody(ServerSentEvent<String> event, ImagesResponse images) {
	}

	private record BoundedBody(byte[] bytes, boolean exceeded) {
	}

	public static final class Builder {

		private @Nullable String baseUrl = DEFAULT_BASE_URL;

		private @Nullable String apiKey;

		private @Nullable String httpReferer;

		private @Nullable String applicationTitle;

		private @Nullable String applicationCategories;

		private RestClient.@Nullable Builder restClientBuilder;

		private WebClient.@Nullable Builder webClientBuilder;

		private ObjectMapper objectMapper = new ObjectMapper();

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

		public Builder applicationTitle(@Nullable String applicationTitle) {
			this.applicationTitle = applicationTitle;
			return this;
		}

		public Builder applicationCategories(@Nullable String applicationCategories) {
			this.applicationCategories = applicationCategories;
			return this;
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
			return this;
		}

		/**
		 * The timeout applied to the streaming {@link WebClient} as a non-destructive
		 * reactor operator that bounds the wait for the first event (including response
		 * headers and error bodies) and gaps between SSE events, including keep-alives.
		 * The blocking {@link RestClient}'s connect/read timeout is a transport concern
		 * configured on its request factory by the caller (the auto-configuration builds
		 * a timeout-aware factory onto the supplied builder).
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
