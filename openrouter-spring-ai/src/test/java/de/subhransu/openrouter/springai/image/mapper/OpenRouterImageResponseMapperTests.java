package de.subhransu.openrouter.springai.image.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import de.subhransu.openrouter.springai.api.dto.ImagesStreamEvent;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.errors.OpenRouterErrorCategory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("http")
@Tag("http.errors")
class OpenRouterImageResponseMapperTests {

	@Test
	void imageErrorEventUsesStableMessageAndSafeStructuredDiagnostics() {
		ImagesStreamEvent event = new ImagesStreamEvent(ImagesStreamEvent.ERROR_EVENT, null, null, null, null, null,
				new StreamError("provider_error", "first\r\nsecond\u001B[31m Bearer image-secret"));

		assertThatThrownBy(() -> new OpenRouterImageResponseMapper().map(event))
			.isInstanceOfSatisfying(OpenRouterApiException.class, exception -> {
				assertThat(exception.getMessage()).isEqualTo("OpenRouter image generation stream failed");
				assertThat(exception.getErrorDetails().code()).isEqualTo("provider_error");
				assertThat(exception.getErrorDetails().message()).isEqualTo("first second [31m Bearer [REDACTED]");
				assertThat(exception.getResponseBody()).doesNotContain("\r", "\n", "\u001B", "image-secret");
			});
	}

	@Test
	void imageErrorEventPropagatesTypedFields() throws Exception {
		ImagesStreamEvent event = new ObjectMapper().readValue("""
				{"type":"error","error":{"code":"server_error","message":"invalid key",
				 "error_type":"authentication","metadata":{"provider_code":"bad_key"}}}
				""", ImagesStreamEvent.class);

		assertThatThrownBy(() -> new OpenRouterImageResponseMapper().map(event))
			.isInstanceOfSatisfying(OpenRouterApiException.class, exception -> {
				assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.AUTHENTICATION);
				assertThat(exception.getErrorDetails().errorType()).isEqualTo("authentication");
				assertThat(exception.getErrorDetails().providerCode()).isEqualTo("bad_key");
			});
	}

}
