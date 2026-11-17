package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterPromptCacheOptions;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("configuration")
@Tag("configuration.properties")
class OpenRouterTypedRequestOptionsPropertiesTests {

	private static final String PREFIX = "spring.ai.openrouter.chat.";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key");

	@Test
	void bindsTheTypedRequestOptionDefaults() {
		this.runner
			.withPropertyValues(PREFIX + "logprobs=true", PREFIX + "top-logprobs=0", PREFIX + "logit-bias.[50256]=-100",
					PREFIX + "verbosity=low", PREFIX + "prompt-cache-key=synthetic-key",
					PREFIX + "prompt-cache-options.mode=explicit", PREFIX + "prompt-cache-options.ttl=12h")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterChatOptions options = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
					.getOptions();
				assertThat(options.getLogprobs()).isTrue();
				assertThat(options.getTopLogprobs()).isZero();
				assertThat(options.getLogitBias()).containsExactlyEntriesOf(Map.of("50256", -100));
				assertThat(options.getVerbosity()).isEqualTo("low");
				assertThat(options.getPromptCacheKey()).isEqualTo("synthetic-key");
				assertThat(options.getPromptCacheOptions()).isEqualTo(OpenRouterPromptCacheOptions.explicit("12h"));
			});
	}

	@Test
	void unsetPropertiesLeaveTheTypedRequestOptionsUnset() {
		this.runner.run(context -> {
			OpenRouterChatOptions options = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
				.getOptions();
			assertThat(options.getLogprobs()).isNull();
			assertThat(options.getTopLogprobs()).isNull();
			assertThat(options.getLogitBias()).isNull();
			assertThat(options.getVerbosity()).isNull();
			assertThat(options.getPromptCacheKey()).isNull();
			assertThat(options.getPromptCacheOptions()).isNull();
		});
	}

	@Test
	void invalidTypedRequestOptionPropertiesFailStartup() {
		this.runner.withPropertyValues(PREFIX + "top-logprobs=21")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("topLogprobs must be an integer from 0 to 20"));
		this.runner.withPropertyValues(PREFIX + "verbosity=loud")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("verbosity \"loud\" is not supported"));
		// Explicit mode turns automatic caching off, so a lifetime alone does not select
		// it.
		this.runner.withPropertyValues(PREFIX + "prompt-cache-options.ttl=12h")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("promptCacheOptions mode must be set"));
		this.runner.withPropertyValues(PREFIX + "logprobs=true", PREFIX + "extra-body.[logprobs]=true")
			.run(context -> assertThat(context).hasFailed()
				.getFailure()
				.rootCause()
				.hasMessageContaining("extraBody logprobs true conflicts with the typed option true"));
	}

}
