package de.subhransu.openrouter.springai.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.subhransu.openrouter.springai.errors.OpenRouterRetryAfter;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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

	@ParameterizedTest(name = "{0} failures before success")
	@ValueSource(ints = { 0, 1, 2 })
	void returnsValueOnceAnAttemptSucceeds(int failures) {
		AtomicInteger attempts = new AtomicInteger();

		String result = Retries.invoke(retryTemplate(2), () -> {
			if (attempts.incrementAndGet() <= failures) {
				throw new IllegalStateException("retry");
			}
			return "ok";
		});

		assertThat(result).isEqualTo("ok");
		assertThat(attempts).hasValue(failures + 1);
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

	@ParameterizedTest
	@MethodSource("exhaustedFailures")
	void exhaustionRethrowsUncheckedAndWrapsCheckedThrowables(Throwable failure, Class<? extends Throwable> thrownType,
			UnaryOperator<Throwable> unwrap) {
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> Retries.invoke(retryTemplate(0), () -> {
			attempts.incrementAndGet();
			throw failure;
		})).isInstanceOf(thrownType).satisfies(thrown -> assertThat(unwrap.apply(thrown)).isSameAs(failure));
		assertThat(attempts).hasValue(1);
	}

	static Stream<Arguments> exhaustedFailures() {
		UnaryOperator<Throwable> same = UnaryOperator.identity();
		UnaryOperator<Throwable> cause = Throwable::getCause;
		return Stream.of(
				Arguments.of(Named.of("runtime exception", new IllegalStateException("failure")),
						IllegalStateException.class, same),
				Arguments.of(Named.of("error", new AssertionError("failure")), AssertionError.class, same),
				Arguments.of(Named.of("checked exception", new IOException("failure")),
						UndeclaredThrowableException.class, cause));
	}

	@Test
	void retryAfterThatCannotFitInPolicyTimeoutDeclinesTheRetry() {
		// A one-second Retry-After cannot fit a 50 ms timeout, so the retry is declined
		// even though the configured 1 ms delay alone would have allowed it. This is the
		// observable proof that the provider delay reaches the back-off; the delay value
		// itself is pinned by the effectiveBackOffMillis cases.
		RetryPolicy policy = RetryPolicy.builder()
			.maxRetries(1)
			.includes(TransientAiException.class)
			.delay(Duration.ofMillis(1))
			.timeout(Duration.ofMillis(50))
			.build();
		OpenRouterTransientApiException failure = new OpenRouterTransientApiException("rate limited",
				HttpStatus.TOO_MANY_REQUESTS, "{}", null, new OpenRouterRetryAfter("test", Duration.ofSeconds(1), null),
				"/chat/completions");
		AtomicInteger attempts = new AtomicInteger();

		assertThatThrownBy(() -> Retries.invoke(new RetryTemplate(policy), () -> {
			attempts.incrementAndGet();
			throw failure;
		})).isSameAs(failure);

		assertThat(attempts).hasValue(1);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("backOffCases")
	void effectiveBackOffMillisSelectsTheDelay(String name, long configured, Duration provider, Duration remaining,
			long expected) {
		assertThat(Retries.effectiveBackOffMillis(configured, provider, remaining)).isEqualTo(expected);
	}

	static Stream<Arguments> backOffCases() {
		// The documented five-minute limit on provider-directed delays.
		Duration cap = Duration.ofMinutes(5);
		return Stream.of(Arguments.of("no provider delay keeps the configured delay", 25L, null, null, 25L),
				Arguments.of("larger provider delay wins", 25L, Duration.ofMillis(40), null, 40L),
				Arguments.of("larger configured delay wins", 50L, Duration.ofMillis(40), null, 50L),
				Arguments.of("negative provider delay is ignored", 25L, Duration.ofMillis(-1), null, 25L),
				Arguments.of("just below the provider limit is honored", 25L, cap.minusMillis(1), null,
						cap.toMillis() - 1),
				Arguments.of("at the provider limit is honored", 25L, cap, null, cap.toMillis()),
				Arguments.of("above the provider limit falls back", 25L, cap.plusMillis(1), null, 25L),
				Arguments.of("Long.MAX_VALUE ms falls back", 25L, Duration.ofMillis(Long.MAX_VALUE), null, 25L),
				Arguments.of("overflowing ms falls back", 25L, Duration.ofMillis(Long.MAX_VALUE).plusMillis(1), null,
						25L),
				Arguments.of("below the remaining timeout is honored", 25L, Duration.ofMillis(39),
						Duration.ofMillis(40), 39L),
				Arguments.of("at the remaining timeout stops", 25L, Duration.ofMillis(40), Duration.ofMillis(40),
						(long) BackOffExecution.STOP),
				Arguments.of("above the remaining timeout stops", 25L, Duration.ofMillis(41), Duration.ofMillis(40),
						(long) BackOffExecution.STOP),
				Arguments.of("an exhausted timeout stops", 25L, Duration.ofMillis(40), Duration.ZERO,
						(long) BackOffExecution.STOP),
				Arguments.of("a provider delay not above the configured delay never stops", 50L, Duration.ofMillis(40),
						Duration.ofMillis(10), 50L));
	}

	private RetryTemplate retryTemplate(long maxRetries) {
		RetryPolicy retryPolicy = RetryPolicy.builder().backOff(new FixedBackOff(0, maxRetries)).build();
		return new RetryTemplate(retryPolicy);
	}

}
