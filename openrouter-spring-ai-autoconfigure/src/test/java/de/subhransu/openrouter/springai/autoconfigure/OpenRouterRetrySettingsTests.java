package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
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
class OpenRouterRetrySettingsTests {

	private static final String WARNING = "do not apply to OpenRouter models";

	private static final String[] EVERY_SELECTOR_RULES_OUT_OTHER_PROVIDERS = { "spring.ai.model.chat=openrouter",
			"spring.ai.model.embedding=none", "spring.ai.model.embedding.text=none",
			"spring.ai.model.embedding.multimodal=none", "spring.ai.model.image=none",
			"spring.ai.model.audio.speech=none", "spring.ai.model.audio.transcription=none",
			"spring.ai.model.moderation=none" };

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
	void warnsWhenEveryModelSelectorRulesOutOtherProviders(CapturedOutput output) {
		this.contextRunner.withPropertyValues(EVERY_SELECTOR_RULES_OUT_OTHER_PROVIDERS)
			.withPropertyValues("spring.ai.retry.on-http-codes=429")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).containsPattern("WARN .*" + WARNING);
			});
	}

	@Test
	void onlyInformsWhenANarrowerSelectorIsUnset(CapturedOutput output) {
		// Google GenAI text embeddings select on spring.ai.model.embedding.text, so
		// setting spring.ai.model.embedding alone does not rule them out.
		String[] withoutTextEmbedding = Arrays.stream(EVERY_SELECTOR_RULES_OUT_OTHER_PROVIDERS)
			.filter((property) -> !property.startsWith("spring.ai.model.embedding.text="))
			.toArray(String[]::new);
		this.contextRunner.withPropertyValues(withoutTextEmbedding)
			.withPropertyValues("spring.ai.retry.on-http-codes=429")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).containsPattern("INFO .*" + WARNING).doesNotContainPattern("WARN .*" + WARNING);
			});
	}

	@Test
	void onlyInformsWhenAnotherProviderMayUseTheKeys(CapturedOutput output) {
		// With a selector left unset or naming another provider, the keys may configure
		// that provider's error handler, so a warning on every startup would be noise.
		this.contextRunner
			.withPropertyValues("spring.ai.retry.on-http-codes=429", "spring.ai.model.chat=openrouter",
					"spring.ai.model.embedding=mistral")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(output).containsPattern("INFO .*" + WARNING).doesNotContainPattern("WARN .*" + WARNING);
			});
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
