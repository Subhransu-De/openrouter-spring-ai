package de.subhransu.openrouter.springai.internal;

import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.util.backoff.BackOffExecution;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

/**
 * Internal, subscription-scoped retries for definite HTTP stream rejections.
 *
 * @author Subhransu De
 */
public final class StreamRetries {

	private StreamRetries() {
	}

	/**
	 * Mark only errors decoded from actual HTTP 429 responses. Reactor context carries
	 * identity rather than wrapping the exception, preserving the public error contract
	 * and excluding in-band failures with a derived 429 status.
	 */
	public static Mono<RuntimeException> rejected(RuntimeException error) {
		return Mono.deferContextual(context -> {
			if (error instanceof OpenRouterTransientApiException http && http.getStatusCode() != null
					&& http.getStatusCode().value() == 429 && http.getCategory() == OpenRouterErrorCategory.RATE_LIMIT
					&& context.hasKey(State.class)) {
				context.<State>get(State.class).rejection.set(error);
			}
			return Mono.just(error);
		});
	}

	/**
	 * Reuse the template's policy and backoff without blocking a reactive thread.
	 * Blocking RetryListener callbacks are not invoked.
	 */
	public static <T> Flux<T> stream(Flux<T> source, RetryTemplate template) {
		return Flux.defer(() -> {
			State state = new State();
			RetryPolicy policy = template.getRetryPolicy();
			BackOffExecution backOff = policy.getBackOff().start();
			long started = Schedulers.parallel().now(TimeUnit.NANOSECONDS);
			return source.doOnNext(value -> state.emitted = true)
				.retryWhen(Retry.from(signals -> signals.concatMap(signal -> {
					Throwable failure = signal.failure();
					if (state.emitted || state.rejection.getAndSet(null) != failure || !policy.shouldRetry(failure)) {
						return Mono.error(failure);
					}
					return retryDelay(failure, policy, backOff, started);
				})))
				.contextWrite(context -> context.put(State.class, state));
		});
	}

	private static Mono<Long> retryDelay(Throwable failure, RetryPolicy policy, BackOffExecution backOff,
			long started) {
		long delay = backOff.nextBackOff();
		if (delay == BackOffExecution.STOP) {
			return Mono.error(failure);
		}
		Duration remaining = remainingTimeout(policy, started);
		OpenRouterHttpException http = (OpenRouterHttpException) failure;
		delay = Retries.effectiveBackOffMillis(delay,
				http.getRetryAfter() != null ? http.getRetryAfter().delay() : null, remaining);
		if (delay == BackOffExecution.STOP || remaining != null && remaining.compareTo(Duration.ofMillis(delay)) <= 0) {
			return Mono.error(failure);
		}
		return Mono.delay(Duration.ofMillis(delay)).flatMap(tick -> {
			Duration left = remainingTimeout(policy, started);
			return left != null && left.isZero() ? Mono.error(failure) : Mono.just(tick);
		});
	}

	private static @Nullable Duration remainingTimeout(RetryPolicy policy, long started) {
		Duration timeout = policy.getTimeout();
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			return null;
		}
		Duration remaining = timeout.minusNanos(Math.max(0, Schedulers.parallel().now(TimeUnit.NANOSECONDS) - started));
		return remaining.isNegative() ? Duration.ZERO : remaining;
	}

	private static final class State {

		private final AtomicReference<@Nullable Throwable> rejection = new AtomicReference<>();

		private volatile boolean emitted;

	}

}
