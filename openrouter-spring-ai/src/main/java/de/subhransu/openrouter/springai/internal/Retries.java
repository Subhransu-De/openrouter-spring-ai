package de.subhransu.openrouter.springai.internal;

import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterRetryAfter;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Internal retry invocation utilities.
 *
 * <p>
 * This class is not part of the supported public API and may change or be removed in any
 * release.
 *
 * @author Subhransu De
 */
public final class Retries {

	/**
	 * Maximum provider-directed delay honored by the blocking retry worker. Longer values
	 * fall back to the application's configured Spring Retry backoff so an untrusted
	 * response cannot park the worker indefinitely.
	 */
	static final Duration MAX_PROVIDER_BACKOFF = Duration.ofMinutes(5);

	private Retries() {
	}

	/**
	 * Invoke a retryable operation while preserving the exception semantics of
	 * {@code RetryTemplate.invoke(Supplier)} without linking to that Spring Framework
	 * 7.0.3+ method.
	 */
	public static <T> T invoke(RetryTemplate retryTemplate, Retryable<T> retryable) {
		AtomicReference<@Nullable Duration> retryAfter = new AtomicReference<>();
		long startedAtNanos = System.nanoTime();
		RetryTemplate effectiveTemplate = retryAfterAware(retryTemplate, retryAfter, startedAtNanos);
		try {
			return effectiveTemplate.execute(() -> {
				retryAfter.set(null);
				try {
					return retryable.execute();
				}
				catch (Throwable ex) {
					retryAfter.set(retryAfter(ex));
					throw ex;
				}
			});
		}
		catch (RetryException ex) {
			Throwable cause = ex.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new UndeclaredThrowableException(cause);
		}
	}

	private static RetryTemplate retryAfterAware(RetryTemplate retryTemplate,
			AtomicReference<@Nullable Duration> retryAfter, long startedAtNanos) {
		RetryTemplate effectiveTemplate = new RetryTemplate(
				new RetryAfterAwarePolicy(retryTemplate.getRetryPolicy(), retryAfter, startedAtNanos));
		effectiveTemplate.setRetryListener(retryTemplate.getRetryListener());
		return effectiveTemplate;
	}

	private static @Nullable Duration retryAfter(Throwable throwable) {
		if (throwable instanceof OpenRouterHttpException httpException) {
			OpenRouterRetryAfter retryAfter = httpException.getRetryAfter();
			return retryAfter != null ? retryAfter.delay() : null;
		}
		return null;
	}

	/**
	 * Select the larger configured or provider delay using Spring Retry's non-negative
	 * {@code long} millisecond representation. A provider extension that cannot fit in
	 * the remaining retry timeout declines the retry without sleeping. Negative values,
	 * values above {@link #MAX_PROVIDER_BACKOFF}, and values that cannot be represented
	 * as milliseconds fall back to the configured delay instead of creating an extreme
	 * worker sleep.
	 */
	static long effectiveBackOffMillis(long configuredDelay, @Nullable Duration providerDelay,
			@Nullable Duration remainingTimeout) {
		if (providerDelay == null || providerDelay.isNegative() || providerDelay.compareTo(MAX_PROVIDER_BACKOFF) > 0) {
			return configuredDelay;
		}
		try {
			long effectiveDelay = Math.max(configuredDelay, providerDelay.toMillis());
			if (effectiveDelay > configuredDelay && remainingTimeout != null) {
				long remainingMillis = remainingTimeout.toMillis();
				if (remainingMillis <= 0 || effectiveDelay >= remainingMillis) {
					return BackOffExecution.STOP;
				}
			}
			return effectiveDelay;
		}
		catch (ArithmeticException ex) {
			return configuredDelay;
		}
	}

	private record RetryAfterAwarePolicy(RetryPolicy delegate, AtomicReference<@Nullable Duration> retryAfter,
			long startedAtNanos) implements RetryPolicy {

		@Override
		public boolean shouldRetry(Throwable throwable) {
			return this.delegate.shouldRetry(throwable);
		}

		@Override
		public Duration getTimeout() {
			return this.delegate.getTimeout();
		}

		@Override
		public BackOff getBackOff() {
			BackOff configured = this.delegate.getBackOff();
			return () -> {
				BackOffExecution execution = configured.start();
				return () -> {
					long configuredDelay = execution.nextBackOff();
					if (configuredDelay == BackOffExecution.STOP) {
						return BackOffExecution.STOP;
					}
					return effectiveBackOffMillis(configuredDelay, this.retryAfter.get(), remainingTimeout());
				};
			};
		}

		private @Nullable Duration remainingTimeout() {
			Duration timeout = this.delegate.getTimeout();
			if (timeout == null || timeout.isZero() || timeout.isNegative()) {
				return null;
			}
			long elapsedNanos = Math.max(0, System.nanoTime() - this.startedAtNanos);
			Duration remaining = timeout.minusNanos(elapsedNanos);
			return remaining.isNegative() || remaining.isZero() ? Duration.ZERO : remaining;
		}

	}

}
