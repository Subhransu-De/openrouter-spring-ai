package de.subhransu.openrouter.springai.api.errors;

import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import org.springframework.http.HttpStatusCode;

/**
 * An in-band OpenRouter error carried in an otherwise successful HTTP response.
 *
 * <p>
 * HTTP failures use {@link OpenRouterTransientApiException} or
 * {@link OpenRouterNonTransientApiException}; this legacy type remains for top-level
 * errors emitted inside successful Chat Completions and image response bodies and
 * streams. Responses mappers use the transient/non-transient API exception types.
 * Blocking or streamed errors carried in a {@code choices[n].error} object use the
 * choice-level exception taxonomy. Provider-controlled text is retained only as bounded,
 * credential-safe diagnostic fields and is never included in {@link #getMessage()}.
 *
 * @author Subhransu De
 */
public class OpenRouterApiException extends RuntimeException {

	private final HttpStatusCode statusCode;

	private final String responseBody;

	private final OpenRouterErrorDetails errorDetails;

	public OpenRouterApiException(String message, HttpStatusCode statusCode, String responseBody) {
		this(message, statusCode, responseBody, new OpenRouterErrorDetails(null, message, null, null, null,
				OpenRouterErrorClassifier.category(statusCode != null ? statusCode.value() : -1, null, null, message)));
	}

	public OpenRouterApiException(String message, HttpStatusCode statusCode, String responseBody,
			OpenRouterErrorDetails errorDetails) {
		super(message);
		this.statusCode = statusCode;
		this.responseBody = OpenRouterExceptionMessage.sanitize(responseBody);
		this.errorDetails = errorDetails;
	}

	public HttpStatusCode getStatusCode() {
		return this.statusCode;
	}

	public String getResponseBody() {
		return this.responseBody;
	}

	public OpenRouterErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

	public OpenRouterErrorCategory getCategory() {
		return this.errorDetails != null ? this.errorDetails.category() : OpenRouterErrorCategory.UNKNOWN;
	}

}
