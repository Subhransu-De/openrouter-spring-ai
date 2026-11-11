package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import de.subhransu.openrouter.springai.api.dto.StreamError;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiException;
import de.subhransu.openrouter.springai.api.errors.OpenRouterApiExceptionFactory;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

class OpenRouterErrorEnvelopeTests {

	private static final String RATE_LIMIT_ERROR = "rate_limit_error";

	private final OpenRouterHttpExceptionFactory httpFactory = new OpenRouterHttpExceptionFactory(new ObjectMapper(),
			null);

	static Stream<Arguments> precedenceRows() {
		return Stream.of(row(RATE_LIMIT_ERROR, null, null, RATE_LIMIT_ERROR, OpenRouterErrorCategory.RATE_LIMIT),
				row(null, "authentication", null, "authentication", OpenRouterErrorCategory.AUTHENTICATION),
				row(null, null, "timeout", "timeout", OpenRouterErrorCategory.TIMEOUT),
				row("authentication", RATE_LIMIT_ERROR, "timeout", "authentication",
						OpenRouterErrorCategory.AUTHENTICATION),
				row(" ", RATE_LIMIT_ERROR, "authentication", RATE_LIMIT_ERROR, OpenRouterErrorCategory.RATE_LIMIT),
				row(null, " ", "permission_denied", "permission_denied", OpenRouterErrorCategory.AUTHORIZATION),
				row("RATE_LIMIT_ERROR", null, null, "RATE_LIMIT_ERROR", OpenRouterErrorCategory.RATE_LIMIT),
				row("future_error", RATE_LIMIT_ERROR, "timeout", "future_error", OpenRouterErrorCategory.UNKNOWN),
				row(null, null, null, null, OpenRouterErrorCategory.PROVIDER_UNAVAILABLE));
	}

	private static Arguments row(@Nullable String root, @Nullable String nested, @Nullable String metadataType,
			@Nullable String expectedType, OpenRouterErrorCategory expectedCategory) {
		return Arguments.of(Named.of("root=%s, nested=%s, metadata=%s".formatted(root, nested, metadataType),
				new Precedence(root, nested, metadataType)), expectedType, expectedCategory);
	}

	private static void assertPrecedence(OpenRouterErrorDetails details, Precedence input,
			@Nullable String expectedType, OpenRouterErrorCategory expectedCategory) {
		assertThat(details.errorType()).isEqualTo(expectedType);
		assertThat(details.category()).isEqualTo(expectedCategory);
		assertThat(details.code()).isEqualTo("503");
		assertThat(details.message()).isEqualTo("failure");
		assertThat(details.providerCode()).isEqualTo("upstream-503");
		assertThat(details.metadata()).isEqualTo(input.metadata());
	}

	@ParameterizedTest
	@MethodSource("precedenceRows")
	void httpFactoryAppliesTheErrorTypePrecedence(Precedence input, @Nullable String expectedType,
			OpenRouterErrorCategory expectedCategory) {
		OpenRouterHttpException http = (OpenRouterHttpException) this.httpFactory.create("/responses",
				HttpStatusCode.valueOf(503), HttpHeaders.EMPTY, input.json());

		assertPrecedence(http.getErrorDetails(), input, expectedType, expectedCategory);
	}

	@ParameterizedTest
	@MethodSource("precedenceRows")
	void truncatedBodyFactoryAppliesTheErrorTypePrecedence(Precedence input, @Nullable String expectedType,
			OpenRouterErrorCategory expectedCategory) {
		String json = input.json();
		OpenRouterHttpException truncated = this.httpFactory.createErrorBodyLimit("/responses",
				HttpStatusCode.valueOf(503), json.substring(0, json.length() - 1), 10, 11);

		assertPrecedence(truncated.getErrorDetails(), input, expectedType, expectedCategory);
	}

	@ParameterizedTest
	@MethodSource("precedenceRows")
	void inBandFactoryAppliesTheErrorTypePrecedence(Precedence input, @Nullable String expectedType,
			OpenRouterErrorCategory expectedCategory) {
		OpenRouterApiException inBand = OpenRouterApiExceptionFactory.create("fallback", input.json(),
				new StreamError("503", "failure", input.metadata(), input.nested()), input.root(),
				OpenRouterDiagnosticSanitizer.keyless());

		assertPrecedence(inBand.getErrorDetails(), input, expectedType, expectedCategory);
		assertThat(inBand.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(503));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " " })
	void blankRootTypeWithoutAnErrorObjectLeavesNoErrorType(String root) {
		OpenRouterHttpException http = (OpenRouterHttpException) this.httpFactory.create("/responses",
				HttpStatusCode.valueOf(429), HttpHeaders.EMPTY, "{\"error_type\":\"" + root + "\"}");
		OpenRouterApiException inBand = OpenRouterApiExceptionFactory.create("fallback", null, null, root,
				OpenRouterDiagnosticSanitizer.keyless());

		assertThat(http.getErrorDetails().errorType()).isNull();
		assertThat(http.getCategory()).isEqualTo(OpenRouterErrorCategory.RATE_LIMIT);
		assertThat(inBand.getErrorDetails().errorType()).isNull();
		assertThat(inBand.getCategory()).isEqualTo(OpenRouterErrorCategory.UNKNOWN);
	}

	private record Precedence(@Nullable String root, @Nullable String nested, @Nullable String metadataType) {

		ObjectNode metadata() {
			ObjectNode metadata = JsonNodeFactory.instance.objectNode().put("provider_code", "upstream-503");
			if (this.metadataType != null) {
				metadata.put("error_type", this.metadataType);
			}
			return metadata;
		}

		String json() {
			ObjectNode error = JsonNodeFactory.instance.objectNode().put("code", "503").put("message", "failure");
			if (this.nested != null) {
				error.put("error_type", this.nested);
			}
			error.set("metadata", metadata());
			ObjectNode body = JsonNodeFactory.instance.objectNode();
			if (this.root != null) {
				body.put("error_type", this.root);
			}
			body.set("error", error);
			return body.toString();
		}

	}

}
