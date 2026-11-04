package de.subhransu.openrouter.springai.errors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class OpenRouterExceptionMessageTests {

	private static final String SYNTHETIC_KEY = "synthetic-Key+/=_9";

	@ParameterizedTest
	@MethodSource("encodedEchoes")
	void configuredKeyIsRedactedInPlainEncodedAndMixedForms(String echo) {
		assertThat(OpenRouterExceptionMessage.sanitize("before " + echo + " after " + echo, SYNTHETIC_KEY))
			.isEqualTo("before [REDACTED] after [REDACTED]");
	}

	@ParameterizedTest
	@MethodSource("encodedEchoes")
	void retainedHttpBodiesAndDetailsRedactEncodedKeys(String echo) {
		var factory = new OpenRouterHttpExceptionFactory(new ObjectMapper(), SYNTHETIC_KEY);
		String body = "{\"error\":{\"message\":\"echo " + echo + "\",\"metadata\":{\"echo\":\"" + echo + "\"}}}";
		var whole = (OpenRouterHttpException) factory.create("/chat/completions", HttpStatus.UNAUTHORIZED,
				HttpHeaders.EMPTY, body);
		var truncated = factory.createErrorBodyLimit("/images", HttpStatus.UNAUTHORIZED, body, 1024, 1025);
		for (OpenRouterHttpException error : List.of(whole, truncated)) {
			assertThat(error.getResponseBody()).isEqualTo(body.replace(echo, "[REDACTED]"));
			assertThat(error.getErrorDetails().message()).isEqualTo("echo [REDACTED]");
			assertThat(error.getErrorDetails().metadata().path("echo").stringValue()).isEqualTo("[REDACTED]");
			assertThat(error.toString()).doesNotContain(echo, SYNTHETIC_KEY);
		}
	}

	@Test
	void unrelatedEncodedTextAndDifferentKeyCaseRemainUnchanged() {
		for (String text : List.of("ordinary %2f path \\u0041 text", SYNTHETIC_KEY.toLowerCase(Locale.ROOT),
				"synthetic-Key%2B%2F%3D_8", "partial synthetic-Key")) {
			assertThat(OpenRouterExceptionMessage.sanitize(text, SYNTHETIC_KEY)).isEqualTo(text);
		}
		assertThat(OpenRouterExceptionMessage.sanitize("ordinary encoded text", " "))
			.isEqualTo("ordinary encoded text");
	}

	@Test
	void nonAsciiKeysMatchUtf8PercentAndUtf16JsonEscapes() {
		String key = "synthetic-\u2603-\uD83D\uDE80";
		String unicode = "synthetic-\\u2603-\\uD83D\\uDE80";
		for (String echo : List.of(key, unicode, URLEncoder.encode(key, StandardCharsets.UTF_8),
				"synthetic-\\u2603-%F0%9f%9A%80")) {
			assertThat(OpenRouterExceptionMessage.sanitize("echo " + echo, key)).isEqualTo("echo [REDACTED]");
		}
	}

	private static Stream<String> encodedEchoes() {
		StringBuilder percent = new StringBuilder();
		StringBuilder unicode = new StringBuilder();
		StringBuilder mixed = new StringBuilder();
		for (int i = 0; i < SYNTHETIC_KEY.length(); i++) {
			char value = SYNTHETIC_KEY.charAt(i);
			String url = "%" + HexFormat.of().toHexDigits((byte) value);
			String json = "\\u" + HexFormat.of().toHexDigits(value);
			percent.append(url);
			unicode.append(json);
			mixed.append(switch (i % 3) {
				case 0 -> String.valueOf(value);
				case 1 -> url;
				default -> json;
			});
		}
		return Stream.of(SYNTHETIC_KEY, percent.toString(), percent.toString().toUpperCase(Locale.ROOT),
				unicode.toString(), unicode.toString().replace('b', 'B').replace('f', 'F').replace('d', 'D'),
				mixed.toString(), URLEncoder.encode(SYNTHETIC_KEY, StandardCharsets.UTF_8));
	}

	@Test
	void diagnosticPolicyBoundsRedactsAndNeutralizesProviderText() {
		String diagnostic = "first line\r\nsecond line\u001B[31m Bearer secret-token " + "api_key=another-secret "
				+ "x".repeat(1200);

		String sanitized = OpenRouterExceptionMessage.sanitize(diagnostic);

		assertThat(sanitized).hasSize(OpenRouterExceptionMessage.MAX_DIAGNOSTIC_LENGTH + 3)
			.startsWith("first line second line [31m Bearer [REDACTED] api_key=[REDACTED]")
			.endsWith("...")
			.doesNotContain("\r", "\n", "\u001B", "secret-token", "another-secret");
	}

	@Test
	void hostMessageNeverIncludesTheProviderDiagnostic() {
		assertThat(OpenRouterExceptionMessage.build("OpenRouter request failed", "provider\r\ninjected"))
			.isEqualTo("OpenRouter request failed");
	}

	@Test
	void hostMessageIsPreservedWhenItMatchesTheProviderDiagnostic() {
		String hostMessage = "Unauthorized";

		assertThat(OpenRouterExceptionMessage.build(hostMessage, "Unauthorized")).isSameAs(hostMessage);
	}

}
