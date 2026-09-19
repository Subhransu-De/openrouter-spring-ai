package de.subhransu.openrouter.springai.api;

import de.subhransu.openrouter.springai.api.dto.ImagesResponse;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import de.subhransu.openrouter.springai.errors.OpenRouterProtocolException;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Validates image JSON before synchronous mapping or streaming fallback conversion.
 *
 * @author Subhransu De
 */
public final class OpenRouterImageResponseValidator {

	private OpenRouterImageResponseValidator() {
	}

	public static void validate(@Nullable ImagesResponse response) {
		if (response == null) {
			throw new OpenRouterProtocolException("Null OpenRouter image response");
		}
		if (response.error() != null) {
			throw OpenRouterApiExceptionFactory.create("OpenRouter image generation failed",
					response.error().toString(), response.error(), null);
		}
		if (response.data() == null) {
			throw new OpenRouterProtocolException("OpenRouter image response requires data");
		}
		for (ImagesResponse.ImageData data : response.data()) {
			if (data == null || !StringUtils.hasText(data.b64Json()) && !StringUtils.hasText(data.url())) {
				throw new OpenRouterProtocolException("OpenRouter image data requires image content");
			}
		}
	}

}
