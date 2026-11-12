package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
@Tag("configuration")
@Tag("configuration.http")
class OpenRouterRetrySettingsTests {

	private static final String WARNING = "do not apply to OpenRouter models";

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(SpringAiRetryAutoConfiguration.class, OpenRouterApiAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@ParameterizedTest
	@ValueSource(strings = { "spring.ai.retry.on-http-codes=429", "spring.ai.retry.exclude-on-http-codes=500",
			"spring.ai.retry.on-client-errors=true" })
	void reportsErrorClassificationKeysThatDoNotApply(String property, CapturedOutput output) {
		this.contextRunner.withPropertyValues(property).run(context -> {
			assertThat(context).hasNotFailed();
			String key = property.substring(0, property.indexOf('='));
			assertThat(output).contains(key + " " + WARNING);
		});
	}

	@Test
	void warnsWhenNoOtherProviderReadsTheKeys(CapturedOutput output) {
		// The narrower selectors such as spring.ai.model.ocr stay unset, as in a
		// single-provider application; no other provider is on the classpath.
		this.contextRunner.withPropertyValues("spring.ai.retry.on-http-codes=429").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(output).containsPattern("WARN .*" + WARNING);
		});
	}

	@ParameterizedTest
	@ValueSource(strings = { "org.springframework.ai.model.mistralai.autoconfigure.MistralAiOcrAutoConfiguration",
			"org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration" })
	void onlyInformsWhenAnotherProviderReadsTheKeys(String autoConfiguration, CapturedOutput output) {
		// A provider whose auto-configuration passes Spring AI's error handler to its
		// client is active, so a warning would be noise. The auto-configuration does not
		// prove that its client reads the keys, because its client factory can back off
		// for an application's own client, so the message only says they may.
		this.contextRunner.withBean(autoConfiguration, Object.class, Object::new)
			.withPropertyValues("spring.ai.retry.on-http-codes=429")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).containsPattern("INFO .*" + WARNING).doesNotContainPattern("WARN .*" + WARNING);
				assertThat(output).contains("They may still configure another Spring AI provider's client");
			});
	}

	@Test
	void bootRegistersAutoConfigurationsUnderTheirClassName() {
		// The level check relies on this naming to see which providers are active.
		this.contextRunner.run(context -> assertThat(
				context.getBeanFactory().containsBeanDefinition(SpringAiRetryAutoConfiguration.class.getName()))
			.isTrue());
	}

	@Test
	void errorHandlerConsumersWereCheckedForThisSpringAiLine() {
		// The level check names the Spring AI 2.0 auto-configurations that pass the error
		// handler to a client. A new minor release can add or rename one.
		assertThat(SpringAiRetryAutoConfiguration.class.getPackage().getImplementationVersion())
			.as("Re-check OpenRouterApiAutoConfiguration.ERROR_HANDLER_CONSUMERS against the new Spring AI "
					+ "auto-configurations, then update this version")
			.startsWith("2.0.");
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
