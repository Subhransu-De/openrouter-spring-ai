package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import de.subhransu.openrouter.springai.api.OpenRouterApi;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Option values bound from {@code spring.ai.openrouter.chat.*}: undocumented enum-like
 * values start with a warning, and values that are wrong on their own fail at startup
 * instead of at the first request.
 */
@Tag("configuration")
@Tag("configuration.properties")
@ExtendWith(OutputCaptureExtension.class)
class OpenRouterOptionValidationPropertiesTests {

	private static final String PREFIX = "spring.ai.openrouter.chat.";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=test-key", PREFIX + "model=test/model");

	private static OpenRouterChatOptions defaults(
			org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
		return (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class).getOptions();
	}

	@Test
	void undocumentedRoutingAndReasoningValuesStartWithAWarning(CapturedOutput output) {
		this.runner
			.withPropertyValues(PREFIX + "provider.sort=cheapest-boot", PREFIX + "provider.quantizations=fp8,fp7-boot",
					PREFIX + "reasoning.effort=hgih-boot")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var options = defaults(context);
				assertThat(options.getProvider().sort()).isEqualTo("cheapest-boot");
				assertThat(options.getReasoning().effort()).isEqualTo("hgih-boot");
				assertThat(output.getAll()).contains("provider sort \"cheapest-boot\"",
						"provider quantization \"fp7-boot\"", "reasoning effort \"hgih-boot\"");
				assertThat(output.getAll()).doesNotContain("provider quantization \"fp8\"");
			});
	}

	@Test
	void modalitiesAreLowerCased() {
		this.runner.withPropertyValues(PREFIX + "modalities=Image,TEXT")
			.run(context -> assertThat(defaults(context).getModalities()).containsExactly("image", "text"));
	}

	@Test
	void valuesThatAreWrongOnTheirOwnFailAtStartup() {
		this.runner.withPropertyValues(PREFIX + "tool-choice=AUTO")
			.run(context -> assertThat(context.getStartupFailure()).rootCause()
				.hasMessageStartingWith("toolChoice \"AUTO\" is not supported; use auto, none, required"));
		this.runner.withPropertyValues(PREFIX + "response-format.type=json-schema", PREFIX + "response-format.schema= ")
			.run(context -> assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("responseFormat JSON_SCHEMA requires a non-blank schema"));
		this.runner.withPropertyValues(PREFIX + "extra-body.[verbosity]=extreme")
			.run(context -> assertThat(context.getStartupFailure()).rootCause()
				.hasMessage("extraBody verbosity \"extreme\" is not supported; use low, medium, high, xhigh, max"));
		this.runner.withPropertyValues(PREFIX + "extra-body.[logprobs]=true", PREFIX + "extra-body.[top_logprobs]=abc")
			.run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining(
					"IllegalArgumentException: extraBody top_logprobs must be a number, but was \"abc\""));
	}

	@Test
	void responsesModeNamesItselfWhenAChatExtensionIsConfigured() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		this.runner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(PREFIX + "request-mode=openai-responses", PREFIX + "extra-body.[verbosity]=low")
			.run(context -> {
				assertThat(context).hasNotFailed();
				var model = context.getBean(OpenRouterChatModel.class);
				assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("Synthetic input")))
					.withMessageStartingWith("OPENAI_RESPONSES does not support extraBody key \"verbosity\"")
					.withMessageContaining("prompt_cache_key, top_logprobs");
				verifyNoInteractions(api);
			});
	}

	@Test
	void toolChoiceThatNeedsToolsFailsARequestWithoutTools() {
		OpenRouterApi api = mock(OpenRouterApi.class);
		this.runner.withBean(OpenRouterApi.class, () -> api)
			.withPropertyValues(PREFIX + "tool-choice=required")
			.run(context -> {
				var model = context.getBean(OpenRouterChatModel.class);
				assertThatIllegalArgumentException().isThrownBy(() -> model.call(new Prompt("Synthetic input")))
					.withMessageContaining("toolChoice \"required\" needs at least one tool");
				verifyNoInteractions(api);
			});
	}

}
