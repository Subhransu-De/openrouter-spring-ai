package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.api.OpenRouterRequestMode;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.test.StepVerifier;

class OpenRouterBlankSchemaTests {

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t\r\n" })
	void blankSchemasFailBeforeDispatch(String schema) {
		for (OpenRouterRequestMode mode : OpenRouterRequestMode.values()) {
			for (boolean defaults : List.of(false, true)) {
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
	}

}
