package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.api.errors.OpenRouterInBandErrorException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceErrorDetails;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterChoiceFailure;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterNonTransientChoiceException;
import de.subhransu.openrouter.springai.chat.errors.OpenRouterTransientChoiceException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

@Tag("http")
@Tag("http.errors")
class OpenRouterFailureContractTests {

	private static final String CHAT_COMPLETIONS = "/chat/completions";

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final OpenRouterHttpExceptionFactory factory = new OpenRouterHttpExceptionFactory(this.objectMapper,
			"test-key");

	@Test
	void choiceFailuresStoreEmptyDetailsForNull() {
		List<OpenRouterChoiceFailure> failures = List.of(new OpenRouterTransientChoiceException("failed", null),
				new OpenRouterNonTransientChoiceException("failed", null));

		for (OpenRouterChoiceFailure failure : failures) {
			OpenRouterChoiceErrorDetails details = failure.getErrorDetails();
			assertThat(details.choiceIndex()).isNull();
			assertThat(details.message()).isNull();
			assertThat(details.metadata()).isEmpty();
			assertThat(failure.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
		}
	}

	@Test
	void apiExceptionStoresEmptyDetailsForNull() {
		OpenRouterInBandErrorException exception = new OpenRouterInBandErrorException("failed", HttpStatus.BAD_GATEWAY,
				null, null);

		assertThat(exception.getErrorDetails()).isEqualTo(OpenRouterErrorDetails.empty());
		assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	@Test
	void httpExceptionsStoreEmptyDetailsForNullAndLimitsKeepTheUnknownCategory() {
		var nonTransient = new OpenRouterNonTransientApiException("failed", HttpStatus.BAD_REQUEST, null, null, null,
				CHAT_COMPLETIONS);
		var limit = new OpenRouterLimitExceededException(OpenRouterLimitExceededException.Limit.CHAT_STATE_BYTES, 10,
				11, CHAT_COMPLETIONS, null, null, null);

		for (OpenRouterHttpFailure exception : List.<OpenRouterHttpFailure>of(nonTransient, limit)) {
			assertThat(exception.getErrorDetails()).isEqualTo(OpenRouterErrorDetails.empty());
			assertThat(exception.getFailureDetails().errorDetails()).isSameAs(exception.getErrorDetails());
			assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
		}
		assertThat(new OpenRouterHttpFailureDetails(null, null, null, null, null).errorDetails())
			.isEqualTo(OpenRouterErrorDetails.empty());
	}

	@Test
	void absentMetadataIsAMissingNodeThatStillSerializesAsNull() {
		OpenRouterErrorDetails details = new OpenRouterErrorDetails("429", "slow down", null, null, null);

		assertThat(details.metadata().isMissingNode()).isTrue();
		assertThat(details.metadata().path("provider_name").asString(null)).isNull();
		assertThat(this.objectMapper.writeValueAsString(details)).contains("\"metadata\":null");
	}

	@Test
	void suppliedMetadataIsADetachedSanitizedCopy() {
		var metadata = JsonNodeFactory.instance.objectNode().put("provider_name", "Example");
		OpenRouterErrorDetails details = new OpenRouterErrorDetails(null, null, null, null, metadata);
		metadata.put("provider_name", "Changed");

		assertThat(details.metadata().path("provider_name").asString()).isEqualTo("Example");
	}

	@Test
	void retryDelayFlattensTheRetryAfterHeader() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "7");
		var withHeader = (OpenRouterHttpFailure) this.factory.create(CHAT_COMPLETIONS, HttpStatus.TOO_MANY_REQUESTS,
				headers, null);
		var withoutHeader = (OpenRouterHttpFailure) this.factory.create(CHAT_COMPLETIONS, HttpStatus.TOO_MANY_REQUESTS,
				HttpHeaders.EMPTY, null);

		assertThat(withHeader.getRetryDelay()).isEqualTo(Duration.ofSeconds(7));
		assertThat(withoutHeader.getRetryDelay()).isNull();
	}

	@Test
	void everyFailureFamilyImplementsTheSharedInterface() {
		List<RuntimeException> failures = List.of(
				this.factory.create(CHAT_COMPLETIONS, HttpStatus.SERVICE_UNAVAILABLE, HttpHeaders.EMPTY, null),
				this.factory.create(CHAT_COMPLETIONS, HttpStatus.UNAUTHORIZED, HttpHeaders.EMPTY, null),
				new OpenRouterLimitExceededException(OpenRouterLimitExceededException.Limit.CHAT_STATE_BYTES, 10, 11,
						CHAT_COMPLETIONS, null, null, null),
				new OpenRouterTransientChoiceException("failed", null),
				new OpenRouterNonTransientChoiceException("failed", null),
				new OpenRouterInBandErrorException("failed", HttpStatus.TOO_MANY_REQUESTS, null),
				new OpenRouterProtocolException("broken"), new OpenRouterTruncatedResponseException("cut"),
				new OpenRouterTransportException(OpenRouterTransportException.Kind.IO, "/responses",
						new java.io.IOException("synthetic")));

		assertThat(failures).allSatisfy(failure -> assertThat(failure).isInstanceOf(OpenRouterFailure.class));
		assertThat(failures).extracting(failure -> ((OpenRouterFailure) failure).isTransient())
			.containsExactly(true, false, false, false, false, true, false, false, true);
	}

	@Test
	void inBandStreamErrorReportsTransientWithoutBeingASpringAiTransientException() {
		OpenRouterInBandErrorException exception = new OpenRouterInBandErrorException("failed",
				HttpStatus.TOO_MANY_REQUESTS, null);

		assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
		assertThat(exception.isTransient()).isTrue();
		assertThat(exception).isNotInstanceOf(TransientAiException.class);
	}

	@Test
	void limitOnATransientErrorBodyIsNotTransient() {
		OpenRouterLimitExceededException limit = this.factory.createErrorBodyLimit(CHAT_COMPLETIONS,
				HttpStatusCode.valueOf(503), "{\"error\":", 10, 11);

		assertThat(limit.getCategory()).isEqualTo(OpenRouterErrorCategory.PROVIDER_UNAVAILABLE);
		assertThat(limit.isTransient()).isFalse();
		assertThat(limit.isStreamStateLimit()).isFalse();
	}

	@Test
	void localLimitsAreTheStreamStateLimits() {
		assertThat(OpenRouterLimitExceededException.Limit.values())
			.filteredOn(OpenRouterLimitExceededException.Limit::isStreamStateLimit)
			.allSatisfy(limit -> assertThat(limit.name()).doesNotContain("BODY", "EVENT"));
		assertThat(OpenRouterLimitExceededException.Limit.values()).filteredOn(limit -> !limit.isStreamStateLimit())
			.containsExactlyInAnyOrder(OpenRouterLimitExceededException.Limit.BLOCKING_RESPONSE_BODY_BYTES,
					OpenRouterLimitExceededException.Limit.BLOCKING_ERROR_BODY_BYTES,
					OpenRouterLimitExceededException.Limit.STREAMING_ERROR_BODY_BYTES,
					OpenRouterLimitExceededException.Limit.STREAMING_EVENT_BYTES);
	}

	@Test
	void legacyProtocolConstructorsReportAContractFailureWithoutEndpoint() {
		OpenRouterProtocolException exception = new OpenRouterProtocolException("broken", new TimeoutException());

		assertThat(exception.getKind()).isEqualTo(OpenRouterProtocolException.Kind.CONTRACT);
		assertThat(exception.getEndpoint()).isNull();
		assertThat(exception.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

}
