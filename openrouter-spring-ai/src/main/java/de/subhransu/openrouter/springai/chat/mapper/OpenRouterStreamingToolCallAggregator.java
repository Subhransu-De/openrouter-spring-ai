package de.subhransu.openrouter.springai.chat.mapper;

import java.util.Objects;
import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.ChatMessage;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.ChoiceError;
import de.subhransu.openrouter.springai.api.dto.ContentPart;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscription;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Merges streamed tool-call fragments in chat-completions mode. Providers split a tool
 * call's JSON arguments across many SSE chunks, correlated by the tool call's
 * {@code index}; emitting those fragments individually would hand consumers (and the
 * aggregating {@code ToolCallingAdvisor}) unusable partial tool calls. Chunks between the
 * first tool-call delta and the closing finish-reason chunk are buffered and merged per
 * choice index into one complete chunk; plain text chunks pass through one-by-one
 * untouched.
 *
 * @author Subhransu De
 */
public final class OpenRouterStreamingToolCallAggregator {

	public static final long DEFAULT_MAX_BYTES = 1024 * 1024;

	public static final int DEFAULT_MAX_CHUNKS = 1024;

	public static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(2);

	private static final String ENDPOINT = "/chat/completions";

	private final ObjectMapper objectMapper;

	private final long maxBytes;

	private final int maxChunks;

	private final Duration maxDuration;

	public OpenRouterStreamingToolCallAggregator() {
		this(new ObjectMapper(), DEFAULT_MAX_BYTES, DEFAULT_MAX_CHUNKS, DEFAULT_MAX_DURATION);
	}

	public OpenRouterStreamingToolCallAggregator(ObjectMapper objectMapper, long maxBytes, int maxChunks,
			Duration maxDuration) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.isTrue(maxBytes > 0, "Maximum streamed tool-call size must be greater than zero");
		Assert.isTrue(maxChunks > 0, "Maximum streamed tool-call chunk count must be greater than zero");
		Assert.notNull(maxDuration, "Maximum streamed tool-call duration must not be null");
		Assert.isTrue(!maxDuration.isZero() && !maxDuration.isNegative(),
				"Maximum streamed tool-call duration must be greater than zero");
		this.objectMapper = objectMapper;
		this.maxBytes = maxBytes;
		this.maxChunks = maxChunks;
		this.maxDuration = maxDuration;
	}

	public Flux<ChatCompletionChunk> aggregate(Flux<ChatCompletionChunk> chunks) {
		return Flux.create(sink -> subscribe(chunks, sink), FluxSink.OverflowStrategy.BUFFER);
	}

	private void subscribe(Flux<ChatCompletionChunk> chunks, FluxSink<ChatCompletionChunk> sink) {
		AtomicBoolean cancelled = new AtomicBoolean();
		AtomicLong pendingRequests = new AtomicLong();
		AtomicReference<@Nullable Subscription> upstream = new AtomicReference<>();
		Runnable drainRequests = () -> drainRequests(upstream, pendingRequests);
		Consumer<Throwable> fail = failure -> {
			if (cancelled.compareAndSet(false, true)) {
				cancel(upstream);
				sink.error(failure);
			}
		};
		AggregationState state = new AggregationState(fail);
		sink.onRequest(requested -> {
			pendingRequests.updateAndGet(current -> addSaturated(current, requested));
			drainRequests.run();
		});
		sink.onCancel(() -> {
			if (cancelled.compareAndSet(false, true)) {
				cancel(upstream);
				state.clear();
			}
		});

		chunks.subscribe(new CoreSubscriber<>() {

			@Override
			public Context currentContext() {
				return Context.of(sink.contextView());
			}

			@Override
			public void onSubscribe(Subscription subscription) {
				if (!upstream.compareAndSet(null, subscription) || cancelled.get()) {
					subscription.cancel();
					return;
				}
				drainRequests.run();
			}

			@Override
			public void onNext(ChatCompletionChunk chunk) {
				try {
					List<ChatCompletionChunk> ready = state.accept(chunk);
					ready.forEach(sink::next);
					if (ready.isEmpty() && !cancelled.get()) {
						pendingRequests.updateAndGet(current -> addSaturated(current, 1));
						drainRequests.run();
					}
				}
				catch (Throwable failure) {
					state.clear();
					fail.accept(failure);
				}
			}

			@Override
			public void onError(Throwable failure) {
				state.clear();
				fail.accept(failure);
			}

			@Override
			public void onComplete() {
				if (cancelled.compareAndSet(false, true)) {
					try {
						state.complete();
						sink.complete();
					}
					catch (OpenRouterTruncatedResponseException failure) {
						sink.error(failure);
					}
				}
			}

		});
	}

	private static void drainRequests(AtomicReference<@Nullable Subscription> upstream, AtomicLong pendingRequests) {
		Subscription subscription = upstream.get();
		if (subscription != null) {
			long requested = pendingRequests.getAndSet(0);
			if (requested > 0) {
				subscription.request(requested);
			}
		}
	}

	private static void cancel(AtomicReference<@Nullable Subscription> upstream) {
		Subscription subscription = upstream.get();
		if (subscription != null) {
			subscription.cancel();
		}
	}

	private boolean hasToolCallDelta(Choice choice) {
		var delta = choice.delta();
		return delta != null && !CollectionUtils.isEmpty(delta.toolCalls());
	}

	private long addSaturated(long left, long right) {
		return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
	}

	private OpenRouterLimitExceededException limit(OpenRouterLimitExceededException.Limit limit, long configured,
			long observed) {
		return new OpenRouterLimitExceededException(limit, configured, observed, ENDPOINT, null, null, null);
	}

	private ChatCompletionChunk withChoice(ChatCompletionChunk chunk, Choice choice) {
		return new ChatCompletionChunk(chunk.id(), chunk.object(), chunk.created(), chunk.model(), chunk.provider(),
				List.of(choice), chunk.usage(), chunk.error(), chunk.extensions());
	}

	private ChatCompletionChunk merge(List<ChatCompletionChunk> buffered) {
		if (buffered.size() == 1) {
			return buffered.get(0);
		}
		// A mid-stream error chunk must surface as-is so the response mapper can throw;
		// merging it away would disguise a failed stream as a clean tool call.
		for (ChatCompletionChunk chunk : buffered) {
			if (chunk.error() != null) {
				return chunk;
			}
		}
		ChatCompletionChunk first = buffered.get(0);
		ChatCompletionChunk last = buffered.get(buffered.size() - 1);
		Map<Integer, ChoiceBuffer> buffersByChoice = new TreeMap<>();
		Map<String, @Nullable Object> extensions = new LinkedHashMap<>();
		var usage = first.usage();
		for (ChatCompletionChunk chunk : buffered) {
			if (chunk.usage() != null) {
				usage = chunk.usage();
			}
			extensions.putAll(chunk.extensions());
			if (chunk.choices() == null) {
				continue;
			}
			for (Choice choice : items(chunk.choices(), "choice")) {
				buffersByChoice
					.computeIfAbsent(Objects.requireNonNullElse(choice.index(), 0), ignored -> new ChoiceBuffer())
					.append(choice);
			}
		}
		List<@Nullable Choice> choices = new ArrayList<>(buffersByChoice.size());
		buffersByChoice.values().forEach(buffer -> choices.add(buffer.finish()));
		return new ChatCompletionChunk(value(first.id(), last.id()), value(first.object(), last.object()),
				value(first.created(), last.created()), value(first.model(), last.model()),
				value(first.provider(), last.provider()), choices, usage, null, extensions);
	}

	private static @Nullable String mergeFunctionNames(@Nullable String earlier, @Nullable String later) {
		if (!StringUtils.hasText(earlier)) {
			return later;
		}
		// Names are complete identifiers, unlike incremental JSON arguments.
		if (StringUtils.hasText(later) && !earlier.equals(later)) {
			throw new OpenRouterProtocolException(
					"Conflicting streamed tool-call function names; fragmented names are not supported");
		}
		return earlier;
	}

	private static <T extends @Nullable Object> T value(T preferred, T fallback) {
		return preferred != null ? preferred : fallback;
	}

	private static <T> List<T> items(@Nullable List<? extends @Nullable T> values, String field) {
		return values != null && values.size() == 1 ? List.of(ResponseValues.required(values.get(0), field))
				: ResponseValues.items(values, field);
	}

	private static final class ChoiceBuffer {

		private final Map<String, @Nullable Object> values = new LinkedHashMap<>();

		private @Nullable Integer index;

		private @Nullable ChatMessage message;

		private @Nullable String finishReason;

		private @Nullable Object nativeFinishReason;

		private @Nullable ChoiceError error;

		private @Nullable Delta first;

		private boolean merged;

		private @Nullable String role;

		private @Nullable StringBuilder content;

		private @Nullable StringBuilder reasoning;

		private @Nullable StringBuilder refusal;

		private final ListBuffer<@Nullable ContentPart> images = new ListBuffer<@Nullable ContentPart>();

		private final ListBuffer<@Nullable JsonNode> reasoningDetails = new ListBuffer<@Nullable JsonNode>();

		private @Nullable Map<String, @Nullable Object> messageExtensions;

		private @Nullable List<@Nullable Object> annotations;

		private @Nullable List<@Nullable ToolCall> toolCalls;

		private @Nullable TreeMap<Integer, ToolCallFragments> fragmentsByCall;

		private int nextCallKey;

		private void append(Choice choice) {
			appendExtensions(choice.extensions());
			this.index = value(this.index, choice.index());
			this.message = value(this.message, choice.message());
			this.finishReason = value(choice.finishReason(), this.finishReason);
			this.nativeFinishReason = value(choice.nativeFinishReason(), this.nativeFinishReason);
			this.error = value(choice.error(), this.error);
			Delta delta = choice.delta();
			if (delta != null) {
				appendDelta(delta);
			}
		}

		private void appendDelta(Delta delta) {
			this.content = appendText(this.content, delta.content());
			this.reasoning = appendText(this.reasoning, delta.reasoning());
			this.refusal = appendText(this.refusal, delta.refusal());
			this.images.append(delta.images());
			this.reasoningDetails.append(delta.reasoningDetails());
			Delta earlier = this.first;
			if (earlier == null) {
				this.first = delta;
				this.role = delta.role();
				this.toolCalls = delta.toolCalls();
				return;
			}
			this.merged = true;
			this.role = value(this.role, delta.role());
			appendToolCalls(delta.toolCalls());
			appendMessageExtensions(earlier, delta.extensions());
		}

		private void appendToolCalls(@Nullable List<@Nullable ToolCall> later) {
			if (CollectionUtils.isEmpty(later)) {
				return;
			}
			TreeMap<Integer, ToolCallFragments> calls = this.fragmentsByCall;
			Integer lastKey = null;
			if (calls == null) {
				calls = new TreeMap<>();
				this.fragmentsByCall = calls;
				for (ToolCall toolCall : ResponseValues.items(this.toolCalls, "tool call")) {
					lastKey = correlate(calls, Objects.requireNonNullElse(toolCall.index(), this.nextCallKey),
							toolCall);
				}
			}
			else if (!calls.isEmpty()) {
				lastKey = calls.lastKey();
			}
			for (ToolCall fragment : items(later, "tool call fragment")) {
				// Fragments are correlated by the wire index. Without one, a fragment
				// carrying an id starts a new call; otherwise it continues the last call.
				Integer key = fragment.index();
				if (key == null) {
					key = fragment.id() != null || lastKey == null ? this.nextCallKey : lastKey;
				}
				lastKey = correlate(calls, key, fragment);
			}
		}

		private int correlate(Map<Integer, ToolCallFragments> calls, int key, ToolCall fragment) {
			this.nextCallKey = Math.max(this.nextCallKey, key + 1);
			ToolCallFragments call = calls.get(key);
			if (call == null) {
				calls.put(key, new ToolCallFragments(fragment));
			}
			else {
				call.append(fragment);
			}
			return key;
		}

		private void appendMessageExtensions(Delta first, Map<String, @Nullable Object> later) {
			if (later.isEmpty()) {
				return;
			}
			Map<String, @Nullable Object> extensions = this.messageExtensions;
			if (extensions == null) {
				extensions = new LinkedHashMap<>(first.extensions());
				this.messageExtensions = extensions;
			}
			Object previous = extensions.get("annotations");
			Object incoming = later.get("annotations");
			extensions.putAll(later);
			if (previous instanceof List<?> earlier && incoming instanceof List<?> additional) {
				List<@Nullable Object> combined = earlier == this.annotations ? this.annotations
						: new ArrayList<>(earlier);
				combined.addAll(additional);
				this.annotations = combined;
				extensions.put("annotations", combined);
			}
		}

		private static @Nullable StringBuilder appendText(@Nullable StringBuilder earlier, @Nullable String later) {
			if (later == null) {
				return earlier;
			}
			return earlier == null ? new StringBuilder(later) : earlier.append(later);
		}

		private Choice finish() {
			return new Choice(this.index, this.message, finishDelta(), this.finishReason, this.nativeFinishReason,
					this.error, this.values);
		}

		private @Nullable Delta finishDelta() {
			Delta delta = this.first;
			if (delta == null) {
				return null;
			}
			return new Delta(this.role, text(this.content), text(this.reasoning), finishToolCalls(), this.images.items,
					this.reasoningDetails.items, text(this.refusal), this.merged ? null : delta.audio(),
					this.messageExtensions != null ? this.messageExtensions : delta.extensions());
		}

		private @Nullable List<@Nullable ToolCall> finishToolCalls() {
			if (this.fragmentsByCall == null) {
				return this.toolCalls;
			}
			List<@Nullable ToolCall> calls = new ArrayList<>(this.fragmentsByCall.size());
			this.fragmentsByCall.values().forEach(call -> calls.add(call.finish()));
			return calls;
		}

		private static @Nullable String text(@Nullable StringBuilder value) {
			return value == null ? null : value.toString();
		}

		@SuppressWarnings("unchecked")
		private void appendExtensions(Map<String, @Nullable Object> later) {
			Object previous = this.values.get("logprobs");
			Object incoming = later.get("logprobs");
			this.values.putAll(later);
			if (previous instanceof Map<?, ?> first && incoming == null) {
				this.values.put("logprobs", first);
			}
			else if (incoming instanceof Map<?, ?> second) {
				Map<String, @Nullable Object> logprobs;
				if (previous instanceof Map<?, ?> first) {
					logprobs = (Map<String, @Nullable Object>) first;
					Object content = logprobs.get("content");
					Object refusal = logprobs.get("refusal");
					logprobs.putAll((Map<String, @Nullable Object>) second);
					appendEntries(logprobs, "content", content, second.get("content"));
					appendEntries(logprobs, "refusal", refusal, second.get("refusal"));
				}
				else {
					logprobs = new LinkedHashMap<>((Map<String, @Nullable Object>) second);
					appendEntries(logprobs, "content", null, second.get("content"));
					appendEntries(logprobs, "refusal", null, second.get("refusal"));
				}
				this.values.put("logprobs", logprobs);
			}
		}

		@SuppressWarnings("unchecked")
		private void appendEntries(Map<String, @Nullable Object> logprobs, String key, @Nullable Object previous,
				@Nullable Object incoming) {
			if (previous instanceof List<?> first) {
				List<@Nullable Object> entries = (List<@Nullable Object>) first;
				if (incoming instanceof List<?> second) {
					entries.addAll(second);
				}
				logprobs.put(key, entries);
			}
			else if (incoming instanceof List<?> second) {
				logprobs.put(key, new ArrayList<>(second));
			}
		}

	}

	private static final class ListBuffer<T extends @Nullable Object> {

		private @Nullable List<T> items;

		private boolean copied;

		private void append(@Nullable List<T> later) {
			if (later == null) {
				return;
			}
			List<T> earlier = this.items;
			if (earlier == null) {
				this.items = later;
				return;
			}
			if (!this.copied) {
				earlier = new ArrayList<>(earlier);
				this.items = earlier;
				this.copied = true;
			}
			earlier.addAll(later);
		}

	}

	private static final class ToolCallFragments {

		private final ToolCall first;

		private boolean merged;

		private @Nullable String id;

		private @Nullable String type;

		private @Nullable Integer index;

		private @Nullable FunctionCall function;

		private boolean functionMerged;

		private @Nullable String name;

		private @Nullable StringBuilder arguments;

		private @Nullable Map<String, @Nullable Object> extensions;

		private ToolCallFragments(ToolCall first) {
			this.first = first;
			this.id = first.id();
			this.type = first.type();
			this.index = first.index();
			appendFunction(first.function());
		}

		private void append(ToolCall later) {
			this.merged = true;
			this.id = value(this.id, later.id());
			this.type = value(this.type, later.type());
			this.index = value(this.index, later.index());
			appendFunction(later.function());
			if (!later.extensions().isEmpty()) {
				if (this.extensions == null) {
					this.extensions = new LinkedHashMap<>(this.first.extensions());
				}
				this.extensions.putAll(later.extensions());
			}
		}

		private void appendFunction(@Nullable FunctionCall later) {
			if (later == null) {
				return;
			}
			FunctionCall earlier = this.function;
			if (earlier == null) {
				this.function = later;
				this.name = later.name();
				return;
			}
			this.name = mergeFunctionNames(this.name, later.name());
			if (!this.functionMerged) {
				this.functionMerged = true;
				if (earlier.arguments() != null) {
					this.arguments = new StringBuilder(earlier.arguments());
				}
			}
			if (later.arguments() != null) {
				if (this.arguments == null) {
					this.arguments = new StringBuilder();
				}
				this.arguments.append(later.arguments());
			}
		}

		private ToolCall finish() {
			if (!this.merged) {
				return this.first;
			}
			FunctionCall merged = this.functionMerged
					? new FunctionCall(this.name, this.arguments != null ? this.arguments.toString() : null)
					: this.function;
			return new ToolCall(this.id, this.type, merged, this.index,
					this.extensions != null ? this.extensions : this.first.extensions());
		}

	}

	private final class AggregationState {

		private final Map<Integer, ToolCallBuffer> bufferedByChoice = new TreeMap<>();

		private final List<ChatCompletionChunk> bufferedChoiceLessChunks = new ArrayList<>();

		private int retainedChunks;

		private long retainedBytes;

		private long bufferedChoiceLessBytes;

		private final Consumer<Throwable> timeoutHandler;

		private AggregationState(Consumer<Throwable> timeoutHandler) {
			this.timeoutHandler = timeoutHandler;
		}

		private long serializedBytes(ChatCompletionChunk chunk) {
			try (ByteCountingOutputStream output = new ByteCountingOutputStream()) {
				objectMapper.writeValue(output, chunk);
				return output.count();
			}
			catch (JacksonException ex) {
				throw new IllegalStateException("Failed to measure an OpenRouter stream chunk", ex);
			}
		}

		private synchronized List<ChatCompletionChunk> accept(ChatCompletionChunk chunk) {
			if (chunk.error() != null) {
				clear();
				return List.of(chunk);
			}
			if (CollectionUtils.isEmpty(chunk.choices())) {
				if (!this.bufferedByChoice.isEmpty()) {
					long chunkBytes = serializedBytes(chunk);
					this.bufferedByChoice.values().forEach(buffer -> buffer.measure(chunkBytes));
					retain(chunkBytes);
					this.bufferedChoiceLessChunks.add(chunk);
					this.bufferedChoiceLessBytes = addSaturated(this.bufferedChoiceLessBytes, chunkBytes);
					return List.of();
				}
				return List.of(chunk);
			}

			List<ChatCompletionChunk> ready = new ArrayList<>();
			if (chunk.choices().size() == 1) {
				acceptChoice(chunk, ResponseValues.required(chunk.choices().get(0), "choice"), ready);
			}
			else {
				ResponseValues.items(chunk.choices(), "choice")
					.stream()
					.sorted(Comparator.comparingInt(this::choiceIndex))
					.forEach(choice -> acceptChoice(chunk, choice, ready));
			}
			if (this.bufferedByChoice.isEmpty()) {
				ready.addAll(this.bufferedChoiceLessChunks);
				release(this.bufferedChoiceLessChunks.size(), this.bufferedChoiceLessBytes);
				this.bufferedChoiceLessChunks.clear();
				this.bufferedChoiceLessBytes = 0;
			}
			return combine(ready);
		}

		private void acceptChoice(ChatCompletionChunk chunk, Choice choice, List<ChatCompletionChunk> ready) {
			int index = choiceIndex(choice);
			ToolCallBuffer buffered = this.bufferedByChoice.get(index);
			if (buffered == null && hasToolCallDelta(choice)) {
				buffered = new ToolCallBuffer(index);
				this.bufferedByChoice.put(index, buffered);
			}

			ChatCompletionChunk choiceChunk = withChoice(chunk, choice);
			if (buffered == null) {
				ready.add(choiceChunk);
			}
			else {
				var choiceDelta = choice.delta();
				if (choiceDelta != null && choiceDelta.audio() != null) {
					throw new OpenRouterProtocolException("Audio and tool calls in the same choice are unsupported");
				}
				long chunkBytes = serializedBytes(choiceChunk);
				buffered.add(choiceChunk, chunkBytes);
				retain(chunkBytes);
				if (choice.finishReason() != null || OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
					finishChoice(choice, buffered, index, ready);
				}
			}
		}

		private void finishChoice(Choice choice, ToolCallBuffer buffered, int index, List<ChatCompletionChunk> ready) {
			if (!OpenRouterChoiceErrorExceptionFactory.isFailure(choice)
					&& !FinishReasonMapper.isToolCallCompletion(choice.finishReason())) {
				throw new OpenRouterTruncatedResponseException(
						"Tool call choice ended without a tool-call completion reason");
			}
			this.bufferedByChoice.remove(index);
			buffered.close();
			ChatCompletionChunk merged = merge(buffered.chunks);
			if (!OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
				Choice completed = ResponseValues.items(merged.choices(), "completed choice").get(0);
				Delta delta = ResponseValues.required(completed.delta(), "completed tool-call delta");
				for (ToolCall toolCall : ResponseValues.items(delta.toolCalls(), "completed tool call")) {
					var function = toolCall.function();
					if (function == null || !StringUtils.hasText(function.name())) {
						throw new OpenRouterProtocolException("Completed streamed tool call has no function name");
					}
				}
			}
			ready.add(merged);
			release(buffered.chunks.size(), buffered.retainedBytes);
		}

		private synchronized void complete() {
			boolean incomplete = !this.bufferedByChoice.isEmpty();
			clear();
			if (incomplete) {
				throw new OpenRouterTruncatedResponseException("Stream ended with unfinished tool call choices");
			}
		}

		private List<ChatCompletionChunk> combine(List<ChatCompletionChunk> ready) {
			if (ready.isEmpty()) {
				return List.of();
			}
			return List.of(merge(ready));
		}

		private int choiceIndex(Choice choice) {
			return Objects.requireNonNullElse(choice.index(), 0);
		}

		private synchronized void clear() {
			this.bufferedByChoice.values().forEach(ToolCallBuffer::close);
			this.bufferedByChoice.clear();
			this.bufferedChoiceLessChunks.clear();
			this.retainedChunks = 0;
			this.retainedBytes = 0;
			this.bufferedChoiceLessBytes = 0;
		}

		private void retain(long chunkBytes) {
			int observedChunks = ++this.retainedChunks;
			if (observedChunks > maxChunks) {
				throw limit(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_CHUNKS, maxChunks,
						observedChunks);
			}
			long observedBytes = addSaturated(this.retainedBytes, chunkBytes);
			this.retainedBytes = observedBytes;
			if (observedBytes > maxBytes) {
				throw limit(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES, maxBytes, observedBytes);
			}
		}

		private void release(int chunks, long bytes) {
			this.retainedChunks = Math.max(0, this.retainedChunks - chunks);
			this.retainedBytes = Math.max(0, this.retainedBytes - bytes);
		}

		private synchronized void timeout(int index, ToolCallBuffer expected) {
			if (this.bufferedByChoice.get(index) != expected) {
				return;
			}
			long durationMillis = Math.max(1, maxDuration.toMillis());
			clear();
			this.timeoutHandler.accept(limit(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_DURATION,
					durationMillis, durationMillis));
		}

		private final class ToolCallBuffer {

			private final List<ChatCompletionChunk> chunks = new ArrayList<>();

			private int count;

			private long bytes;

			private long retainedBytes;

			private final Disposable timeout;

			private ToolCallBuffer(int index) {
				this.timeout = Mono.delay(maxDuration).subscribe(ignored -> AggregationState.this.timeout(index, this));
			}

			private void add(ChatCompletionChunk chunk, long chunkBytes) {
				measure(chunkBytes);
				this.chunks.add(chunk);
				this.retainedBytes = addSaturated(this.retainedBytes, chunkBytes);
			}

			private void measure(long chunkBytes) {
				int observedChunks = ++this.count;
				if (observedChunks > maxChunks) {
					throw limit(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_CHUNKS, maxChunks,
							observedChunks);
				}
				long observedBytes = addSaturated(this.bytes, chunkBytes);
				this.bytes = observedBytes;
				if (observedBytes > maxBytes) {
					throw limit(OpenRouterLimitExceededException.Limit.STREAMING_TOOL_CALL_BYTES, maxBytes,
							observedBytes);
				}
			}

			private void close() {
				this.timeout.dispose();
			}

		}

	}

	private static final class ByteCountingOutputStream extends OutputStream {

		private long count;

		@Override
		public void write(int value) {
			this.count = this.count == Long.MAX_VALUE ? Long.MAX_VALUE : this.count + 1;
		}

		@Override
		public void write(byte[] bytes, int offset, int length) {
			this.count = Long.MAX_VALUE - this.count < length ? Long.MAX_VALUE : this.count + length;
		}

		@Override
		public void close() {
		}

		long count() {
			return this.count;
		}

	}

}
