package de.subhransu.openrouter.springai.errors;

import org.jspecify.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;

/**
 * Parsed form of an HTTP {@code Retry-After} response header.
 *
 * @param value original header value
 * @param delay delay relative to when the response was received, when valid
 * @param retryAt absolute retry time for the HTTP-date form, otherwise {@code null}
 * @author Subhransu De
 */
public record OpenRouterRetryAfter(String value, @Nullable Duration delay, @Nullable Instant retryAt) {
}
