package de.subhransu.openrouter.springai.chat.mapper;

import de.subhransu.openrouter.springai.api.dto.ChatCompletionChunk;
import de.subhransu.openrouter.springai.api.dto.Choice;
import de.subhransu.openrouter.springai.api.dto.Delta;
import de.subhransu.openrouter.springai.api.dto.FunctionCall;
import de.subhransu.openrouter.springai.api.dto.ToolCall;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
		AtomicReference<Subscription> upstream = new AtomicReference<>();
		Runnable drainRequests = () -> {
			Subscription subscription = upstream.get();
			if (subscription != null) {
				long requested = pendingRequests.getAndSet(0);
				if (requested > 0) {
					subscription.request(requested);
				}
			}
		};
		Consumer<Throwable> fail = failure -> {
			if (cancelled.compareAndSet(false, true)) {
				Subscription subscription = upstream.get();
				if (subscription != null) {
					subscription.cancel();
				}
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
				Subscription subscription = upstream.get();
				if (subscription != null) {
					subscription.cancel();
				}
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

	private boolean hasToolCallDelta(Choice choice) {
		return choice.delta() != null && !CollectionUtils.isEmpty(choice.delta().toolCalls());
	}

	private long serializedBytes(ChatCompletionChunk chunk) {
		try (ByteCountingOutputStream output = new ByteCountingOutputStream()) {
			this.objectMapper.writeValue(output, chunk);
			return output.count();
		}
		catch (JacksonException ex) {
			throw new IllegalStateException("Failed to measure an OpenRouter stream chunk", ex);
		}
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
		Map<Integer, Choice> choices = new TreeMap<>();
		var usage = first.usage();
		for (ChatCompletionChunk chunk : buffered) {
			if (chunk.usage() != null) {
				usage = chunk.usage();
			}
			if (chunk.choices() == null) {
				continue;
			}
			for (Choice choice : chunk.choices()) {
				Integer index = choice.index() != null ? choice.index() : 0;
				choices.merge(index, choice, this::mergeChoices);
			}
		}
		return new ChatCompletionChunk(value(first.id(), last.id()), value(first.object(), last.object()),
				value(first.created(), last.created()), value(first.model(), last.model()),
				value(first.provider(), last.provider()), new ArrayList<>(choices.values()), usage, null,
				buffered.stream().map(ChatCompletionChunk::extensions).reduce(Map.of(), ExtensionMetadata::merge));
	}

	private Choice mergeChoices(Choice earlier, Choice later) {
		return new Choice(value(earlier.index(), later.index()), value(earlier.message(), later.message()),
				mergeDeltas(earlier.delta(), later.delta()), value(later.finishReason(), earlier.finishReason()),
				value(later.nativeFinishReason(), earlier.nativeFinishReason()), value(later.error(), earlier.error()),
				ExtensionMetadata.merge(earlier.extensions(), later.extensions()));
	}

	private Delta mergeDeltas(Delta earlier, Delta later) {
		if (earlier == null) {
			return later;
		}
		if (later == null) {
			return earlier;
		}
		return new Delta(value(earlier.role(), later.role()), concat(earlier.content(), later.content()),
				concat(earlier.reasoning(), later.reasoning()), mergeToolCalls(earlier.toolCalls(), later.toolCalls()),
				ReasoningMetadata.concat(earlier.images(), later.images()),
				ReasoningMetadata.concat(earlier.reasoningDetails(), later.reasoningDetails()),
				concat(earlier.refusal(), later.refusal()),
				ExtensionMetadata.mergeMessage(earlier.extensions(), later.extensions()));
	}

	private List<ToolCall> mergeToolCalls(List<ToolCall> earlier, List<ToolCall> later) {
		if (CollectionUtils.isEmpty(later)) {
			return earlier;
		}
		Map<Integer, ToolCall> merged = new TreeMap<>();
		int nextSyntheticKey = 0;
		Integer lastKey = null;
		if (earlier != null) {
			for (ToolCall toolCall : earlier) {
				int key = toolCall.index() != null ? toolCall.index() : nextSyntheticKey;
				nextSyntheticKey = Math.max(nextSyntheticKey, key + 1);
				merged.merge(key, toolCall, this::mergeToolCallFragments);
				lastKey = key;
			}
		}
		for (ToolCall fragment : later) {
			// Fragments are correlated by the wire index. Without one, a fragment
			// carrying an id starts a new call; otherwise it continues the last call.
			Integer key = fragment.index();
			if (key == null) {
				key = fragment.id() != null || lastKey == null ? nextSyntheticKey : lastKey;
			}
			nextSyntheticKey = Math.max(nextSyntheticKey, key + 1);
			merged.merge(key, fragment, this::mergeToolCallFragments);
			lastKey = key;
		}
		return new ArrayList<>(merged.values());
	}

	private ToolCall mergeToolCallFragments(ToolCall earlier, ToolCall later) {
		FunctionCall earlierFunction = earlier.function();
		FunctionCall laterFunction = later.function();
		FunctionCall function;
		if (earlierFunction == null) {
			function = laterFunction;
		}
		else if (laterFunction == null) {
			function = earlierFunction;
		}
		else {
			function = new FunctionCall(mergeFunctionNames(earlierFunction.name(), laterFunction.name()),
					concat(earlierFunction.arguments(), laterFunction.arguments()));
		}
		return new ToolCall(value(earlier.id(), later.id()), value(earlier.type(), later.type()), function,
				value(earlier.index(), later.index()),
				ExtensionMetadata.merge(earlier.extensions(), later.extensions()));
	}

	private String mergeFunctionNames(String earlier, String later) {
		if (!StringUtils.hasText(earlier)) {
			return later;
		}
		// Names are complete identifiers, unlike incremental JSON arguments.
		Assert.state(!StringUtils.hasText(later) || earlier.equals(later),
				"Conflicting streamed tool-call function names; fragmented names are not supported");
		return earlier;
	}

	private static <T> T value(T preferred, T fallback) {
		return preferred != null ? preferred : fallback;
	}

	private static String concat(String earlier, String later) {
		if (earlier == null) {
			return later;
		}
		return later == null ? earlier : earlier + later;
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
			chunk.choices().stream().sorted(Comparator.comparingInt(this::choiceIndex)).forEach(choice -> {
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
					long chunkBytes = serializedBytes(choiceChunk);
					buffered.add(choiceChunk, chunkBytes);
					retain(chunkBytes);
					if (choice.finishReason() != null || OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
						if (!OpenRouterChoiceErrorExceptionFactory.isFailure(choice)
								&& !FinishReasonMapper.isToolCallCompletion(choice.finishReason())) {
							throw new OpenRouterTruncatedResponseException(
									"Tool call choice ended without a tool-call completion reason");
						}
						this.bufferedByChoice.remove(index);
						buffered.close();
						ChatCompletionChunk merged = merge(buffered.chunks);
						if (!OpenRouterChoiceErrorExceptionFactory.isFailure(choice)) {
							for (ToolCall toolCall : merged.choices().get(0).delta().toolCalls()) {
								Assert.state(
										toolCall.function() != null && StringUtils.hasText(toolCall.function().name()),
										"Completed streamed tool call has no function name");
							}
						}
						ready.add(merged);
						release(buffered.chunks.size(), buffered.retainedBytes);
					}
				}
			});
			if (this.bufferedByChoice.isEmpty()) {
				ready.addAll(this.bufferedChoiceLessChunks);
				release(this.bufferedChoiceLessChunks.size(), this.bufferedChoiceLessBytes);
				this.bufferedChoiceLessChunks.clear();
				this.bufferedChoiceLessBytes = 0;
			}
			return combine(ready);
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
			return choice.index() != null ? choice.index() : 0;
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
