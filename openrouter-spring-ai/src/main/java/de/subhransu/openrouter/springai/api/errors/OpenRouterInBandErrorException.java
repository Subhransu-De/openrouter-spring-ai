package de.subhransu.openrouter.springai.api.errors;

import org.jspecify.annotations.Nullable;
import de.subhransu.openrouter.springai.errors.OpenRouterExceptionMessage;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorClassifier;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorDetails;
import de.subhransu.openrouter.springai.errors.OpenRouterFailure;
import de.subhransu.openrouter.springai.errors.OpenRouterNonTransientApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterTransientApiException;
import org.springframework.http.HttpStatusCode;

/**
 * An in-band OpenRouter error carried in an otherwise successful HTTP response.
 *
 * <p>
 * HTTP failures use {@link OpenRouterTransientApiException} or
 * {@link OpenRouterNonTransientApiException}; this type is for top-level errors emitted
 * inside successful Chat Completions and image response bodies and streams. Responses
 * mappers use the transient/non-transient API exception types. Blocking or streamed
 * errors carried in a {@code choices[n].error} object use the choice-level exception
 * taxonomy. Provider-controlled text is retained only as bounded, credential-safe
 * diagnostic fields and is never included in {@link #getMessage()}.
 *
 * <p>
 * This type is not a Spring AI {@code TransientAiException}, even when
 * {@link #isTransient()} is {@code true}: a top-level error inside a stream raises it for
 * every category, because OpenRouter already accepted that stream.
 *
 * @author Subhransu De
 */
public class OpenRouterInBandErrorException extends RuntimeException implements OpenRouterFailure {

	private final @Nullable HttpStatusCode statusCode;

	private final @Nullable String responseBody;

	private final OpenRouterErrorDetails errorDetails;

	public OpenRouterInBandErrorException(String message, @Nullable HttpStatusCode statusCode,
			@Nullable String responseBody) {
		this(message, statusCode, responseBody, new OpenRouterErrorDetails(null, message, null, null, null,
				OpenRouterErrorClassifier.category(statusCode != null ? statusCode.value() : -1, null, null, message)));
	}

	public OpenRouterInBandErrorException(String message, @Nullable HttpStatusCode statusCode,
			@Nullable String responseBody, @Nullable OpenRouterErrorDetails errorDetails) {
		super(message);
		this.statusCode = statusCode;
		this.responseBody = OpenRouterExceptionMessage.sanitize(responseBody);
		this.errorDetails = errorDetails != null ? errorDetails : OpenRouterErrorDetails.empty();
	}

	public @Nullable HttpStatusCode getStatusCode() {
		return this.statusCode;
	}

	public @Nullable String getResponseBody() {
		return this.responseBody;
	}

	/**
	 * Return the parsed error details.
	 * @return the details, empty when the error had none; never {@code null}
	 */
	public OpenRouterErrorDetails getErrorDetails() {
		return this.errorDetails;
	}

	@Override
	public OpenRouterErrorCategory getCategory() {
		return this.errorDetails.category();
	}

}
