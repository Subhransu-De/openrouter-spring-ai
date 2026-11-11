package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Spring AI's error-classification keys configure its ResponseErrorHandler, which
 * OpenRouter models do not use. They are reported instead of being ignored silently.
 */
@ExtendWith(OutputCaptureExtension.class)
class OpenRouterRetrySettingsTests {

	private static final String WARNING = "do not apply to OpenRouter models";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, OpenRouterApiAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@ParameterizedTest
	@ValueSource(strings = { "spring.ai.retry.on-http-codes=429", "spring.ai.retry.exclude-on-http-codes=500",
			"spring.ai.retry.on-client-errors=true" })
	void warnsAboutErrorClassificationKeysThatDoNotApplyWhenNoOtherProviderReadsThem(String property,
			CapturedOutput output) {
		// The narrower selectors such as spring.ai.model.ocr stay unset, as in a
		// single-provider application, and no other provider is on the classpath.
		this.contextRunner.withPropertyValues(property).run(context -> {
			assertThat(context).hasNotFailed();
			String key = property.substring(0, property.indexOf('='));
			assertThat(output).containsPattern("WARN .*" + key + " " + WARNING);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration",
			"org.springframework.ai.model.elevenlabs.autoconfigure.ElevenLabsAutoConfiguration",
			"org.springframework.ai.model.mistralai.autoconfigure.MistralAiChatAutoConfiguration",
			"org.springframework.ai.model.mistralai.autoconfigure.MistralAiEmbeddingAutoConfiguration",
			"org.springframework.ai.model.mistralai.autoconfigure.MistralAiModerationAutoConfiguration",
			"org.springframework.ai.model.mistralai.autoconfigure.MistralAiOcrAutoConfiguration",
			"org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration" })
	void onlyInformsWhenAnotherProviderReadsTheKeys(String autoConfiguration, CapturedOutput output) {
		// A provider whose auto-configuration passes Spring AI's error handler to its
		// client is active, so a warning would be noise. The auto-configuration does not
		// prove that its client reads the keys, because its client factory can back off
		// for an application's own client, so the message only says they may. The bean
		// name is the class name because Boot registers an imported auto-configuration
		// under it, which is how the check finds another provider.
		this.contextRunner.withBean(autoConfiguration, Object.class, Object::new)
			.withPropertyValues("spring.ai.retry.on-http-codes=429")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).containsPattern("INFO .*" + WARNING).doesNotContainPattern("WARN .*" + WARNING);
				assertThat(output).contains("They may still configure another Spring AI provider's client");
			});
	}

	/**
	 * The list of providers that read the keys is written by hand from the Spring AI
	 * release named here. A test cannot find a provider that is not on the classpath, so
	 * a new release fails this test until its provider auto-configurations have been
	 * reread and this version is updated.
	 */
	@Test
	void theListWasReviewedAgainstThisSpringAiRelease() {
		assertThat(TransientAiException.class.getPackage().getImplementationVersion())
			.as("Spring AI release; review ERROR_HANDLER_CONSUMERS in OpenRouterApiAutoConfiguration")
			.isEqualTo("2.0.2");
	}

	@Test
	void staysQuietForKeysThatApply(CapturedOutput output) {
		this.contextRunner
			.withPropertyValues("spring.ai.retry.max-attempts=2", "spring.ai.retry.backoff.initial-interval=1s",
					"spring.ai.retry.on-client-errors=false")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).doesNotContain(WARNING);
			});
	}

}
