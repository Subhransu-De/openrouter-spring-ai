package de.subhransu.openrouter.springai.errors;

/**
 * Common inspection contract for every failure this library raises for an OpenRouter
 * request: HTTP and in-band errors, choice errors, transport failures, protocol
 * violations, truncated responses and client-side limits.
 *
 * <p>
 * Java cannot catch an interface, so catch {@link RuntimeException} and test with
 * {@code instanceof OpenRouterFailure}. Applications should not implement this interface.
 *
 * @author Subhransu De
 */
public interface OpenRouterFailure {

	/**
	 * Return the stable application-facing category.
	 * @return the failure category, {@link OpenRouterErrorCategory#UNKNOWN} when the
	 * failure has none
	 */
	OpenRouterErrorCategory getCategory();

	/**
	 * Return whether the same request may succeed when sent again later: a rate-limit,
	 * timeout or provider-availability failure, or a transport failure. This describes
	 * the failure, not whether a retry is safe. A failure inside a stream that OpenRouter
	 * already accepted may have run, and been billed, upstream; the library never retries
	 * such a stream.
	 * @return {@code true} when the failure is temporary
	 */
	default boolean isTransient() {
		return OpenRouterErrorClassifier.isTransient(getCategory());
	}

}
