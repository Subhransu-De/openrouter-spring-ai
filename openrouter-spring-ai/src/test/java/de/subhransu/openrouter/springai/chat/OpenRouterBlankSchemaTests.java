package de.subhransu.openrouter.springai.chat;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("api")
@Tag("api.request")
class OpenRouterBlankSchemaTests {

	// The format record rejects a blank schema, so a Boot default fails at startup
	// rather than at the first request, in either request mode.
	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\t\r\n" })
	void blankSchemasFailWhenTheFormatIsBuilt(String schema) {
		assertThatIllegalArgumentException().isThrownBy(() -> OpenRouterResponseFormat.jsonSchema(schema))
			.withMessageContaining("Invalid JSON schema")
			.withMessageContaining("responseFormat JSON_SCHEMA requires a non-blank schema");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> OpenRouterResponseFormat.jsonSchema("answer", true, schema))
			.withMessageContaining("Invalid JSON schema");
		assertThatIllegalArgumentException()
			.isThrownBy(
					() -> new OpenRouterResponseFormat(OpenRouterResponseFormat.Type.JSON_SCHEMA, null, null, schema))
			.withMessageContaining("but schema was \"" + schema + "\"");
	}

}
