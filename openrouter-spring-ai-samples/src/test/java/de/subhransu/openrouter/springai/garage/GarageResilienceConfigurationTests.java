package de.subhransu.openrouter.springai.garage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.core.retry.RetryException;
import org.springframework.web.client.ResourceAccessException;

class GarageResilienceConfigurationTests {

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

  static Stream<RuntimeException> permanentFailures() {
    return Stream.of(
        new NonTransientAiException("authentication"),
        new NonTransientAiException("billing"),
        new IllegalArgumentException("unsupported option"),
        new IllegalStateException("local validation"),
        new RuntimeException("unknown failure"));
  }

  @ParameterizedTest
  @MethodSource("transientFailures")
  void transientFailuresRecoverWithinBoundedRetries(RuntimeException failure) throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    var template = new GarageResilienceConfiguration().garageRetryTemplate();

    String result = template.execute(() -> {
      if (attempts.incrementAndGet() < 3) {
        throw failure;
      }
      return "recovered";
    });

    assertThat(result).isEqualTo("recovered");
    assertThat(attempts).hasValue(3);
  }

  @ParameterizedTest
  @MethodSource("transientFailures")
  void transientFailuresStopAfterTwoRetries(RuntimeException failure) {
    AtomicInteger attempts = new AtomicInteger();
    var template = new GarageResilienceConfiguration().garageRetryTemplate();

    assertThatThrownBy(() -> template.execute(() -> {
      attempts.incrementAndGet();
      throw failure;
    })).isInstanceOf(RetryException.class);
    assertThat(attempts).hasValue(3);
  }

  static Stream<RuntimeException> transientFailures() {
    return Stream.of(new TransientAiException("provider unavailable"),
        new ResourceAccessException("transport failure"));
  }
}
