package de.subhransu.openrouter.springai.garage.evidence;

import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterHttpException;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import de.subhransu.openrouter.springai.errors.OpenRouterTruncatedResponseException;
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
  /** The request did not reach OpenRouter or the connection failed. */
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
   * Classifies a scene failure. A reply cut off at its token limit explains a later failed
   * check, so it outranks {@link #CHECK_FAILED} but not a definite HTTP or protocol error.
   */
  public static SceneFailureReason classify(Throwable failure, boolean truncatedReply) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
      if (cause instanceof SceneFailure known) {
        return known.reason();
      }
      if (cause instanceof OpenRouterHttpException http) {
        return fromStatus(http.getStatusCode());
      }
      // Image streams report in-band errors with this older exception type.
      if (cause instanceof OpenRouterApiException api) {
        return fromStatus(api.getStatusCode());
      }
      if (cause instanceof OpenRouterProtocolException) {
        return PROTOCOL_ERROR;
      }
      if (cause instanceof OpenRouterTruncatedResponseException) {
        return TRUNCATED;
      }
      if (cause instanceof ResourceAccessException || cause instanceof WebClientRequestException) {
        return TRANSPORT_ERROR;
      }
    }
    return truncatedReply ? TRUNCATED : CHECK_FAILED;
  }

  private static SceneFailureReason fromStatus(@Nullable HttpStatusCode status) {
    return status != null && status.value() == 404 ? NO_ENDPOINT : PROVIDER_ERROR;
  }
}
