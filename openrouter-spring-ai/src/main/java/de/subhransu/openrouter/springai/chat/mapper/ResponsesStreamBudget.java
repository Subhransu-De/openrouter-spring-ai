package de.subhransu.openrouter.springai.chat.mapper;

import org.springframework.util.CollectionUtils;
import de.subhransu.openrouter.springai.api.dto.ResponsesStreamEvent;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException;
import de.subhransu.openrouter.springai.errors.OpenRouterLimitExceededException.Limit;
import java.io.OutputStream;
import java.time.Duration;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.ObjectMapper;

// Cumulative admission budget, independent of individual SSE event size.
final class ResponsesStreamBudget {

	private final ObjectMapper objectMapper;

	private final long maxBytes;

	private final int maxChunks;

	private final Duration maxDuration;

	ResponsesStreamBudget(ObjectMapper objectMapper, long maxBytes, int maxChunks, Duration maxDuration) {
		Assert.notNull(objectMapper, "ObjectMapper must not be null");
		Assert.isTrue(maxBytes > 0, "Maximum Responses state size must be greater than zero");
		Assert.isTrue(maxChunks > 0, "Maximum Responses state chunk count must be greater than zero");
		Assert.notNull(maxDuration, "Maximum Responses state duration must not be null");
		Assert.isTrue(!maxDuration.isZero() && !maxDuration.isNegative(),
				"Maximum Responses state duration must be greater than zero");
		this.objectMapper = objectMapper;
		this.maxBytes = maxBytes;
		this.maxChunks = maxChunks;
		this.maxDuration = maxDuration;
	}

	Flux<ResponsesStreamEvent> apply(Flux<ResponsesStreamEvent> events) {
		return Flux.defer(() -> {
			Counter counter = new Counter();
			Sinks.One<Boolean> started = Sinks.one();
			Mono<Long> deadline = started.asMono()
				.flatMap(ignored -> Mono.delay(this.maxDuration))
				.doOnNext(ignored -> counter.expired = true);
			return events.doOnNext(event -> {
				if (counter.active || retainsState(event)) {
					if (!counter.active) {
						counter.active = true;
						started.tryEmitValue(true);
					}
					counter.accept(event);
				}
			})
				.takeUntil(ResponsesStreamBudget::terminal)
				.takeUntilOther(deadline)
				.concatWith(Mono.defer(() -> counter.expired ? Mono.error(limit(Limit.RESPONSES_STATE_DURATION,
						Math.max(1, this.maxDuration.toMillis()), Math.max(1, this.maxDuration.toMillis())))
						: Mono.empty()));
		});
	}

	private static boolean retainsState(ResponsesStreamEvent event) {
		var response = event.response();
		String type = event.type();
		return event.item() != null || response != null && !CollectionUtils.isEmpty(response.output())
				|| type != null && (type.startsWith("response.function_call_arguments.")
						|| type.startsWith("response.output_text.") || type.startsWith("response.reasoning")
						|| type.startsWith("response.refusal."));
	}

	private static boolean terminal(ResponsesStreamEvent event) {
		var type = event.type();
		return "response.completed".equals(type) || "response.incomplete".equals(type) || "response.failed".equals(type)
				|| "error".equals(type) || type != null && type.endsWith(".error");
	}

	private static OpenRouterLimitExceededException limit(Limit limit, long configured, long observed) {
		return new OpenRouterLimitExceededException(limit, configured, observed, "/responses", null, null, null);
	}

	private final class Counter extends OutputStream {

		private boolean active;

		private volatile boolean expired;

		private long bytes;

		private long chunks;

		private long items;

		private void accept(ResponsesStreamEvent event) {
			var response = event.response();
			if (++this.chunks > maxChunks) {
				throw limit(Limit.RESPONSES_STATE_CHUNKS, maxChunks, this.chunks);
			}
			// Done items are retained in replay order. The terminal snapshot replaces
			// them, so check its cardinality separately rather than counting it twice.
			if ("response.output_item.done".equals(event.type()) && event.item() != null) {
				this.items++;
			}
			var output = response != null ? response.output() : null;
			long observed = output != null ? Math.max(this.items, output.size()) : this.items;
			if (observed > maxChunks) {
				throw limit(Limit.RESPONSES_STATE_ITEMS, maxChunks, observed);
			}
			// Count incoming JSON once, including duplicate snapshots. Never serialize
			// growing accumulated state or allocate another full event-sized byte array.
			objectMapper.writeValue(this, event);
			if (this.bytes > maxBytes) {
				throw limit(Limit.RESPONSES_STATE_BYTES, maxBytes, this.bytes);
			}
		}

		@Override
		public void write(int value) {
			add(1);
		}

		@Override
		public void write(byte[] value, int offset, int length) {
			add(length);
		}

		private void add(int length) {
			this.bytes = Long.MAX_VALUE - this.bytes < length ? Long.MAX_VALUE : this.bytes + length;
		}

	}

}
