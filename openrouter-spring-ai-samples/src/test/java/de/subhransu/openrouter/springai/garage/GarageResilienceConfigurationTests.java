package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.web.client.ResourceAccessException;

class GarageResilienceConfigurationTests {

  private final RetryPolicy policy = new GarageResilienceConfiguration().garageRetryTemplate().getRetryPolicy();

  @ParameterizedTest
  @MethodSource("permanentFailures")
  void permanentFailuresAreAttemptedOnce(RuntimeException failure) {
    AtomicInteger attempts = new AtomicInteger();
    var template = new GarageResilienceConfiguration().garageRetryTemplate();

    assertThatThrownBy(() -> template.execute(() -> {
      attempts.incrementAndGet();
      throw failure;
    })).isInstanceOf(RetryException.class);
    assertThat(attempts).hasValue(1);
  }

  static Stream<Arguments> permanentFailures() {
    return Stream.of(
        Arguments.of(Named.of("authentication", new NonTransientAiException("authentication"))),
        Arguments.of(Named.of("billing", new NonTransientAiException("billing"))),
        Arguments.of(Named.of("unsupported option", new IllegalArgumentException("unsupported option"))),
        Arguments.of(Named.of("local validation", new IllegalStateException("local validation"))),
        Arguments.of(Named.of("unknown failure", new RuntimeException("unknown failure"))));
  }

  @ParameterizedTest
  @MethodSource("transientFailures")
  void transientFailuresAreRetried(RuntimeException failure) {
    assertThat(this.policy.shouldRetry(failure)).isTrue();
  }

  static Stream<Arguments> transientFailures() {
    return Stream.of(
        Arguments.of(Named.of("provider unavailable", new TransientAiException("provider unavailable"))),
        Arguments.of(Named.of("transport failure", new ResourceAccessException("transport failure"))));
  }

  @Test
  void retriesStopAfterTwoRetriesSpacedByTheConfiguredBackoff() {
    BackOffExecution backOff = this.policy.getBackOff().start();
    List<Long> delays = new ArrayList<>();
    for (long delay = backOff.nextBackOff(); delay != BackOffExecution.STOP; delay = backOff.nextBackOff()) {
      delays.add(delay);
    }

    assertThat(delays).containsExactly(200L, 200L);
  }

  // The retry template offers no seam to replace its sleeper, so this one test pays the two real
  // 200 ms backoffs to prove the configured template really attempts three times and then
  // recovers. The policy and the backoff schedule are asserted above without sleeping.
  @Test
  void aTransientFailureRecoversOnTheThirdAttempt() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    var template = new GarageResilienceConfiguration().garageRetryTemplate();

    String result = template.execute(() -> {
      if (attempts.incrementAndGet() < 3) {
        throw new TransientAiException("provider unavailable");
      }
      return "recovered";
    });

    assertThat(result).isEqualTo("recovered");
    assertThat(attempts).hasValue(3);
  }
}
