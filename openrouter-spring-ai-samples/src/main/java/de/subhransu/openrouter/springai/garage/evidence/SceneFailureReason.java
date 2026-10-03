package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Why a scene failed, as a fixed code that is safe to persist. Exception messages can carry
 * model or provider text and are redacted from evidence; these codes are not.
 */
public enum SceneFailureReason {

  /** OpenRouter had no endpoint that could serve the request (HTTP 404). */
  NO_ENDPOINT("no-endpoint"),
  /** OpenRouter or the provider returned another HTTP or in-band error. */
  PROVIDER_ERROR("provider-error"),
  /** A response broke the wire contract the library enforces. */
  PROTOCOL_ERROR("protocol-error"),
  /** The request did not reach OpenRouter, the connection failed, or the reply timed out. */
  TRANSPORT_ERROR("transport-error"),
  /** A model reply ended early: at its token limit, or before its stream finished. */
  TRUNCATED("truncated"),
  /** The scene's own checks failed on a complete reply. */
  CHECK_FAILED("check-failed");

  private final String code;

  SceneFailureReason(String code) {
    this.code = code;
  }

  public String code() {
    return this.code;
  }

  public static SceneFailureReason fromCode(String code) {
    for (SceneFailureReason reason : values()) {
      if (reason.code.equals(code)) {
        return reason;
      }
    }
    throw new IllegalArgumentException("Unknown scene failure reason: " + code);
  }

  /**
   * Resolves a failure from the evidence of the model calls that led to it, given as
   * {@link CallOutcome} codes in start order. The first of these decides: a reason the scene
   * recorded itself, the first call that ended in a classified error, the failure's own type, a
   * call cut off at its token limit (which usually explains a failed check), and otherwise
   * {@link #CHECK_FAILED}.
   */
  public static SceneFailureReason resolve(@Nullable Throwable failure, List<String> callOutcomes) {
    for (Throwable cause = failure; cause != null; cause = next(cause)) {
      if (cause instanceof SceneFailure recorded) {
        return recorded.reason();
      }
    }
    for (String outcome : callOutcomes) {
      if (!CallOutcome.OK.equals(outcome) && !CallOutcome.UNCLASSIFIED.equals(outcome)
          && !TRUNCATED.code.equals(outcome)) {
        return fromCode(outcome);
      }
    }
    SceneFailureReason thrown = failure != null ? classify(failure) : null;
    if (thrown != null) {
      return thrown;
    }
    return callOutcomes.contains(TRUNCATED.code) ? TRUNCATED : CHECK_FAILED;
  }

  /**
   * Classifies an error by the library or transport exception in its cause chain, or returns
   * {@code null} when the chain holds neither.
   */
  public static @Nullable SceneFailureReason classify(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = next(cause)) {
      SceneFailureReason reason = classifyOne(cause);
      if (reason != null) {
        return reason;
      }
    }
    return null;
  }

  private static @Nullable SceneFailureReason classifyOne(Throwable cause) {
    if (cause instanceof OpenRouterHttpException http) {
      return fromStatus(http.getStatusCode());
    }
    // Image streams report in-band errors with this older exception type.
    if (cause instanceof OpenRouterApiException api) {
      return fromStatus(api.getStatusCode());
    }
    // A Chat Completions choice carried an error or an error finish reason.
    if (cause instanceof OpenRouterChoiceFailure) {
      return PROVIDER_ERROR;
    }
    if (cause instanceof OpenRouterProtocolException) {
      return PROTOCOL_ERROR;
    }
    if (cause instanceof OpenRouterTruncatedResponseException) {
      return TRUNCATED;
    }
    // The client's response timeout surfaces as Reactor's TimeoutException.
    if (cause instanceof ResourceAccessException || cause instanceof WebClientRequestException
        || cause instanceof TimeoutException) {
      return TRANSPORT_ERROR;
    }
    return null;
  }

  private static @Nullable Throwable next(Throwable cause) {
    return cause.getCause() == cause ? null : cause.getCause();
  }

  private static SceneFailureReason fromStatus(@Nullable HttpStatusCode status) {
    return status != null && status.value() == 404 ? NO_ENDPOINT : PROVIDER_ERROR;
  }
}
