package de.subhransu.openrouter.springai.garage.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

class SceneFailureReasonTests {

  private static final IllegalStateException CHECK = new IllegalStateException("synthetic check failed");

  static Stream<Arguments> classifiedFailures() {
    return Stream.of(
        row("HTTP 404", new OpenRouterNonTransientApiException("synthetic", HttpStatus.NOT_FOUND, null, null, null, null),
            SceneFailureReason.NO_ENDPOINT),
        row("HTTP 400", new OpenRouterNonTransientApiException("synthetic", HttpStatus.BAD_REQUEST, null, null, null, null),
            SceneFailureReason.PROVIDER_ERROR),
        row("HTTP 429", new OpenRouterTransientApiException("synthetic", HttpStatus.TOO_MANY_REQUESTS, null, null, null, null),
            SceneFailureReason.PROVIDER_ERROR),
        row("HTTP failure without a status", new OpenRouterNonTransientApiException("synthetic", null, null, null, null, null),
            SceneFailureReason.PROVIDER_ERROR),
        row("in-band error with status 404", new OpenRouterApiException("synthetic", HttpStatus.NOT_FOUND, "{}"),
            SceneFailureReason.NO_ENDPOINT),
        row("in-band error with status 502", new OpenRouterApiException("synthetic", HttpStatus.BAD_GATEWAY, "{}"),
            SceneFailureReason.PROVIDER_ERROR),
        row("choice failure", new OpenRouterNonTransientChoiceException("synthetic", null), SceneFailureReason.PROVIDER_ERROR),
        row("protocol violation", new OpenRouterProtocolException("synthetic"), SceneFailureReason.PROTOCOL_ERROR),
        row("truncated reply", new OpenRouterTruncatedResponseException("synthetic"), SceneFailureReason.TRUNCATED),
        row("I/O failure of the blocking client", new ResourceAccessException("synthetic"),
            SceneFailureReason.TRANSPORT_ERROR),
        row("request failure of the reactive client",
            new WebClientRequestException(new IOException("synthetic"), HttpMethod.POST,
                URI.create("https://openrouter.example/api"), new HttpHeaders()),
            SceneFailureReason.TRANSPORT_ERROR),
        row("response timeout", new TimeoutException("synthetic"), SceneFailureReason.TRANSPORT_ERROR),
        row("library failure below a wrapper",
            new IllegalStateException("wrapper", new RuntimeException("middle",
                new OpenRouterNonTransientApiException("synthetic", HttpStatus.NOT_FOUND, null, null, null, null))),
            SceneFailureReason.NO_ENDPOINT));
  }

  private static Arguments row(String name, Throwable failure, SceneFailureReason expected) {
    return Arguments.of(Named.of(name, failure), expected);
  }

  @ParameterizedTest
  @MethodSource("classifiedFailures")
  void classifyNamesTheReasonOfEachLibraryAndTransportFailure(Throwable failure, SceneFailureReason expected) {
    assertThat(SceneFailureReason.classify(failure)).isEqualTo(expected);
  }

  @Test
  void classifyLeavesAnUnrelatedFailureUnclassified() {
    assertThat(SceneFailureReason.classify(new IllegalStateException("synthetic check failed", new IOException("io"))))
        .isNull();
  }

  @Test
  void aRecordedReasonWinsOverEveryCall() {
    var recorded = new SceneFailure("synthetic", SceneFailureReason.CHECK_FAILED);
    assertThat(SceneFailureReason.resolve(recorded, List.of("provider-error"))).isEqualTo(SceneFailureReason.CHECK_FAILED);
  }

  @Test
  void theFirstErroredCallExplainsALaterFailedCheck() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "truncated", "no-endpoint", "provider-error")))
        .isEqualTo(SceneFailureReason.NO_ENDPOINT);
  }

  @Test
  void anErroredCallOutranksTheThrownFailure() {
    var thrown = new OpenRouterProtocolException("synthetic");
    assertThat(SceneFailureReason.resolve(thrown, List.of("provider-error", "protocol-error")))
        .isEqualTo(SceneFailureReason.PROVIDER_ERROR);
  }

  @Test
  void theThrownFailureOutranksATruncatedCall() {
    var thrown = new IllegalStateException(new OpenRouterProtocolException("synthetic"));
    assertThat(SceneFailureReason.resolve(thrown, List.of("truncated", "error")))
        .isEqualTo(SceneFailureReason.PROTOCOL_ERROR);
  }

  @Test
  void aTruncatedCallExplainsAFailedCheck() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "error", "truncated"))).isEqualTo(SceneFailureReason.TRUNCATED);
  }

  @Test
  void completeCallsLeaveTheCheckToBlame() {
    assertThat(SceneFailureReason.resolve(CHECK, List.of("ok", "error"))).isEqualTo(SceneFailureReason.CHECK_FAILED);
    assertThat(SceneFailureReason.resolve(null, List.of())).isEqualTo(SceneFailureReason.CHECK_FAILED);
  }
}
