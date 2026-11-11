package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

class OpenRouterBlankSchemaTests {

	static Stream<Arguments> blankSchemas() {
		var schemas = Stream
			.of(Named.of("empty schema", ""), Named.of("space schema", " "), Named.of("whitespace schema", "\t\r\n"))
			.toList();
		var modes = Stream
			.of(Named.of("chat completions", OpenRouterRequestMode.OPENAI_CHAT_COMPLETIONS),
					Named.of("responses", OpenRouterRequestMode.OPENAI_RESPONSES))
			.toList();
		var sources = Stream.of(Named.of("per-request options", false), Named.of("model defaults", true)).toList();
		return schemas.stream()
			.flatMap(schema -> modes.stream()
				.flatMap(mode -> sources.stream().map(source -> Arguments.of(schema, mode, source))));
	}

	@ParameterizedTest(name = "{0}, {1}, {2}")
	@MethodSource("blankSchemas")
	void blankSchemasFailBeforeDispatch(String schema, OpenRouterRequestMode mode, boolean defaults) {
		OpenRouterApi api = mock(OpenRouterApi.class, invocation -> {
			throw new AssertionError("Blank schema reached HTTP dispatch: " + invocation.getMethod().getName());
		});
		var options = OpenRouterChatOptions.builder()
			.model("synthetic")
			.requestMode(mode)
			.responseFormat(OpenRouterResponseFormat.jsonSchema(schema))
			.build();
		var builder = OpenRouterChatModel.builder().openRouterApi(api);
		var model = (defaults ? builder.defaultOptions(options) : builder).build();
		var prompt = defaults ? new Prompt("synthetic") : new Prompt("synthetic", options);
		assertThatThrownBy(() -> model.call(prompt)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Invalid JSON schema");
		StepVerifier.create(model.stream(prompt))
			.expectErrorSatisfies(error -> assertThat(error).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Invalid JSON schema"))
			.verify();
		verifyNoInteractions(api);
	}

}
