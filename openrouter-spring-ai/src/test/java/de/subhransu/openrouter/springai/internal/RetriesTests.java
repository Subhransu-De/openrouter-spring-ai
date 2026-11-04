package de.subhransu.openrouter.springai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.errors.OpenRouterRetryAfter;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.Retryable;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

class RetriesTests {

	@Test
	void returnsValueWhenFirstAttemptSucceeds() {
		AtomicInteger attempts = new AtomicInteger();

		String result = Retries.invoke(retryTemplate(2), () -> {
			attempts.incrementAndGet();
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts).hasValue(1);
	}

	@Test
	void returnsValueWhenLaterAttemptSucceeds() {
		AtomicInteger attempts = new AtomicInteger();

		String result = Retries.invoke(retryTemplate(2), () -> {
			if (attempts.incrementAndGet() < 3) {
				throw new IllegalStateException("retry");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts).hasValue(3);
	}

	@Test
	void propagatesListenerCallbacksAndUsesTheCurrentTemplateConfiguration() {
		RetryTemplate template = retryTemplate(1);
		AtomicInteger executions = new AtomicInteger();
		AtomicInteger retries = new AtomicInteger();
		template.setRetryListener(new RetryListener() {
			@Override
			public void onRetryableExecution(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
				executions.incrementAndGet();
			}

			@Override
			public void beforeRetry(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
				retries.incrementAndGet();
			}
		});
		AtomicInteger attempts = new AtomicInteger();
		assertThat(Retries.invoke(template, () -> {
			if (attempts.incrementAndGet() == 1) {
				throw new IllegalStateException("synthetic retry");
			}
			return "ok";
		})).isEqualTo("ok");
		assertThat(executions).hasValue(2);
		assertThat(retries).hasValue(1);

		template.setRetryPolicy(RetryPolicy.withMaxRetries(0));
		AtomicInteger updatedExecutions = new AtomicInteger();
		template.setRetryListener(new RetryListener() {
			@Override
			public void onRetryableExecution(RetryPolicy policy, Retryable<?> retryable, RetryState state) {
				updatedExecutions.incrementAndGet();
			}
		});
		IllegalStateException failure = new IllegalStateException("synthetic exhaustion");
		assertThatThrownBy(() -> Retries.invoke(template, () -> {
			throw failure;
		})).isSameAs(failure);
		assertThat(updatedExecutions).hasValue(1);
		assertThat(executions).hasValue(2);
		assertThat(retries).hasValue(1);
	}

	@Test
	void propagatesRuntimeExceptionAfterExhaustion() {
		IllegalStateException failure = new IllegalStateException("failure");

		assertThatThrownBy(() -> Retries.invoke(retryTemplate(0), () -> {
			throw failure;
		})).isSameAs(failure);
	}

	@Test
	void propagatesErrorAfterExhaustion() {
		AssertionError failure = new AssertionError("failure");

		assertThatThrownBy(() -> Retries.invoke(retryTemplate(0), () -> {
			throw failure;
		})).isSameAs(failure);
	}

	@Test
	void wrapsCheckedThrowableAfterExhaustion() {
		IOException failure = new IOException("failure");

		assertThatThrownBy(() -> Retries.invoke(retryTemplate(0), () -> {
			throw failure;
		})).isInstanceOf(UndeclaredThrowableException.class).extracting(Throwable::getCause).isSameAs(failure);
	}

	@Test
	void retryAfterExtendsConfiguredBackoffWithoutChangingAttemptCount() {
		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(1)
			.includes(TransientAiException.class)
			.delay(Duration.ofMillis(5))
			.build();
		AtomicInteger attempts = new AtomicInteger();
		long started = System.nanoTime();

		String result = Retries.invoke(new RetryTemplate(policy), () -> {
			if (attempts.incrementAndGet() == 1) {
				throw new OpenRouterTransientApiException("rate limited", HttpStatus.TOO_MANY_REQUESTS, "{}", null,
						new OpenRouterRetryAfter("test", Duration.ofMillis(40), null), "/chat/completions");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts).hasValue(2);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(30));
	}

	@Test
	void retryAfterThatCannotFitInPolicyTimeoutDoesNotSleepOrRetry() {
		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(1)
			.includes(TransientAiException.class)
			.delay(Duration.ofMillis(5))
			.timeout(Duration.ofMillis(50))
			.build();
		OpenRouterTransientApiException failure = new OpenRouterTransientApiException("rate limited",
				HttpStatus.TOO_MANY_REQUESTS, "{}", null, new OpenRouterRetryAfter("test", Duration.ofSeconds(1), null),
				"/chat/completions");
		AtomicInteger attempts = new AtomicInteger();
		long started = System.nanoTime();

		assertThatThrownBy(() -> Retries.invoke(new RetryTemplate(policy), () -> {
			attempts.incrementAndGet();
			throw failure;
		})).isSameAs(failure);

		assertThat(attempts).hasValue(1);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(500));
	}

	@Test
	void retryAfterJustBelowProviderLimitIsHonored() {
		Duration delay = Retries.MAX_PROVIDER_BACKOFF.minusMillis(1);

		assertThat(Retries.effectiveBackOffMillis(25, delay, null)).isEqualTo(delay.toMillis());
	}

	@Test
	void retryAfterAtProviderLimitIsHonored() {
		assertThat(Retries.effectiveBackOffMillis(25, Retries.MAX_PROVIDER_BACKOFF, null))
			.isEqualTo(Retries.MAX_PROVIDER_BACKOFF.toMillis());
	}

	@Test
	void retryAfterAboveProviderLimitFallsBackToConfiguredDelay() {
		assertThat(Retries.effectiveBackOffMillis(25, Retries.MAX_PROVIDER_BACKOFF.plusMillis(1), null)).isEqualTo(25);
	}

	@Test
	void hugeRepresentableRetryAfterFallsBackToConfiguredDelay() {
		assertThat(Retries.effectiveBackOffMillis(25, Duration.ofMillis(Long.MAX_VALUE), null)).isEqualTo(25);
	}

	@Test
	void retryAfterOverflowFallsBackToConfiguredDelay() {
		Duration overflowingDelay = Duration.ofMillis(Long.MAX_VALUE).plusMillis(1);

		assertThat(Retries.effectiveBackOffMillis(25, overflowingDelay, null)).isEqualTo(25);
	}

	@Test
	void retryAfterUsesLargerNormalDelay() {
		assertThat(Retries.effectiveBackOffMillis(25, Duration.ofMillis(40), null)).isEqualTo(40);
		assertThat(Retries.effectiveBackOffMillis(50, Duration.ofMillis(40), null)).isEqualTo(50);
	}

	@Test
	void retryAfterBelowRemainingTimeoutIsHonored() {
		assertThat(Retries.effectiveBackOffMillis(25, Duration.ofMillis(39), Duration.ofMillis(40))).isEqualTo(39);
	}

	@Test
	void retryAfterAtOrAboveRemainingTimeoutStops() {
		assertThat(Retries.effectiveBackOffMillis(25, Duration.ofMillis(40), Duration.ofMillis(40)))
			.isEqualTo(BackOffExecution.STOP);
		assertThat(Retries.effectiveBackOffMillis(25, Duration.ofMillis(41), Duration.ofMillis(40)))
			.isEqualTo(BackOffExecution.STOP);
	}

	private RetryTemplate retryTemplate(long maxRetries) {
		RetryPolicy retryPolicy = RetryPolicy.builder().backOff(new FixedBackOff(0, maxRetries)).build();
		return new RetryTemplate(retryPolicy);
	}

}
