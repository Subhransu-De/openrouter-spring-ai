package de.subhransu.openrouter.springai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import de.subhransu.openrouter.springai.chat.OpenRouterAudioOptions;
import de.subhransu.openrouter.springai.chat.OpenRouterChatModel;
import de.subhransu.openrouter.springai.chat.OpenRouterChatOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OpenRouterAudioPropertiesTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(
				AutoConfigurations.of(OpenRouterApiAutoConfiguration.class, OpenRouterChatAutoConfiguration.class))
		.withPropertyValues("spring.ai.openrouter.api-key=synthetic-key",
				"spring.ai.openrouter.chat.model=synthetic/audio");

	@Test
	void bindsAudioAndModalitiesIntoModelDefaults() {
		this.runner
			.withPropertyValues("spring.ai.openrouter.chat.modalities=text,audio",
					"spring.ai.openrouter.chat.audio.voice=alloy", "spring.ai.openrouter.chat.audio.format=pcm16")
			.run(context -> {
				assertThat(context).hasNotFailed();
				OpenRouterChatOptions options = (OpenRouterChatOptions) context.getBean(OpenRouterChatModel.class)
					.getOptions();
				assertThat(options.getAudio()).isEqualTo(new OpenRouterAudioOptions("alloy", "pcm16"));
				assertThat(options.getModalities()).containsExactly("text", "audio");
			});
	}

	@Test
	void absentAudioStaysUnsetAndInvalidFormatsFailBinding() {
		this.runner.run(
				context -> assertThat(context.getBean(OpenRouterChatProperties.class).toOptions().getAudio()).isNull());
		this.runner
			.withPropertyValues("spring.ai.openrouter.chat.audio.voice=alloy",
					"spring.ai.openrouter.chat.audio.format=unsupported")
			.run(context -> assertThat(context).hasFailed());
	}

}
